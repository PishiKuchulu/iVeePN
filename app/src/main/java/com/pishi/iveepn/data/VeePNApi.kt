package com.pishi.iveepn.data

import com.pishi.iveepn.data.model.VeePNLocation
import com.pishi.iveepn.data.model.VeePNServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** API failure. [status] is the HTTP status, or 0 for local/rotation failures. */
class ApiError(val status: Int, val payload: String?) : IOException(
    "HTTP $status: ${payload?.take(200) ?: "no payload"}",
)

/**
 * The ONE strict validator for VeePN API base domains (desktop lesson:
 * exactly one implementation, used for defaults, reserve domains and any
 * cached value alike). https only, sane hostname, optional port 1..65535.
 * The guest token is sent to every domain in the rotation, so nothing
 * unvalidated may ever enter it. Pure Kotlin — no Android imports.
 */
object ApiDomainValidator {
    private val pattern = Regex(
        """^https://(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\.)+[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?::\d{1,5})?$""",
    )

    fun isValid(candidate: String): Boolean {
        val u = candidate.trim().trimEnd('/')
        if (!pattern.matches(u)) return false
        val rest = u.removePrefix("https://")
        if (':' in rest) {
            val port = rest.substringAfterLast(':').toIntOrNull() ?: return false
            if (port !in 1..65535) return false
        }
        return true
    }
}

/**
 * Parser for POST /v3/server/list/ responses. Returns only usable entries
 * (non-empty addresses, valid port); an empty result means the response was
 * unusable and must never be cached. Pure Kotlin — no Android imports.
 */
object VeePNServerListParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<VeePNServer> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val array = root as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val addresses = (obj["addresses"] as? JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            val port = obj["port"]?.jsonPrimitive?.intOrNull ?: 0
            if (addresses.isEmpty() || port !in 1..65535) return@mapNotNull null
            VeePNServer(
                addresses = addresses,
                port = port,
                username = obj["username"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                password = obj["password"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }
}

/** Result of the locations endpoint: every location plus the free subset. */
data class LocationsResult(
    val all: List<VeePNLocation>,
    val free: List<VeePNLocation>,
)

/**
 * Client for the VeePN free-extension backend (F-01..F-03), a behaviour
 * port of the desktop reference client: domain rotation over the default
 * domains plus validated reserve domains from two public buckets, a guest
 * token from POST /v3/launch/, locations, and per-region server lists.
 *
 * Honesty note: the launch request sends a stable per-install UUID and the
 * device time zone to VeePN — that is what the protocol requires; it is
 * surfaced in the README, not hidden.
 */
class VeePNApi(
    private val store: TokenStore,
    private val client: OkHttpClient = defaultClient(),
) {

    @Volatile
    private var domainsCache: List<String>? = null

    // ------------------------------------------------------------ token

    /** Guest access token, cached with a 7-day soft TTL (F-01). */
    suspend fun ensureToken(force: Boolean = false): String = withContext(Dispatchers.IO) {
        val cached = store.guestToken()
        val now = System.currentTimeMillis()
        if (!force && cached != null && now - cached.fetchedAtMs < TOKEN_SOFT_TTL_MS) {
            return@withContext cached.access
        }
        AppLogger.i(TAG, "Requesting guest access token (POST /v3/launch/)")
        val body = buildLaunchBody()
        val domains = getDomains()
        val deadline = System.nanoTime() + REQUEST_BUDGET_MS * 1_000_000L
        var last = "unknown"
        val attempts = minOf(domains.size, MAX_DOMAIN_ATTEMPTS)
        for (attempt in 0 until attempts) {
            if (attempt > 0 && System.nanoTime() > deadline) break
            val domain = domains[attempt % domains.size]
            val response = try {
                http(domain + LAUNCH_PATH, "POST", body, authToken = null, client)
            } catch (e: IOException) {
                last = "$domain: ${e.message}"
                delay(minOf(backoffSeconds(attempt), 5) * 1000L)
                continue
            }
            if (response.code == 200) {
                val access = parseJsonObject(response.text)?.get("access")
                    ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                if (access != null) {
                    store.saveGuestToken(access, System.currentTimeMillis())
                    AppLogger.i(TAG, "Guest token acquired via $domain")
                    return@withContext access
                }
                last = "$domain: bad launch payload"
            } else if (response.code == 429 || response.code >= 500) {
                last = "$domain: HTTP ${response.code}"
                delay(backoffSeconds(attempt) * 1000L)
            } else {
                throw ApiError(response.code, response.text)
            }
        }
        throw ApiError(0, "Could not obtain token ($last)")
    }

    // ------------------------------------------------------------ locations

    /** All locations plus the free subset (proxyType == 0) (F-02). */
    suspend fun locations(): LocationsResult = withContext(Dispatchers.IO) {
        val text = request(LOCATIONS_PATH, "GET", null, auth = true)
        val root = parseJsonObject(text)
        val array = root?.get("locations") as? JsonArray ?: return@withContext LocationsResult(emptyList(), emptyList())
        val all = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val region = obj["region"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            VeePNLocation(
                region = region,
                name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                countryCode = obj["countryCode"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                proxyType = obj["proxyType"]?.jsonPrimitive?.intOrNull ?: -1,
            )
        }
        LocationsResult(all = all, free = all.filter { it.proxyType == 0 })
    }

    /** The backend's suggested optimal location, if it returns a region. */
    suspend fun optimalLocation(): VeePNLocation? = withContext(Dispatchers.IO) {
        val text = request(OPTIMAL_PATH, "GET", null, auth = true)
        val obj = parseJsonObject(text) ?: return@withContext null
        val region = obj["region"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        VeePNLocation(
            region = region,
            name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            countryCode = obj["countryCode"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            proxyType = obj["proxyType"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    // ------------------------------------------------------------ server list

    /**
     * Usable servers for [region] (F-03), cached 12h per region. A fresh
     * response that parses to zero usable servers is an error and is never
     * cached.
     */
    suspend fun serverList(region: String, forceRefresh: Boolean = false): List<VeePNServer> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cache = store.cachedServerList(region)
            if (!forceRefresh && cache != null && now - cache.fetchedAtMs < SERVERS_CACHE_TTL_MS) {
                return@withContext cache.servers
            }
            val body = """{"protocol":"https","region":${jsonString(region)},"type":0}"""
            val text = request(SERVER_LIST_PATH, "POST", body, auth = true)
            val servers = VeePNServerListParser.parse(text)
            if (servers.isEmpty()) {
                throw ApiError(0, "Region '$region' returned no usable server entries")
            }
            store.saveServerList(region, servers, System.currentTimeMillis())
            servers
        }

    // ------------------------------------------------------------ ip check

    /** Public IP via the reference's echo mirrors, 6s each (direct, no tunnel). */
    suspend fun checkIpDirect(): String? = withContext(Dispatchers.IO) {
        val echoClient = client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
        for (mirror in IP_ECHO_MIRRORS) {
            try {
                val response = http(mirror, "GET", null, authToken = null, echoClient)
                if (response.code != 200) continue
                val obj = parseJsonObject(response.text)
                val ip = obj?.get("ip")?.jsonPrimitive?.contentOrNull
                if (!ip.isNullOrBlank()) return@withContext ip.trim()
                val match = IPV4_REGEX.find(response.text)
                if (match != null) return@withContext match.groupValues[1]
            } catch (e: IOException) {
                continue
            }
        }
        null
    }

    // ------------------------------------------------------------ internals

    /**
     * Authed request with domain rotation: 401/403 invalidates the token and
     * retries once with a fresh one; 429/5xx backs off and rotates; other
     * 4xx surfaces as [ApiError]; network errors rotate. Bounded by
     * [MAX_DOMAIN_ATTEMPTS] and the 120s wall budget.
     */
    private suspend fun request(path: String, method: String, body: String?, auth: Boolean): String {
        val domains = getDomains()
        val deadline = System.nanoTime() + REQUEST_BUDGET_MS * 1_000_000L
        var lastError = "unknown"
        var tokenRefreshed = false
        val attempts = minOf(domains.size, MAX_DOMAIN_ATTEMPTS)
        for (attempt in 0 until attempts) {
            if (attempt > 0 && System.nanoTime() > deadline) {
                lastError += " (request budget exhausted)"
                break
            }
            val token = if (auth) ensureToken(force = tokenRefreshed) else null
            val domain = domains[attempt % domains.size]
            val response = try {
                http(domain + path, method, body, token, client)
            } catch (e: IOException) {
                lastError = "$domain unreachable (${e.message})"
                delay(minOf(backoffSeconds(attempt), 3) * 1000L)
                continue
            }
            when {
                response.code in 200..299 -> return response.text
                auth && (response.code == 401 || response.code == 403) && !tokenRefreshed -> {
                    tokenRefreshed = true
                    store.clearGuestToken()
                    lastError = "HTTP ${response.code} from $domain (token rejected)"
                }
                response.code == 429 || response.code >= 500 -> {
                    lastError = "HTTP ${response.code} from $domain"
                    delay(backoffSeconds(attempt) * 1000L)
                }
                else -> throw ApiError(response.code, response.text)
            }
        }
        throw ApiError(0, "All API attempts failed ($lastError)")
    }

    /**
     * Default domains + validated reserve domains. Reserve cache lasts 24h;
     * an empty fetch is never cached and a stale cache is reused when the
     * fetch fails.
     */
    private suspend fun getDomains(): List<String> {
        domainsCache?.let { return it }
        val domains = DEFAULT_FREE_DOMAINS.toMutableList()
        val now = System.currentTimeMillis()
        val cached = store.cachedReserveDomains()
        val extra: List<String> = if (cached != null && cached.first.isNotEmpty() &&
            now - cached.second < RESERVE_CACHE_TTL_MS
        ) {
            cached.first
        } else {
            val fetched = fetchReserveDomains()
            if (fetched.isNotEmpty()) {
                store.saveReserveDomains(fetched, now)
                fetched
            } else {
                cached?.first ?: emptyList()
            }
        }
        for (candidate in extra) {
            val clean = candidate.trim().trimEnd('/')
            if (ApiDomainValidator.isValid(clean) && clean !in domains) domains.add(clean)
        }
        val result = domains.toList()
        domainsCache = result
        return result
    }

    private fun fetchReserveDomains(): List<String> {
        val bucketClient = client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val found = mutableListOf<String>()
        for (bucket in RESERVE_BUCKET_URLS) {
            try {
                val response = http(bucket, "GET", null, authToken = null, bucketClient)
                if (response.code != 200) continue
                val root = parseJsonObject(response.text) ?: continue
                val domainsObj = root["domains"] as? JsonObject
                (domainsObj?.get("free") as? JsonArray)?.forEach { element ->
                    val url = element.jsonPrimitive.contentOrNull
                    if (url != null && ApiDomainValidator.isValid(url)) found.add(url.trim().trimEnd('/'))
                }
                when (val free = root["free"]) {
                    is kotlinx.serialization.json.JsonPrimitive ->
                        free.contentOrNull?.takeIf { ApiDomainValidator.isValid(it) }
                            ?.let { found.add(it.trim().trimEnd('/')) }
                    is JsonArray -> free.forEach { element ->
                        val url = element.jsonPrimitive.contentOrNull
                        if (url != null && ApiDomainValidator.isValid(url)) {
                            found.add(url.trim().trimEnd('/'))
                        }
                    }
                    else -> Unit
                }
            } catch (e: IOException) {
                continue
            }
        }
        return found.distinct().take(MAX_RESERVE_DOMAINS)
    }

    private fun buildLaunchBody(): String {
        val tz = TimeZone.getDefault().id
        return buildString {
            append('{')
            append("\"udid\":").append(jsonString(store.udid)).append(',')
            append("\"appVersion\":").append(jsonString(APP_VERSION)).append(',')
            append("\"platform\":\"chrome\",")
            append("\"platformVersion\":").append(jsonString(USER_AGENT)).append(',')
            append("\"timeZone\":").append(jsonString(tz)).append(',')
            append("\"deviceName\":").append(jsonString(DEVICE_NAME))
            append('}')
        }
    }

    private data class HttpResult(val code: Int, val text: String)

    private fun http(url: String, method: String, body: String?, authToken: String?, httpClient: OkHttpClient): HttpResult {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
        if (authToken != null) builder.header("Authorization", "Bearer $authToken")
        if (method == "POST") {
            builder.post((body ?: "{}").toRequestBody(JSON_MEDIA))
        }
        return httpClient.newCall(builder.build()).execute().use { response ->
            HttpResult(response.code, response.body?.string().orEmpty())
        }
    }

    private fun parseJsonObject(text: String): JsonObject? =
        runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()

    private fun jsonString(value: String): String =
        Json.encodeToString(kotlinx.serialization.serializer<String>(), value)

    private fun backoffSeconds(attempt: Int): Long =
        BACKOFF_SECONDS[minOf(attempt, BACKOFF_SECONDS.lastIndex)]

    companion object {
        private const val TAG = "VeePNApi"

        const val APP_VERSION = "5.0.2"
        const val DEVICE_NAME = "Chrome 130"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        val DEFAULT_FREE_DOMAINS = listOf(
            "https://antpeak.com",
            "https://hibchr.com",
            "https://hisball.com",
            "https://bitphox.com",
            "https://freloop.com",
            "https://tronlit.com",
            "https://tronyza.com",
        )

        val RESERVE_BUCKET_URLS = listOf(
            "https://s3-oregon-1.s3-us-west-2.amazonaws.com/api.json",
            "https://proigor.com/payload.json",
        )

        const val MAX_DOMAIN_ATTEMPTS = 14
        const val MAX_RESERVE_DOMAINS = 10
        const val REQUEST_BUDGET_MS = 120_000L
        const val API_TIMEOUT_SECONDS = 12L
        const val SERVERS_CACHE_TTL_MS = 12L * 3600 * 1000
        const val TOKEN_SOFT_TTL_MS = 7L * 24 * 3600 * 1000
        const val RESERVE_CACHE_TTL_MS = 24L * 3600 * 1000
        val BACKOFF_SECONDS = listOf(1L, 2L, 4L, 8L, 10L)

        val IP_ECHO_MIRRORS = listOf(
            "https://api.ipify.org?format=json",
            "https://ifconfig.me/ip",
            "https://api.seeip.org/jsonip",
            "https://ipinfo.io/json",
        )

        private const val LAUNCH_PATH = "/v3/launch/"
        private const val LOCATIONS_PATH = "/v3/location/extension/"
        private const val OPTIMAL_PATH = "/v3/location/optimal/"
        private const val SERVER_LIST_PATH = "/v3/server/list/"

        private val JSON_MEDIA = "application/json".toMediaType()
        private val IPV4_REGEX = Regex("""\b(\d{1,3}(?:\.\d{1,3}){3})\b""")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}

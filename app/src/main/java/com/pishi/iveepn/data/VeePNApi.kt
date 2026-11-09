package com.pishi.iveepn.data

import com.pishi.iveepn.data.model.VeePNLocation
import com.pishi.iveepn.data.model.VeePNServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import com.pishi.iveepn.vpn.upstream.FragmentingSocketFactory
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** API failure. [status] is the HTTP status, or 0 for local/rotation failures. */
class ApiError(val status: Int, val payload: String?) : IOException(
    "HTTP $status: ${payload?.take(200) ?: "no payload"}",
)

/**
 * The ONE strict validator for VeePN API base domains (desktop lesson:
 * exactly one implementation, used for defaults, reserve domains and any
 * cached value alike). https only, sane NAMED hostname, optional port
 * 1..65535. IP literals and localhost are rejected: the guest token is
 * sent to every domain in the rotation, so nothing unvalidated may ever
 * enter it. Pure Kotlin — no Android imports, no DNS resolution.
 */
object ApiDomainValidator {
    private val pattern = Regex(
        """^https://(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\.)+[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?::\d{1,5})?$""",
    )

    fun isValid(candidate: String): Boolean {
        val u = candidate.trim().trimEnd('/')
        if (!pattern.matches(u)) return false
        val rest = u.removePrefix("https://")
        val host = rest.substringBefore(':')
        // Named hosts only: an IP literal (v4 or v6, in ANY range —
        // loopback, private, link-local, multicast or public) or a
        // localhost name must never enter the rotation.
        if (isIpLiteral(host)) return false
        if (host.equals("localhost", ignoreCase = true) ||
            host.endsWith(".localhost", ignoreCase = true)
        ) {
            return false
        }
        if (':' in rest) {
            val port = rest.substringAfterLast(':').toIntOrNull() ?: return false
            if (port !in 1..65535) return false
        }
        return true
    }

    private fun isIpLiteral(host: String): Boolean {
        // IPv6 forms, bracketed or bare. The shape pattern above already
        // excludes them; this is an explicit guard for defence in depth.
        if (host.startsWith("[") || ':' in host) return true
        return parseIpv4Octets(host) != null
    }

    /**
     * Dotted-quad IPv4 parse accepting the decimal, 0x-hex and 0-octal
     * part forms URL parsers understand; returns the four octets or null
     * when [host] is not an IPv4 literal. Pure string work — no DNS.
     */
    private fun parseIpv4Octets(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 10) return null
            val value = when {
                part.startsWith("0x") || part.startsWith("0X") ->
                    part.substring(2).toIntOrNull(16)
                part.length > 1 && part.startsWith("0") ->
                    part.toIntOrNull(8)
                else -> part.toIntOrNull()
            } ?: return null
            if (value !in 0..255) return null
            octets[index] = value
        }
        return octets
    }
}

/**
 * String content of a JSON element, or null when the element is not a
 * primitive. Safe replacement for `element.jsonPrimitive`, which throws
 * when the element is an object or array: one malformed field in a
 * backend response must not break the whole call.
 */
private fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.contentOrNull

/** Int value of a JSON element, or null when it is not a numeric primitive. */
private fun JsonElement?.intValueOrNull(): Int? =
    (this as? JsonPrimitive)?.intOrNull

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
            // One malformed entry is skipped, never fatal to the list.
            runCatching { parseEntry(element) }.getOrNull()
        }
    }

    private fun parseEntry(element: JsonElement): VeePNServer? {
        val obj = element as? JsonObject ?: return null
        val addresses = (obj["addresses"] as? JsonArray)
            ?.mapNotNull { it.stringOrNull()?.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val port = obj["port"].intValueOrNull() ?: 0
        if (addresses.isEmpty() || port !in 1..65535) return null
        return VeePNServer(
            addresses = addresses,
            port = port,
            username = obj["username"].stringOrNull().orEmpty(),
            password = obj["password"].stringOrNull().orEmpty(),
        )
    }
}

/** Result of the locations endpoint: every location plus the free subset. */
data class LocationsResult(
    val all: List<VeePNLocation>,
    val free: List<VeePNLocation>,
)

/**
 * Client for the VeePN free-extension backend, a behaviour
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
    private val baseClient: OkHttpClient = defaultClient(),
    private val transport: () -> ApiTransportOptions = { ApiTransportOptions.NONE },
) {

    @Volatile
    private var cachedClient: OkHttpClient? = null

    @Volatile
    private var cachedClientFingerprint: Int = 0

    /** The current transport-options snapshot (fresh from the settings store). */
    fun currentTransportOptions(): ApiTransportOptions = transport()

    /**
     * The HTTP client shaped by the current transport options
     * (upstream SOCKS5 proxy, bootstrap DoH, TLS fragmentation).
     * Rebuilt only when the options actually change.
     */
    private fun effectiveClient(): OkHttpClient {
        val options = transport()
        val fingerprint = options.hashCode()
        cachedClient?.let { if (cachedClientFingerprint == fingerprint) return it }
        val built = baseClient.newBuilder().apply {
            options.upstreamProxy?.let { proxyConfig ->
                proxy(
                    Proxy(
                        Proxy.Type.SOCKS,
                        InetSocketAddress(proxyConfig.host, proxyConfig.port),
                    ),
                )
                if (proxyConfig.username != null) {
                    proxyAuthenticator { _, response ->
                        response.request.newBuilder()
                            .header(
                                "Proxy-Authorization",
                                Credentials.basic(
                                    proxyConfig.username,
                                    proxyConfig.secret ?: "",
                                ),
                            )
                            .build()
                    }
                }
            }
            options.bootstrapDohUrl?.let { dns(BootstrapDohDns(it)) }
            if (options.tlsFragmentation) {
                socketFactory(FragmentingSocketFactory())
            }
        }.build()
        cachedClient = built
        cachedClientFingerprint = fingerprint
        return built
    }

    @Volatile
    private var domainsCache: List<String>? = null

    @Volatile
    private var domainsCacheAtMs: Long = 0L

    @Volatile
    private var domainsCacheTtlMs: Long = 0L

    // ------------------------------------------------------------ token

    /** Guest access token, cached with a 7-day soft TTL. */
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
                http(domain + LAUNCH_PATH, "POST", body, authToken = null, effectiveClient())
            } catch (e: IOException) {
                last = "$domain: ${e.message}"
                delay(minOf(backoffSeconds(attempt), 5) * 1000L)
                continue
            }
            if (response.code == 200) {
                val access = parseJsonObject(response.text)?.get("access")
                    .stringOrNull()?.takeIf { it.isNotBlank() }
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

    /** All locations plus the free subset (proxyType == 0). */
    suspend fun locations(): LocationsResult = withContext(Dispatchers.IO) {
        val text = request(LOCATIONS_PATH, "GET", null, auth = true)
        val root = parseJsonObject(text)
        val array = root?.get("locations") as? JsonArray ?: return@withContext LocationsResult(emptyList(), emptyList())
        val all = array.mapNotNull { element ->
            // One malformed entry is skipped, never fatal to the list.
            runCatching {
                val obj = element as? JsonObject ?: return@mapNotNull null
                val region = obj["region"].stringOrNull()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                VeePNLocation(
                    region = region,
                    name = obj["name"].stringOrNull().orEmpty(),
                    countryCode = obj["countryCode"].stringOrNull().orEmpty(),
                    proxyType = obj["proxyType"].intValueOrNull() ?: -1,
                )
            }.getOrNull()
        }
        LocationsResult(all = all, free = all.filter { it.proxyType == 0 })
    }

    /** The backend's suggested optimal location, if it returns a region. */
    suspend fun optimalLocation(): VeePNLocation? = withContext(Dispatchers.IO) {
        val text = request(OPTIMAL_PATH, "GET", null, auth = true)
        val obj = parseJsonObject(text) ?: return@withContext null
        val region = obj["region"].stringOrNull()?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        VeePNLocation(
            region = region,
            name = obj["name"].stringOrNull().orEmpty(),
            countryCode = obj["countryCode"].stringOrNull().orEmpty(),
            proxyType = obj["proxyType"].intValueOrNull() ?: 0,
        )
    }

    // ------------------------------------------------------------ server list

    /**
     * Usable servers for [region], cached 12h per region. A fresh
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

    // ------------------------------------------------------------ internals

    /**
     * Authed request with domain rotation: the first 401/403 invalidates
     * the token and forces a fresh one for the single next attempt; a
     * second rejection only records the error and rotation continues.
     * 429/5xx backs off and rotates; other 4xx surfaces as [ApiError];
     * network errors rotate. Bounded by [MAX_DOMAIN_ATTEMPTS] and the
     * 120s wall budget.
     */
    private suspend fun request(path: String, method: String, body: String?, auth: Boolean): String {
        val domains = getDomains()
        val deadline = System.nanoTime() + REQUEST_BUDGET_MS * 1_000_000L
        var lastError = "unknown"
        var tokenRefreshed = false
        var forceFreshToken = false
        val attempts = minOf(domains.size, MAX_DOMAIN_ATTEMPTS)
        for (attempt in 0 until attempts) {
            if (attempt > 0 && System.nanoTime() > deadline) {
                lastError += " (request budget exhausted)"
                break
            }
            val token = if (auth) ensureToken(force = forceFreshToken) else null
            // Consume-once: the forced refresh covers only the attempt
            // immediately after the rejection that requested it.
            forceFreshToken = false
            val domain = domains[attempt % domains.size]
            val response = try {
                http(domain + path, method, body, token, effectiveClient())
            } catch (e: IOException) {
                lastError = "$domain unreachable (${e.message})"
                delay(minOf(backoffSeconds(attempt), 3) * 1000L)
                continue
            }
            when {
                response.code in 200..299 -> return response.text
                auth && (response.code == 401 || response.code == 403) -> {
                    lastError = "HTTP ${response.code} from $domain (token rejected)"
                    if (!tokenRefreshed) {
                        tokenRefreshed = true
                        forceFreshToken = true
                        store.clearGuestToken()
                    }
                    // A second rejection (the fresh token was already
                    // tried) does not abort the rotation: the error is
                    // recorded above and the loop moves to the next domain.
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
     * Default domains + validated reserve domains. The persistent reserve
     * cache lasts 24h; an empty fetch is never cached there and a stale
     * cache is reused when the fetch fails. The in-memory cache carries a
     * timestamp: a full result lives 24h, but a defaults-only result (the
     * reserve fetch failed or returned empty) expires after 5 minutes so
     * a later call retries the fetch instead of pinning the short list
     * for the whole process lifetime.
     */
    private suspend fun getDomains(): List<String> {
        val cachedDomains = domainsCache
        if (cachedDomains != null &&
            System.currentTimeMillis() - domainsCacheAtMs < domainsCacheTtlMs
        ) {
            return cachedDomains
        }
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
        val reserveIncluded = extra.isNotEmpty()
        for (candidate in extra) {
            val clean = candidate.trim().trimEnd('/')
            if (ApiDomainValidator.isValid(clean) && clean !in domains) domains.add(clean)
        }
        val result = domains.toList()
        domainsCache = result
        domainsCacheAtMs = System.currentTimeMillis()
        domainsCacheTtlMs = if (reserveIncluded) {
            RESERVE_CACHE_TTL_MS
        } else {
            DOMAINS_DEFAULTS_ONLY_TTL_MS
        }
        return result
    }

    private fun fetchReserveDomains(): List<String> {
        val bucketClient = effectiveClient().newBuilder()
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
                    val url = element.stringOrNull()
                    if (url != null && ApiDomainValidator.isValid(url)) found.add(url.trim().trimEnd('/'))
                }
                when (val free = root["free"]) {
                    is JsonPrimitive ->
                        free.contentOrNull?.takeIf { ApiDomainValidator.isValid(it) }
                            ?.let { found.add(it.trim().trimEnd('/')) }
                    is JsonArray -> free.forEach { element ->
                        val url = element.stringOrNull()
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
            append("\"platformVersion\":").append(jsonString(UserAgent.VALUE)).append(',')
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
            .header("User-Agent", UserAgent.VALUE)
        if (authToken != null) builder.header("Authorization", "Bearer $authToken")
        if (method == "POST") {
            builder.post((body ?: "{}").toRequestBody(JSON_MEDIA))
        }
        return httpClient.newCall(builder.build()).execute().use { response ->
            HttpResult(response.code, readBodyCapped(response.body?.byteStream()))
        }
    }

    /**
     * Read a response body with a hard size cap: the API domains are not
     * fully trusted (reserve domains come from public buckets), so an
     * unbounded body must never be loaded into memory.
     */
    private fun readBodyCapped(stream: InputStream?): String {
        if (stream == null) return ""
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > MAX_RESPONSE_BODY_BYTES) {
                    throw IOException("Response body exceeds $MAX_RESPONSE_BODY_BYTES bytes")
                }
                out.write(buffer, 0, read)
            }
        }
        return out.toString(Charsets.UTF_8.name())
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
        const val DOMAINS_DEFAULTS_ONLY_TTL_MS = 5L * 60 * 1000
        const val MAX_RESPONSE_BODY_BYTES = 1024 * 1024
        val BACKOFF_SECONDS = listOf(1L, 2L, 4L, 8L, 10L)

        private const val LAUNCH_PATH = "/v3/launch/"
        private const val LOCATIONS_PATH = "/v3/location/extension/"
        private const val OPTIMAL_PATH = "/v3/location/optimal/"
        private const val SERVER_LIST_PATH = "/v3/server/list/"

        private val JSON_MEDIA = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}

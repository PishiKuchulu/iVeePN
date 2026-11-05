package com.pishi.iveepn.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class GhAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
    /** "sha256:<hex>" as reported by the GitHub API for release assets. */
    val digest: String? = null,
)

@Serializable
data class GhRelease(
    @SerialName("tag_name") val tagName: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

data class UpdateInfo(
    /** Version without the leading "v" (e.g. "1.0.11"). */
    val version: String,
    val tag: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    /** Expected SHA-256 of the asset (lowercase hex), when the API reports one. */
    val sha256: String? = null,
)

/**
 * Finds app updates in this repository's GitHub releases. Releases are
 * combined (Windows exe + Android APK under one tag), so a release only
 * counts when it actually carries an APK asset.
 */
object AppUpdateChecker {

    private const val RELEASES_URL =
        "https://api.github.com/repos/PishiKuchulu/iVeePN/releases?per_page=30"
    private const val USER_AGENT = "iVeePN-Android-Updater"
    private val VERSION_TAG = Regex("""^v\d+\.\d+\.\d+$""")
    private val json = Json { ignoreUnknownKeys = true }

    // Dedicated plain OkHttp clients for update traffic, kept separate
    // from the VPN control-plane HTTP stack.
    val apiClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    suspend fun findUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        apiClient.newCall(request).execute().use { response ->
            ensureSuccessful(response.code)
            val body = response.body?.string()
                ?: throw IOException("Empty response from GitHub")
            selectUpdate(json.decodeFromString<List<GhRelease>>(body), currentVersion)
        }
    }

    /**
     * A non-successful HTTP status is a FAILED check, never "no update".
     * Throwing here keeps the caller from reporting "You're up to date"
     * when GitHub could not actually be asked — most visibly the
     * unauthenticated rate limit (HTTP 403) that shared VPN egress IPs
     * routinely hit, which can hide a newer published release from
     * devices still running an older version. The status code travels in
     * the exception message so the failure dialog can show it.
     */
    internal fun ensureSuccessful(code: Int) {
        if (code !in 200..299) throw IOException("HTTP $code")
    }

    /**
     * Pure selection logic (unit-tested): among the strict `vX.Y.Z`
     * releases newer than [currentVersion] (drafts and prereleases
     * excluded), pick the highest version that carries the canonical
     * `iVeePN-<version>.apk` asset — the exact name the signed-release
     * workflow publishes (the tag carries the "v", the asset does not).
     * An arbitrary `.apk` asset of any other name no longer counts:
     * accepting one would let any APK attached to a release be offered
     * as an update. Old Windows-only tags have no such asset and are
     * skipped.
     */
    fun selectUpdate(releases: List<GhRelease>, currentVersion: String): UpdateInfo? =
        releases.asSequence()
            .filter { !it.draft && !it.prerelease && VERSION_TAG.matches(it.tagName) }
            .filter { VersionNumbers.isNewer(it.tagName, currentVersion) }
            .sortedWith { a, b -> VersionNumbers.compare(b.tagName, a.tagName) }
            .mapNotNull { release ->
                val version = release.tagName.removePrefix("v")
                val asset = release.assets.firstOrNull { it.name == "iVeePN-$version.apk" }
                asset?.let {
                    UpdateInfo(
                        version,
                        release.tagName,
                        it.name,
                        it.downloadUrl,
                        it.size,
                        sha256 = it.digest
                            ?.removePrefix("sha256:")
                            ?.lowercase()
                            ?.takeIf { d -> d.length == 64 && d.all { c -> c.isDigit() || c in 'a'..'f' } },
                    )
                }
            }
            .firstOrNull()
}

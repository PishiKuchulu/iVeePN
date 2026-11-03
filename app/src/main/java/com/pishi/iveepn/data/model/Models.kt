package com.pishi.iveepn.data.model

import kotlinx.serialization.Serializable

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * One entry of GET /v3/location/extension/ (F-02). Field names mirror the
 * VeePN API: `region` is the code passed to the server-list endpoint,
 * `name` is the city name, `proxyType == 0` marks a free location.
 */
data class VeePNLocation(
    val region: String,
    val name: String,
    val countryCode: String,
    val proxyType: Int,
) {
    val isFree: Boolean get() = proxyType == 0

    /** Human-readable country name derived from the ISO code, if known. */
    val countryName: String
        get() = runCatching {
            java.util.Locale("", countryCode.uppercase()).displayCountry
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: countryCode.uppercase()
}

/**
 * One usable upstream HTTPS-proxy server from POST /v3/server/list/ (F-03),
 * with its guest credentials. Never log [password].
 */
@Serializable
data class VeePNServer(
    val addresses: List<String>,
    val port: Int,
    val username: String,
    val password: String,
)

fun countryCodeToEmojiFlag(countryCode: String): String {
    if (countryCode.equals("REC", ignoreCase = true) || countryCode.length != 2) return ""
    val upper = countryCode.uppercase()
    val first = upper[0]
    val second = upper[1]
    if (first !in 'A'..'Z' || second !in 'A'..'Z') return ""

    val firstCodePoint = 0x1F1E6 + (first - 'A')
    val secondCodePoint = 0x1F1E6 + (second - 'A')

    return String(Character.toChars(firstCodePoint)) + String(Character.toChars(secondCodePoint))
}

val VeePNLocation.emojiFlag: String get() = countryCodeToEmojiFlag(countryCode)

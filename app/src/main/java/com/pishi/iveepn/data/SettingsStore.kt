package com.pishi.iveepn.data

import android.content.Context
import com.pishi.iveepn.ui.theme.ThemeMode
import com.pishi.iveepn.vpn.IVeePNVpnService
import com.pishi.iveepn.widget.QuickConnectWidgetProvider

class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("iveepn_settings", Context.MODE_PRIVATE)

    // Same corruption recovery as the guest token store (fail-closed:
    // session-only in-memory store, never plaintext on disk).
    private val credentialsPrefs by lazy {
        createEncryptedPrefsWithRecovery(appContext, "iveepn_settings_credentials")
    }

    var themeMode: ThemeMode
        get() = ThemeMode.entries.firstOrNull {
            it.name == prefs.getString(KEY_THEME_MODE, null)
        } ?: ThemeMode.SYSTEM
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value.name).apply()
            QuickConnectWidgetProvider.updateAll(appContext, IVeePNVpnService.state.value)
        }

    /**
     * In-app language choice; null means the user has never chosen (the
     * resolved default — Persian, or English on English-locale devices — is
     * persisted as an explicit choice by MainActivity on first launch).
     */
    var appLanguage: AppLanguage?
        get() = prefs.getString(KEY_APP_LANGUAGE, null)?.let { raw ->
            AppLanguage.entries.firstOrNull { it.name == raw }
        }
        set(value) {
            if (value == null) {
                prefs.edit().remove(KEY_APP_LANGUAGE).apply()
            } else {
                prefs.edit().putString(KEY_APP_LANGUAGE, value.name).apply()
            }
        }

    var exitCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_EXIT_CHECK, true)
        set(value) { prefs.edit().putBoolean(KEY_EXIT_CHECK, value).apply() }

    var mtu: Int
        get() = prefs.getInt(KEY_CUSTOM_MTU, DEFAULT_MTU)
        set(value) { prefs.edit().putInt(KEY_CUSTOM_MTU, value.coerceIn(MIN_MTU, MAX_MTU)).apply() }

    /** Region code of the location chosen on the Locations screen (F-02/F-03). */
    var lastRegion: String
        get() = prefs.getString(KEY_LAST_REGION, DEFAULT_REGION) ?: DEFAULT_REGION
        set(value) {
            val clean = value.trim().lowercase()
            if (clean.isNotEmpty()) prefs.edit().putString(KEY_LAST_REGION, clean).apply()
        }

    /**
     * F-12: when the TUN interface cannot be established, fall back loudly to
     * proxy-only mode (default) instead of dropping the session entirely.
     * The fallback is always surfaced in the UI/logs, never silent.
     */
    var tunFallbackToProxy: Boolean
        get() = prefs.getBoolean(KEY_TUN_FALLBACK_TO_PROXY, true)
        set(value) { prefs.edit().putBoolean(KEY_TUN_FALLBACK_TO_PROXY, value).apply() }

    // The device's real (pre-VPN) IP and country are sensitive location data;
    // they live in the encrypted store. Reads still see a legacy plaintext
    // value once, writes migrate it out of the plaintext store.
    var cachedRealIp: String?
        get() = credentialsPrefs.getString(KEY_CACHED_REAL_IP, null)
            ?: prefs.getString(KEY_CACHED_REAL_IP, null)
        set(value) {
            credentialsPrefs.edit().putString(KEY_CACHED_REAL_IP, value).apply()
            if (prefs.contains(KEY_CACHED_REAL_IP)) prefs.edit().remove(KEY_CACHED_REAL_IP).apply()
        }

    var cachedRealCountry: String?
        get() = credentialsPrefs.getString(KEY_CACHED_REAL_COUNTRY, null)
            ?: prefs.getString(KEY_CACHED_REAL_COUNTRY, null)
        set(value) {
            credentialsPrefs.edit().putString(KEY_CACHED_REAL_COUNTRY, value).apply()
            if (prefs.contains(KEY_CACHED_REAL_COUNTRY)) prefs.edit().remove(KEY_CACHED_REAL_COUNTRY).apply()
        }

    fun clearCachedNetworkInfo() {
        prefs.edit()
            .remove(KEY_CACHED_REAL_IP)
            .remove(KEY_CACHED_REAL_COUNTRY)
            .apply()
        credentialsPrefs.edit()
            .remove(KEY_CACHED_REAL_IP)
            .remove(KEY_CACHED_REAL_COUNTRY)
            .apply()
    }

    var socksBindAddress: String
        get() = prefs.getString(KEY_SOCKS_BIND_ADDRESS, DEFAULT_SOCKS_BIND_ADDRESS) ?: DEFAULT_SOCKS_BIND_ADDRESS
        set(value) {
            prefs.edit().putString(KEY_SOCKS_BIND_ADDRESS, value.ifBlank { DEFAULT_SOCKS_BIND_ADDRESS }).apply()
        }

    var socksPort: Int
        get() = prefs.getInt(KEY_SOCKS_PORT, DEFAULT_SOCKS_PORT)
        set(value) { prefs.edit().putInt(KEY_SOCKS_PORT, value.coerceIn(1, 65_535)).apply() }

    var httpProxyEnabled: Boolean
        get() = prefs.getBoolean(KEY_HTTP_PROXY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_HTTP_PROXY_ENABLED, value).apply() }

    var httpProxyPort: Int
        get() = prefs.getInt(KEY_HTTP_PROXY_PORT, DEFAULT_HTTP_PROXY_PORT)
        set(value) { prefs.edit().putInt(KEY_HTTP_PROXY_PORT, value.coerceIn(1, 65_535)).apply() }

    var dnsProvider: DnsProvider
        get() = DnsProvider.entries.firstOrNull {
            it.name == prefs.getString(KEY_DNS_PROVIDER, null)
        } ?: DnsProvider.SYSTEM_DEFAULT
        set(value) { prefs.edit().putString(KEY_DNS_PROVIDER, value.name).apply() }

    var customDohUrl: String
        get() = prefs.getString(KEY_CUSTOM_DOH_URL, "") ?: ""
        set(value) {
            val trimmed = value.trim()
            if (trimmed.isNotEmpty() && !isValidDohUrl(trimmed)) return
            prefs.edit().putString(KEY_CUSTOM_DOH_URL, trimmed).apply()
        }

    val effectiveDohUrl: String?
        get() = when (dnsProvider) {
            DnsProvider.SYSTEM_DEFAULT -> null
            DnsProvider.CUSTOM -> customDohUrl.takeIf { isValidDohUrl(it) }
            else -> dnsProvider.dohUrl
        }

    var proxyOnlyMode: Boolean
        get() = prefs.getBoolean(KEY_PROXY_ONLY_MODE, false)
        set(value) { prefs.edit().putBoolean(KEY_PROXY_ONLY_MODE, value).apply() }

    var excludedApps: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED_APPS, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_EXCLUDED_APPS, HashSet(value)).apply()
        }

    enum class DnsProvider(
        val label: String,
        val dohUrl: String?,
    ) {
        SYSTEM_DEFAULT(
            label = "Default",
            dohUrl = null,
        ),
        CLOUDFLARE(
            label = "Cloudflare",
            dohUrl = "https://1.1.1.1/dns-query",
        ),
        GOOGLE(
            label = "Google",
            dohUrl = "https://8.8.8.8/dns-query",
        ),
        QUAD9(
            label = "Quad9",
            dohUrl = "https://9.9.9.9/dns-query",
        ),
        ADGUARD(
            label = "AdGuard",
            dohUrl = "https://dns.adguard-dns.com/dns-query",
        ),
        CUSTOM(
            label = "Custom DoH",
            dohUrl = null,
        ),
        ;
    }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_EXIT_CHECK = "exit_check_enabled"
        private const val KEY_CUSTOM_MTU = "custom_mtu"
        private const val KEY_LAST_REGION = "last_region"
        private const val KEY_TUN_FALLBACK_TO_PROXY = "tun_fallback_to_proxy"
        private const val KEY_CACHED_REAL_IP = "cached_real_ip"
        private const val KEY_CACHED_REAL_COUNTRY = "cached_real_country"
        const val DEFAULT_MTU = 1400
        const val MIN_MTU = 576
        const val MAX_MTU = 9000
        const val DEFAULT_REGION = "nl"
        private const val KEY_SOCKS_BIND_ADDRESS = "socks_bind_address"
        private const val KEY_SOCKS_PORT = "socks_port"
        private const val KEY_HTTP_PROXY_ENABLED = "http_proxy_enabled"
        private const val KEY_HTTP_PROXY_PORT = "http_proxy_port"
        private const val KEY_DNS_PROVIDER = "dns_provider"
        private const val KEY_CUSTOM_DOH_URL = "custom_doh_url"
        private const val KEY_PROXY_ONLY_MODE = "proxy_only_mode"
        private const val KEY_EXCLUDED_APPS = "excluded_apps"
        const val DEFAULT_SOCKS_BIND_ADDRESS = "127.0.0.1"
        const val DEFAULT_SOCKS_PORT = 1080
        const val DEFAULT_HTTP_PROXY_PORT = 8080

        fun isValidDohUrl(value: String): Boolean {
            val trimmed = value.trim()
            if (!trimmed.startsWith("https://", ignoreCase = true)) return false
            return runCatching {
                val uri = java.net.URI.create(trimmed)
                !uri.host.isNullOrBlank()
            }.getOrDefault(false)
        }

        /** True when a bind address only listens on this device (safe default). */
        fun isLoopbackBind(value: String): Boolean {
            val v = value.trim().lowercase()
            return v == "localhost" || v == "::1" || v.startsWith("127.")
        }

        fun isValidIpAddress(value: String): Boolean {
            return if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.net.InetAddresses.isNumericAddress(value)
            } else {
                @Suppress("DEPRECATION")
                android.util.Patterns.IP_ADDRESS.matcher(value).matches()
            }
        }

        fun isValidHostname(value: String): Boolean {
            val host = value.trim().removeSuffix(".")
            if (host.isEmpty() || host.length > 253) return false

            if (host.contains(':')) return false
            if (host.all { it.isDigit() || it == '.' }) return false
            val labels = host.split('.')
            if (labels.size < 2) return false
            return labels.all { label ->
                label.isNotEmpty() &&
                    label.length <= 63 &&
                    !label.startsWith('-') &&
                    !label.endsWith('-') &&
                    label.all { char ->
                        char == '-' || (char.code < 128 && (char.isLetterOrDigit()))
                    }
            }
        }
    }
}

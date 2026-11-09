package com.pishi.iveepn.data

import android.content.Context
import android.content.SharedPreferences
import com.pishi.iveepn.ui.theme.ThemeMode

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
            onThemeModeChanged?.invoke()
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

    /** Region code of the location chosen on the Locations screen. */
    var lastRegion: String
        get() = prefs.getString(KEY_LAST_REGION, DEFAULT_REGION) ?: DEFAULT_REGION
        set(value) {
            val clean = value.trim().lowercase()
            if (clean.isNotEmpty()) prefs.edit().putString(KEY_LAST_REGION, clean).apply()
        }

    /**
     * When the TUN interface cannot be established, fall back loudly to
     * proxy-only mode instead of dropping the session entirely. The
     * fallback is always surfaced in the UI/logs, never silent — and it
     * is opt-in: the default is OFF, so a TUN failure (including the
     * fail-closed IPv6 route refusal) fails the connect rather than
     * silently leaving the device's traffic unprotected.
     */
    var tunFallbackToProxy: Boolean
        get() = prefs.getBoolean(KEY_TUN_FALLBACK_TO_PROXY, false)
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
            // Store-level guard (the UI validates too, but the store is
            // the last line of defence): only a blank value (reset to the
            // loopback default) or a strict numeric IP literal is
            // accepted. Anything else — hostnames, malformed input — is
            // rejected and the previous value kept, so a bad write can
            // never expose the unauthenticated local proxies to the LAN
            // or break the bind outright.
            val trimmed = value.trim()
            when {
                trimmed.isBlank() ->
                    prefs.edit().putString(KEY_SOCKS_BIND_ADDRESS, DEFAULT_SOCKS_BIND_ADDRESS).apply()
                isValidIpAddress(trimmed) ->
                    prefs.edit().putString(KEY_SOCKS_BIND_ADDRESS, trimmed).apply()
                else -> AppLogger.w(
                    TAG,
                    "ignoring an invalid SOCKS bind address (not a numeric IP literal): $value",
                )
            }
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
            if (trimmed.isNotEmpty() && !isValidDohUrl(trimmed)) {
                AppLogger.w(TAG, "ignoring an invalid custom DoH URL: $trimmed")
                return
            }
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

    /**
     * Observe proxy-only mode changes. The VPN service registers this so a
     * mid-session toggle is applied by reconnecting, instead of silently
     * waiting for the next manual connection. SharedPreferences keeps only
     * a weak reference to listeners: the caller must retain the returned
     * listener and pass it back to [removeProxyOnlyModeObserver].
     */
    fun addProxyOnlyModeObserver(
        observer: (Boolean) -> Unit,
    ): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_PROXY_ONLY_MODE) observer(proxyOnlyMode)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun removeProxyOnlyModeObserver(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    var excludedApps: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED_APPS, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_EXCLUDED_APPS, HashSet(value)).apply()
        }

    /** Apps routed through the tunnel when the split mode is ONLY_SELECTED. */
    var includedApps: Set<String>
        get() = prefs.getStringSet(KEY_INCLUDED_APPS, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_INCLUDED_APPS, HashSet(value)).apply()
        }

    // ------------------------------------------------------- transport

    /** Route connections to VeePN servers (and the API) via an upstream SOCKS5 proxy. */
    var upstreamSocksEnabled: Boolean
        get() = prefs.getBoolean(KEY_UPSTREAM_SOCKS_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_UPSTREAM_SOCKS_ENABLED, value).apply() }

    var upstreamSocksHost: String
        get() = prefs.getString(KEY_UPSTREAM_SOCKS_HOST, "") ?: ""
        set(value) { prefs.edit().putString(KEY_UPSTREAM_SOCKS_HOST, value.trim()).apply() }

    var upstreamSocksPort: Int
        get() = prefs.getInt(KEY_UPSTREAM_SOCKS_PORT, 1080)
        set(value) { prefs.edit().putInt(KEY_UPSTREAM_SOCKS_PORT, value).apply() }

    /** Upstream proxy credentials live in the encrypted store, never in plain prefs. */
    var upstreamSocksUsername: String
        get() = credentialsPrefs.getString(KEY_UPSTREAM_SOCKS_USERNAME, "") ?: ""
        set(value) { credentialsPrefs.edit().putString(KEY_UPSTREAM_SOCKS_USERNAME, value).apply() }

    var upstreamSocksPassword: String
        get() = credentialsPrefs.getString(KEY_UPSTREAM_SOCKS_PASSWORD, "") ?: ""
        set(value) { credentialsPrefs.edit().putString(KEY_UPSTREAM_SOCKS_PASSWORD, value).apply() }

    /** Resolve VeePN API domains through the configured DoH provider instead of system DNS. */
    var bootstrapDohEnabled: Boolean
        get() = prefs.getBoolean(KEY_BOOTSTRAP_DOH_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_BOOTSTRAP_DOH_ENABLED, value).apply() }

    /** Experimental: segment the TLS ClientHello of VeePN-bound connections. */
    var tlsFragmentationEnabled: Boolean
        get() = prefs.getBoolean(KEY_TLS_FRAGMENTATION_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_TLS_FRAGMENTATION_ENABLED, value).apply() }

    var splitTunnelMode: SplitTunnelMode
        get() = SplitTunnelMode.fromStored(prefs.getString(KEY_SPLIT_TUNNEL_MODE, null))
        set(value) {
            prefs.edit().putString(KEY_SPLIT_TUNNEL_MODE, value.name).apply()
        }

    /**
     * How per-app split tunneling routes traffic. The default preserves
     * the historical behaviour of the excluded-apps list.
     */
    enum class SplitTunnelMode {
        /** Every app uses the tunnel (the app itself always bypasses). */
        ALL,

        /** Every app uses the tunnel except [SettingsStore.excludedApps]. */
        EXCLUDE_SELECTED,

        /** Only [SettingsStore.includedApps] use the tunnel. */
        ONLY_SELECTED,
        ;

        companion object {
            fun fromStored(value: String?): SplitTunnelMode =
                entries.firstOrNull { it.name == value } ?: EXCLUDE_SELECTED
        }
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
        private const val TAG = "SettingsStore"

        /**
         * Hook invoked after the theme mode changes. Registered by the
         * Application (which refreshes the home-screen widget) so this
         * data-layer class does not depend on the vpn/widget layers.
         */
        @Volatile
        var onThemeModeChanged: (() -> Unit)? = null

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
        const val MAX_MTU = 1500
        const val DEFAULT_REGION = "nl"
        private const val KEY_SOCKS_BIND_ADDRESS = "socks_bind_address"
        private const val KEY_SOCKS_PORT = "socks_port"
        private const val KEY_HTTP_PROXY_ENABLED = "http_proxy_enabled"
        private const val KEY_HTTP_PROXY_PORT = "http_proxy_port"
        private const val KEY_DNS_PROVIDER = "dns_provider"
        private const val KEY_CUSTOM_DOH_URL = "custom_doh_url"
        private const val KEY_PROXY_ONLY_MODE = "proxy_only_mode"
        private const val KEY_EXCLUDED_APPS = "excluded_apps"
        private const val KEY_INCLUDED_APPS = "included_apps"
        private const val KEY_SPLIT_TUNNEL_MODE = "split_tunnel_mode"
        private const val KEY_UPSTREAM_SOCKS_ENABLED = "upstream_socks_enabled"
        private const val KEY_UPSTREAM_SOCKS_HOST = "upstream_socks_host"
        private const val KEY_UPSTREAM_SOCKS_PORT = "upstream_socks_port"
        private const val KEY_UPSTREAM_SOCKS_USERNAME = "upstream_socks_username"
        private const val KEY_UPSTREAM_SOCKS_PASSWORD = "upstream_socks_password"
        private const val KEY_BOOTSTRAP_DOH_ENABLED = "bootstrap_doh_enabled"
        private const val KEY_TLS_FRAGMENTATION_ENABLED = "tls_fragmentation_enabled"
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

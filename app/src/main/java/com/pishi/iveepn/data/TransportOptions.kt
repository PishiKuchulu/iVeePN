package com.pishi.iveepn.data

/**
 * Snapshot of the transport-shaping settings, read fresh from the
 * store whenever a client or session (re)builds, so toggles take
 * effect on the next connection without an app restart.
 */
data class ApiTransportOptions(
    val upstreamProxy: UpstreamProxyConfig?,
    val bootstrapDohUrl: String?,
    val tlsFragmentation: Boolean,
) {
    companion object {
        val NONE = ApiTransportOptions(null, null, false)

        fun fromStore(store: SettingsStore): ApiTransportOptions {
            val proxy = if (store.upstreamSocksEnabled && store.upstreamSocksHost.isNotBlank()) {
                UpstreamProxyConfig(
                    host = store.upstreamSocksHost,
                    port = store.upstreamSocksPort,
                    username = store.upstreamSocksUsername.takeIf { it.isNotEmpty() },
                    secret = store.upstreamSocksPassword.takeIf { it.isNotEmpty() },
                )
            } else {
                null
            }
            return ApiTransportOptions(
                upstreamProxy = proxy,
                bootstrapDohUrl = if (store.bootstrapDohEnabled) store.effectiveDohUrl else null,
                tlsFragmentation = store.tlsFragmentationEnabled,
            )
        }
    }
}

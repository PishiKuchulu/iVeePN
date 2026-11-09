package com.pishi.iveepn.data

/** A user-configured upstream SOCKS5 proxy that VeePN-bound traffic transits first. */
data class UpstreamProxyConfig(
    val host: String,
    val port: Int,
    val username: String?,
    val secret: String?,
)

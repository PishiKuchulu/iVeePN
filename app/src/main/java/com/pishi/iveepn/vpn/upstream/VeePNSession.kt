package com.pishi.iveepn.vpn.upstream

import com.pishi.iveepn.data.AppLogger
import com.pishi.iveepn.data.VeePNApi
import com.pishi.iveepn.data.model.VeePNServer
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * [UpstreamSession] backed by the guest-credential server pool: the API
 * hands out per-region proxy credentials, [ServerPool] rotates through
 * the servers and refreshes the credentials when the upstream rejects
 * them (401/403).
 */
class VeePNSession(
    private val api: VeePNApi,
    private val region: String,
) : UpstreamSession {

    @Volatile
    private var pool: ServerPool? = null

    @Volatile
    private var closed: Boolean = false

    override suspend fun connect() {
        api.ensureToken()
        val servers = api.serverList(region)
        pool = ServerPool(
            initialServers = servers,
            refreshServers = {
                api.ensureToken(force = true)
                api.serverList(region, forceRefresh = true)
            },
            onRefresh = {
                AppLogger.i(TAG, "Upstream credentials refreshed; server list replaced")
            },
        )
        closed = false
        AppLogger.i(TAG, "Session ready: ${servers.size} upstream servers for region $region")
    }

    override suspend fun openStream(targetHost: String, targetPort: Int): TunneledStream {
        if (closed) throw IOException("Session is closed")
        val activePool = pool ?: throw IOException("Session is not connected")
        val authority = "$targetHost:$targetPort"
        val socket = try {
            activePool.open(targetHost, targetPort)
        } catch (e: UpstreamError) {
            throw mapUpstreamError(e, authority)
        } catch (e: SocketTimeoutException) {
            throw UpstreamConnectTimeoutException(authority, "Upstream connect timed out: $authority", e)
        }
        return TunneledStream(
            input = socket.getInputStream(),
            output = socket.getOutputStream(),
            close = { runCatching { socket.close() } },
        )
    }

    override fun disableNewStreamsAndCloseWhenIdle() {
        closed = true
        AppLogger.i(TAG, "Session closing: no new streams accepted")
    }

    /**
     * No-op by design: this upstream has no bearer token at the tunnel
     * layer. Guest proxy credentials live in the server list and rotate
     * through the pool's credential refresh (401/403), never through a
     * token pushed into an established session.
     */
    override fun updateBearerToken(token: String) {
        // Intentionally unused; see class doc.
    }

    override val isConnected: Boolean
        get() = pool != null && !closed

    override fun close() {
        closed = true
        pool = null
    }

    /** Public exit IP through a fresh tunnel, or null when undetermined. */
    /** Servers currently in rotation (empty before connect); used for the connected-endpoint ping. */
    fun currentServers(): List<VeePNServer> = pool?.currentServers().orEmpty()

    suspend fun probeExitIp(): String? {
        val activePool = pool
        if (activePool == null || closed) return null
        val server = activePool.currentServers().firstOrNull() ?: return null
        return try {
            VeePNUpstream.checkExitIp(server)
        } catch (e: UpstreamError) {
            AppLogger.w(TAG, "Exit IP probe failed (${e.kind})")
            null
        }
    }

    private fun mapUpstreamError(error: UpstreamError, authority: String): IOException =
        when {
            error.kind == ConnectFailureKind.CREDENTIALS ->
                UpstreamConnectRejectedException(
                    statusCode = error.statusCode ?: 403,
                    authority = authority,
                    message = error.message ?: "Upstream rejected the credentials",
                )
            error.message?.contains("timeout", ignoreCase = true) == true ->
                UpstreamConnectTimeoutException(
                    authority = authority,
                    message = error.message ?: "Upstream connect timed out: $authority",
                )
            else -> error
        }

    private companion object {
        const val TAG = "VeePNSession"
    }
}

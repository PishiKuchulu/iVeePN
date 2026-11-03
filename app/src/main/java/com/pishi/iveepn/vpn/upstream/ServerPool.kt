package com.pishi.iveepn.vpn.upstream

import com.pishi.iveepn.data.model.VeePNServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Socket

/**
 * Cooldown for a server with [streak] consecutive failures:
 * 20s doubling per failure, capped at 600s (desktop circuit breaker).
 */
fun cooldownSecondsForStreak(streak: Int): Long {
    if (streak < 1) return 0L
    // 20 * 2^5 = 640 already exceeds the cap, so stop shifting there.
    if (streak - 1 >= 5) return 600L
    return minOf(20L * (1L shl (streak - 1)), 600L)
}

/**
 * Health-aware pool of upstream servers: a port of the desktop
 * TunnelFactory.
 *
 * - Round-robin start position, skipping servers in failure cooldown;
 *   when everything is cooling down, the earliest-expiring one is tried.
 * - At most [MAX_ATTEMPTS] servers are tried per [open].
 * - A failure grows the server's streak and (re)arms its cooldown.
 * - 401/403 (credentials) triggers a throttled refresh: [refreshServers]
 *   replaces the list, clears the breaker and fires [onRefresh].
 * - 429 earns a 0.5s breather before rotating to the next server.
 * - Upstream handshakes are capped at [MAX_PARALLEL] in flight so a
 *   burst of client connections cannot trip the upstream rate limit.
 */
class ServerPool(
    initialServers: List<VeePNServer>,
    private val refreshServers: suspend () -> List<VeePNServer>,
    private val opener: (VeePNServer, String, Int) -> Socket = { server, host, port ->
        VeePNUpstream.openTlsConnect(server, host, port)
    },
    private val onRefresh: () -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    @Volatile
    private var servers: List<VeePNServer> = initialServers.toList()

    private val stateLock = Any()
    private val cooldownUntilMs = HashMap<Int, Long>()
    private val failStreak = HashMap<Int, Int>()
    private var roundRobin = 0
    private var lastRefreshMs: Long? = null

    private val handshakeSemaphore = Semaphore(MAX_PARALLEL)

    /** Open a tunnel to the target through the healthiest server found. */
    suspend fun open(targetHost: String, targetPort: Int): Socket {
        var last: UpstreamError? = null
        repeat(MAX_ATTEMPTS) {
            val index = pickServerIndex() ?: return@repeat
            val server = synchronized(stateLock) { servers.getOrNull(index) } ?: return@repeat
            try {
                val socket = handshakeSemaphore.withPermit {
                    opener(server, targetHost, targetPort)
                }
                markOk(index)
                return socket
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpstreamError) {
                last = e
                markFail(index)
                when {
                    e.kind == ConnectFailureKind.CREDENTIALS ||
                        e.statusCode == 401 || e.statusCode == 403 ->
                        maybeRefreshCredentials()
                    e.kind == ConnectFailureKind.ROTATE || e.statusCode == 429 ->
                        delay(ROTATE_BREATHER_MS)
                }
            } catch (e: Exception) {
                last = UpstreamError(
                    ConnectFailureKind.GENERAL,
                    "upstream error: ${e.message ?: e.javaClass.simpleName}",
                )
                markFail(index)
            }
        }
        throw last ?: UpstreamError(
            ConnectFailureKind.GENERAL,
            "No upstream servers available",
        )
    }

    /** Replace the server list and reset the breaker (indices change). */
    fun replaceServers(servers: List<VeePNServer>) {
        synchronized(stateLock) {
            this.servers = servers.toList()
            cooldownUntilMs.clear()
            failStreak.clear()
            roundRobin = 0
        }
    }

    fun serverCount(): Int = servers.size

    /** Snapshot of the current list, in pool order. */
    fun currentServers(): List<VeePNServer> = servers

    /** First address of each server, distinct, in pool order. */
    fun upstreamIps(): List<String> =
        servers.mapNotNull { it.addresses.firstOrNull() }.distinct()

    // ------------------------------------------------------------ breaker

    private fun pickServerIndex(): Int? = synchronized(stateLock) {
        val count = servers.size
        if (count == 0) return@synchronized null
        val now = nowMs()
        // Pass 1: healthy servers in round-robin order.
        for (offset in 0 until count) {
            val index = (roundRobin + offset) % count
            if ((cooldownUntilMs[index] ?: 0L) <= now) {
                roundRobin = (index + 1) % count
                return@synchronized index
            }
        }
        // Pass 2: everything is cooling down; try the earliest expiry.
        cooldownUntilMs.entries.minByOrNull { it.value }?.key ?: 0
    }

    private fun markOk(index: Int) {
        synchronized(stateLock) {
            failStreak.remove(index)
            cooldownUntilMs.remove(index)
        }
    }

    private fun markFail(index: Int) {
        synchronized(stateLock) {
            val streak = (failStreak[index] ?: 0) + 1
            failStreak[index] = streak
            cooldownUntilMs[index] = nowMs() + cooldownSecondsForStreak(streak) * 1_000L
        }
    }

    private suspend fun maybeRefreshCredentials() {
        synchronized(stateLock) {
            val now = nowMs()
            val last = lastRefreshMs
            if (last != null && now - last < REFRESH_MIN_INTERVAL_MS) return
            lastRefreshMs = now
        }
        val fresh = try {
            refreshServers()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return // A failed refresh must not mask the original failure.
        }
        if (fresh.isNotEmpty()) {
            replaceServers(fresh)
            try {
                onRefresh()
            } catch (e: Exception) {
                // Listener failures never break tunnel opening.
            }
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val MAX_PARALLEL = 8
        const val REFRESH_MIN_INTERVAL_MS = 90_000L
        const val ROTATE_BREATHER_MS = 500L
    }
}

package com.pishi.iveepn.vpn

import com.pishi.iveepn.data.model.VeePNServer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Pre-connect latency state for one location (region), shown in the
 * locations list so the fastest free location can be picked BEFORE
 * connecting. Latency is the TCP connect time to an upstream server of
 * the region (see [PingUtil]); it is measured on the direct network —
 * this app is excluded from its own TUN interface — so the numbers are
 * comparable while disconnected and while connected alike.
 */
sealed interface LocationPingState {
    /** No measurement has run for this region yet. */
    data object NotTested : LocationPingState

    /** A measurement is in flight. */
    data object Testing : LocationPingState

    /** The fastest probed endpoint answered in [ms] milliseconds. */
    data class Reachable(val ms: Int) : LocationPingState

    /** The server list failed, was empty, or no endpoint answered. */
    data object Unreachable : LocationPingState
}

/** Coarse latency bucket used for the coloured dot in the list. */
enum class PingQuality { GOOD, FAIR, POOR }

fun pingQuality(ms: Int): PingQuality = when {
    ms < GOOD_MAX_MS -> PingQuality.GOOD
    ms < FAIR_MAX_MS -> PingQuality.FAIR
    else -> PingQuality.POOR
}

const val GOOD_MAX_MS = 150
const val FAIR_MAX_MS = 300

/** How many of a region's servers one ping round probes (best-of). */
const val MAX_SERVERS_TO_PROBE = 3

/**
 * Endpoints probed for a region: the first address of each of the
 * first [MAX_SERVERS_TO_PROBE] servers, duplicates removed. Pure —
 * unit-tested directly.
 */
fun probeEndpoints(servers: List<VeePNServer>): List<Pair<String, Int>> =
    servers.take(MAX_SERVERS_TO_PROBE)
        .mapNotNull { server ->
            server.addresses.firstOrNull()?.takeIf { it.isNotBlank() }
                ?.let { host -> host to server.port }
        }
        .distinct()

/**
 * Measures a region's pre-connect latency. The server list comes from
 * the existing per-region cache/API path, so a ping round costs at
 * most one cached (12 h) server-list fetch per region, plus up to
 * [MAX_SERVERS_TO_PROBE] short TCP connects (1.5 s timeout each, run
 * in parallel). Only the endpoint address and port are used — never
 * the server credentials.
 *
 * Both dependencies are injectable so the logic is unit-testable
 * without a network.
 */
class LocationPingProber(
    private val serverList: suspend (String) -> List<VeePNServer>,
    private val tcpPing: suspend (String, Int) -> Int? = { host, port ->
        PingUtil.measureTcpLatencyMs(host, port)
    },
) {
    suspend fun pingRegion(region: String): LocationPingState {
        val servers = runCatching { serverList(region) }.getOrNull()
            ?: return LocationPingState.Unreachable
        val endpoints = probeEndpoints(servers)
        if (endpoints.isEmpty()) return LocationPingState.Unreachable
        val results = coroutineScope {
            endpoints.map { (host, port) -> async { tcpPing(host, port) } }
                .map { deferred -> runCatching { deferred.await() }.getOrNull() }
        }
        val best = results.filterNotNull().minOrNull()
            ?: return LocationPingState.Unreachable
        return LocationPingState.Reachable(best)
    }
}

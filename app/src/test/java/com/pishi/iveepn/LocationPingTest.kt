package com.pishi.iveepn

import com.pishi.iveepn.data.model.VeePNServer
import com.pishi.iveepn.vpn.LocationPingProber
import com.pishi.iveepn.vpn.LocationPingState
import com.pishi.iveepn.vpn.PingQuality
import com.pishi.iveepn.vpn.pingQuality
import com.pishi.iveepn.vpn.probeEndpoints
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pre-connect ping for the locations list: endpoint selection is the
 * first address of the first three servers of a region, and a round's
 * result is the fastest answer among them. All network seams are
 * faked — no socket is opened here.
 */
class LocationPingTest {

    private fun server(vararg addresses: String, port: Int = 443): VeePNServer =
        VeePNServer(addresses.toList(), port, "user", "secret")

    @Test
    fun probeEndpointsTakeFirstAddressOfFirstThreeServers() {
        val servers = listOf(
            server("h1.example.com", "h1b.example.com"),
            server("h2.example.com"),
            server("h3.example.com", port = 8443),
            server("h4.example.com"),
        )
        assertEquals(
            listOf(
                "h1.example.com" to 443,
                "h2.example.com" to 443,
                "h3.example.com" to 8443,
            ),
            probeEndpoints(servers),
        )
    }

    @Test
    fun probeEndpointsSkipBlankAddressesAndDedupe() {
        val servers = listOf(
            server(""),
            server("h1.example.com"),
            server("h1.example.com"),
        )
        assertEquals(listOf("h1.example.com" to 443), probeEndpoints(servers))
    }

    @Test
    fun pingRegionReturnsFastestAnswer() = runBlocking {
        val latencies = mapOf("h1" to 210, "h2" to 86, "h3" to 140)
        val prober = LocationPingProber(
            serverList = { listOf(server("h1"), server("h2"), server("h3")) },
            tcpPing = { host, _ -> latencies[host] },
        )
        assertEquals(LocationPingState.Reachable(86), prober.pingRegion("fr-prs"))
    }

    @Test
    fun pingRegionIgnoresFailedEndpointsWhenOneAnswers() = runBlocking {
        val prober = LocationPingProber(
            serverList = { listOf(server("dead"), server("alive")) },
            tcpPing = { host, _ -> if (host == "alive") 120 else null },
        )
        assertEquals(LocationPingState.Reachable(120), prober.pingRegion("nl-ams"))
    }

    @Test
    fun pingRegionIsUnreachableWhenNothingAnswers() = runBlocking {
        val prober = LocationPingProber(
            serverList = { listOf(server("h1"), server("h2")) },
            tcpPing = { _, _ -> null },
        )
        assertEquals(LocationPingState.Unreachable, prober.pingRegion("gb-lnd"))
    }

    @Test
    fun pingRegionIsUnreachableWhenServerListFailsOrIsEmpty() = runBlocking {
        val failing = LocationPingProber(
            serverList = { throw IllegalStateException("api down") },
            tcpPing = { _, _ -> 10 },
        )
        assertEquals(LocationPingState.Unreachable, failing.pingRegion("us-or"))
        val empty = LocationPingProber(
            serverList = { emptyList() },
            tcpPing = { _, _ -> 10 },
        )
        assertEquals(LocationPingState.Unreachable, empty.pingRegion("us-or"))
    }

    @Test
    fun qualityBucketsFollowBoundaries() {
        assertEquals(PingQuality.GOOD, pingQuality(0))
        assertEquals(PingQuality.GOOD, pingQuality(149))
        assertEquals(PingQuality.FAIR, pingQuality(150))
        assertEquals(PingQuality.FAIR, pingQuality(299))
        assertEquals(PingQuality.POOR, pingQuality(300))
        assertTrue(pingQuality(10_000) == PingQuality.POOR)
    }
}

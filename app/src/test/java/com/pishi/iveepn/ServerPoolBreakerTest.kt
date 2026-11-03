package com.pishi.iveepn

import com.pishi.iveepn.data.model.VeePNServer
import com.pishi.iveepn.vpn.upstream.ConnectFailureKind
import com.pishi.iveepn.vpn.upstream.ServerPool
import com.pishi.iveepn.vpn.upstream.UpstreamError
import com.pishi.iveepn.vpn.upstream.cooldownSecondsForStreak
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

/** Circuit-breaker, rotation and credential-refresh behaviour of the pool. */
class ServerPoolBreakerTest {

    private class DummySocket : Socket() {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private fun server(firstAddress: String) = VeePNServer(
        addresses = listOf(firstAddress),
        port = 443,
        username = "user",
        password = "pass",
    )

    @Test
    fun cooldownDoublesFrom20AndCapsAt600() {
        assertEquals(20L, cooldownSecondsForStreak(1))
        assertEquals(40L, cooldownSecondsForStreak(2))
        assertEquals(80L, cooldownSecondsForStreak(3))
        assertEquals(160L, cooldownSecondsForStreak(4))
        assertEquals(320L, cooldownSecondsForStreak(5))
        assertEquals(600L, cooldownSecondsForStreak(6))
        assertEquals(600L, cooldownSecondsForStreak(7))
        assertEquals(600L, cooldownSecondsForStreak(20))
    }

    @Test
    fun openSkipsCooledDownServer() = runBlocking {
        val attempts = mutableListOf<String>()
        val pool = ServerPool(
            initialServers = listOf(server("10.0.0.1"), server("10.0.0.2")),
            refreshServers = { emptyList() },
            opener = { srv, _, _ ->
                attempts += srv.addresses.first()
                if (srv.addresses.first() == "10.0.0.1") {
                    throw UpstreamError(ConnectFailureKind.GENERAL, "boom")
                }
                DummySocket()
            },
        )
        // First open: 10.0.0.1 fails and cools down, 10.0.0.2 serves.
        val socket = pool.open("example.com", 443)
        assertTrue(socket is DummySocket)
        assertEquals(listOf("10.0.0.1", "10.0.0.2"), attempts)

        // Second open: 10.0.0.1 is still cooling down and is skipped outright.
        attempts.clear()
        pool.open("example.com", 443)
        assertEquals(listOf("10.0.0.2"), attempts)
        Unit
    }

    @Test
    fun openGivesUpAfterThreeAttempts() {
        val attempts = mutableListOf<String>()
        val pool = ServerPool(
            initialServers = listOf(server("10.0.0.1")),
            refreshServers = { emptyList() },
            opener = { srv, _, _ ->
                attempts += srv.addresses.first()
                throw UpstreamError(ConnectFailureKind.REFUSED, "nope", 503)
            },
        )
        assertThrows(UpstreamError::class.java) {
            runBlocking { pool.open("example.com", 443) }
        }
        assertEquals(3, attempts.size)
    }

    @Test
    fun openWithNoServersThrows() {
        val pool = ServerPool(
            initialServers = emptyList(),
            refreshServers = { emptyList() },
            opener = { _, _, _ -> DummySocket() },
        )
        assertThrows(UpstreamError::class.java) {
            runBlocking { pool.open("example.com", 443) }
        }
    }

    @Test
    fun rotateFailureRotatesWithoutRefresh() = runBlocking {
        var refreshCount = 0
        val attempts = mutableListOf<String>()
        val pool = ServerPool(
            initialServers = listOf(server("10.0.0.1"), server("10.0.0.2")),
            refreshServers = { refreshCount++; emptyList() },
            opener = { srv, _, _ ->
                attempts += srv.addresses.first()
                if (srv.addresses.first() == "10.0.0.1") {
                    throw UpstreamError(ConnectFailureKind.ROTATE, "slow down", 429)
                }
                DummySocket()
            },
        )
        val socket = pool.open("example.com", 443)
        assertTrue(socket is DummySocket)
        assertEquals(listOf("10.0.0.1", "10.0.0.2"), attempts)
        assertEquals(0, refreshCount)
        Unit
    }

    @Test
    fun credentialsFailureRefreshIsThrottledTo90Seconds() {
        var now = 1_000_000L
        var refreshCount = 0
        var onRefreshCount = 0
        val entry = server("10.0.0.1")
        val pool = ServerPool(
            initialServers = listOf(entry),
            refreshServers = { refreshCount++; listOf(entry) },
            opener = { _, _, _ ->
                throw UpstreamError(ConnectFailureKind.CREDENTIALS, "auth rejected", 403)
            },
            onRefresh = { onRefreshCount++ },
            nowMs = { now },
        )
        // One open burns 3 attempts, but only the first 401/403 may refresh.
        assertThrows(UpstreamError::class.java) {
            runBlocking { pool.open("example.com", 443) }
        }
        assertEquals(1, refreshCount)
        assertEquals(1, onRefreshCount)

        // Still inside the 90s window: no further refresh.
        assertThrows(UpstreamError::class.java) {
            runBlocking { pool.open("example.com", 443) }
        }
        assertEquals(1, refreshCount)

        // Past the window: the next credential failure refreshes again.
        now += 91_000L
        assertThrows(UpstreamError::class.java) {
            runBlocking { pool.open("example.com", 443) }
        }
        assertEquals(2, refreshCount)
        assertEquals(2, onRefreshCount)
    }

    @Test
    fun replaceServersResetsCountAndUpstreamIps() {
        val pool = ServerPool(
            initialServers = listOf(server("10.0.0.1"), server("10.0.0.2")),
            refreshServers = { emptyList() },
            opener = { _, _, _ -> DummySocket() },
        )
        assertEquals(2, pool.serverCount())
        assertEquals(listOf("10.0.0.1", "10.0.0.2"), pool.upstreamIps())

        pool.replaceServers(listOf(server("10.0.0.9"), server("10.0.0.9")))
        assertEquals(2, pool.serverCount())
        // Distinct: both servers share the same first address.
        assertEquals(listOf("10.0.0.9"), pool.upstreamIps())
    }
}

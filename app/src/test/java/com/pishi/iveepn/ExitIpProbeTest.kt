package com.pishi.iveepn

import com.pishi.iveepn.vpn.upstream.ConnectFailureKind
import com.pishi.iveepn.vpn.upstream.UpstreamError
import com.pishi.iveepn.vpn.upstream.VeePNUpstream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * Mirror-walk semantics of the exit-IP probe ([VeePNUpstream.firstExitIp]):
 * mirrors fall through on inner hiccups and empty answers, but a tunnel
 * failure aborts the walk immediately.
 */
class ExitIpProbeTest {

    @Test
    fun firstMirrorAnswerWinsAndSecondMirrorIsNotTried() {
        var calls = 0
        val ip = VeePNUpstream.firstExitIp { _, _ ->
            calls++
            "203.0.113.7"
        }
        assertEquals("203.0.113.7", ip)
        assertEquals(1, calls)
    }

    @Test
    fun emptyAnswerFallsThroughToTheMirror() {
        val seen = mutableListOf<String>()
        val ip = VeePNUpstream.firstExitIp { host, _ ->
            seen += host
            if (host == "api.ipify.org") null else "198.51.100.23"
        }
        assertEquals("198.51.100.23", ip)
        assertEquals(listOf("api.ipify.org", "ifconfig.me"), seen)
    }

    @Test
    fun innerHiccupFallsThroughToTheMirror() {
        var calls = 0
        val ip = VeePNUpstream.firstExitIp { _, _ ->
            calls++
            if (calls == 1) throw IOException("inner TLS hiccup") else "192.0.2.55"
        }
        assertEquals("192.0.2.55", ip)
        assertEquals(2, calls)
    }

    @Test
    fun tunnelFailureAbortsTheWalkImmediately() {
        var calls = 0
        val error = assertThrows(UpstreamError::class.java) {
            VeePNUpstream.firstExitIp { _, _ ->
                calls++
                throw UpstreamError(ConnectFailureKind.HOST_UNREACHABLE, "Cannot reach upstream proxy")
            }
        }
        assertEquals(ConnectFailureKind.HOST_UNREACHABLE, error.kind)
        assertEquals(1, calls)
    }

    @Test
    fun allMirrorsEmptyReturnsNull() {
        val ip = VeePNUpstream.firstExitIp { _, _ -> null }
        assertNull(ip)
    }
}

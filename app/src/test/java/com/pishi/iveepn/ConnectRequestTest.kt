package com.pishi.iveepn

import com.pishi.iveepn.data.model.VeePNServer
import com.pishi.iveepn.vpn.upstream.ConnectFailureKind
import com.pishi.iveepn.vpn.upstream.ConnectRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** CONNECT head construction and target formatting (desktop parity). */
class ConnectRequestTest {

    private val server = VeePNServer(
        addresses = listOf("proxy.example.com"),
        port = 443,
        username = "user",
        password = "pass",
    )

    @Test
    fun formatTargetPassesHostnameAndIpv4Through() {
        assertEquals("example.com:443", ConnectRequest.formatTarget("example.com", 443))
        assertEquals("192.0.2.1:8080", ConnectRequest.formatTarget("192.0.2.1", 8080))
    }

    @Test
    fun formatTargetBracketsBareIpv6() {
        assertEquals("[2001:db8::1]:443", ConnectRequest.formatTarget("2001:db8::1", 443))
        assertEquals("[::1]:80", ConnectRequest.formatTarget("::1", 80))
    }

    @Test
    fun formatTargetLeavesBracketedIpv6Alone() {
        assertEquals("[2001:db8::1]:443", ConnectRequest.formatTarget("[2001:db8::1]", 443))
    }

    @Test
    fun buildProducesExactDesktopRequestHead() {
        val request = ConnectRequest.build(server, "example.com", 443, "TestAgent/1.0")
        // base64("user:pass") = dXNlcjpwYXNz
        val expected = "CONNECT example.com:443 HTTP/1.1\r\n" +
            "Host: example.com:443\r\n" +
            "Proxy-Authorization: Basic dXNlcjpwYXNz\r\n" +
            "User-Agent: TestAgent/1.0\r\n" +
            "Proxy-Connection: keep-alive\r\n" +
            "\r\n"
        assertEquals(expected, request)
    }

    @Test
    fun buildBracketsIpv6TargetInLineAndHostHeader() {
        val request = ConnectRequest.build(server, "2001:db8::1", 443, "TestAgent/1.0")
        assertTrue(request.startsWith("CONNECT [2001:db8::1]:443 HTTP/1.1\r\n"))
        assertTrue(request.contains("Host: [2001:db8::1]:443\r\n"))
    }

    @Test
    fun buildRejectsUnsanitisedTargetHost() {
        assertThrows(IllegalArgumentException::class.java) {
            ConnectRequest.build(server, "example.com\r\nInjected: yes", 443, "TestAgent/1.0")
        }
    }

    @Test
    fun requireSanitisedHostAcceptsOrdinaryHosts() {
        ConnectRequest.requireSanitisedHost("example.com")
        ConnectRequest.requireSanitisedHost("192.0.2.1")
        ConnectRequest.requireSanitisedHost("2001:db8::1")
    }

    @Test
    fun requireSanitisedHostRejectsCrLfSpaceAndEmpty() {
        assertThrows(IllegalArgumentException::class.java) {
            ConnectRequest.requireSanitisedHost("exa mple.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectRequest.requireSanitisedHost("example.com\r\nInjected: yes")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectRequest.requireSanitisedHost("example\r.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectRequest.requireSanitisedHost("example\n.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectRequest.requireSanitisedHost("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectRequest.requireSanitisedHost("   ")
        }
    }

    @Test
    fun classifyStatusMapsDesktopBuckets() {
        assertEquals(ConnectFailureKind.CREDENTIALS, ConnectRequest.classifyStatus(401))
        assertEquals(ConnectFailureKind.CREDENTIALS, ConnectRequest.classifyStatus(403))
        assertEquals(ConnectFailureKind.ROTATE, ConnectRequest.classifyStatus(429))
        assertEquals(ConnectFailureKind.HOST_UNREACHABLE, ConnectRequest.classifyStatus(404))
        assertEquals(ConnectFailureKind.HOST_UNREACHABLE, ConnectRequest.classifyStatus(410))
        assertEquals(ConnectFailureKind.REFUSED, ConnectRequest.classifyStatus(500))
        assertEquals(ConnectFailureKind.REFUSED, ConnectRequest.classifyStatus(502))
        assertEquals(ConnectFailureKind.REFUSED, ConnectRequest.classifyStatus(503))
        assertEquals(ConnectFailureKind.REFUSED, ConnectRequest.classifyStatus(504))
        assertEquals(ConnectFailureKind.GENERAL, ConnectRequest.classifyStatus(200))
        assertEquals(ConnectFailureKind.GENERAL, ConnectRequest.classifyStatus(407))
    }
}

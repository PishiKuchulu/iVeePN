package com.pishi.iveepn

import com.pishi.iveepn.vpn.upstream.Socks5Exception
import com.pishi.iveepn.vpn.upstream.Socks5Protocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class Socks5ProtocolTest {

    @Test
    fun greetingWithoutAuthOffersNoAuthOnly() {
        assertArrayEquals(
            byteArrayOf(0x05, 0x01, 0x00),
            Socks5Protocol.greeting(false),
        )
    }

    @Test
    fun greetingWithAuthOffersBothMethods() {
        assertArrayEquals(
            byteArrayOf(0x05, 0x02, 0x00, 0x02),
            Socks5Protocol.greeting(true),
        )
    }

    @Test
    fun selectedMethodValidatesReply() {
        assertEquals(0x00.toByte(), Socks5Protocol.selectedMethod(byteArrayOf(0x05, 0x00)))
        assertEquals(0x02.toByte(), Socks5Protocol.selectedMethod(byteArrayOf(0x05, 0x02)))
        expectSocksFailure { Socks5Protocol.selectedMethod(byteArrayOf(0x05, 0xFF.toByte())) }
        expectSocksFailure { Socks5Protocol.selectedMethod(byteArrayOf(0x04, 0x00)) }
        expectSocksFailure { Socks5Protocol.selectedMethod(byteArrayOf(0x05, 0x03)) }
    }

    @Test
    fun authRequestLayout() {
        assertArrayEquals(
            byteArrayOf(0x01, 0x04) + "user".toByteArray() + byteArrayOf(0x04) + "pass".toByteArray(),
            Socks5Protocol.authRequest("user", "pass"),
        )
    }

    @Test
    fun authReplyChecked() {
        Socks5Protocol.checkAuthReply(byteArrayOf(0x01, 0x00))
        expectSocksFailure { Socks5Protocol.checkAuthReply(byteArrayOf(0x01, 0x01)) }
    }

    @Test
    fun connectRequestIpv4() {
        assertArrayEquals(
            byteArrayOf(0x05, 0x01, 0x00, 0x01, 203.toByte(), 0, 113, 7, 0x01, 0xBB.toByte()),
            Socks5Protocol.connectRequest("203.0.113.7", 443),
        )
    }

    @Test
    fun connectRequestDomain() {
        assertArrayEquals(
            byteArrayOf(0x05, 0x01, 0x00, 0x03, 0x0B) +
                "example.com".toByteArray() + byteArrayOf(0x00, 0x50),
            Socks5Protocol.connectRequest("example.com", 80),
        )
    }

    @Test
    fun connectReplySuccessIpv4ConsumedExactly() {
        val reply = byteArrayOf(0x05, 0x00, 0x00, 0x01, 10, 0, 0, 1, 0x04, 0x38, 0x7F)
        val input = ByteArrayInputStream(reply)
        Socks5Protocol.readConnectReply(input)
        assertEquals(0x7F, input.read()) // trailing byte untouched
    }

    @Test
    fun connectReplySuccessDomainBound() {
        val reply = byteArrayOf(0x05, 0x00, 0x00, 0x03, 0x03) +
            "foo".toByteArray() + byteArrayOf(0x00, 0x50)
        Socks5Protocol.readConnectReply(ByteArrayInputStream(reply))
    }

    @Test
    fun connectReplyErrorCodeReported() {
        val reply = byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
        try {
            Socks5Protocol.readConnectReply(ByteArrayInputStream(reply))
            fail("expected Socks5Exception")
        } catch (expected: Socks5Exception) {
            assertEquals(true, expected.message!!.contains("refused"))
        }
    }

    @Test
    fun connectReplyTruncatedFails() {
        expectSocksFailure {
            Socks5Protocol.readConnectReply(ByteArrayInputStream(byteArrayOf(0x05, 0x00)))
        }
    }

    private fun expectSocksFailure(block: () -> Unit) {
        try {
            block()
            fail("expected Socks5Exception")
        } catch (expected: Socks5Exception) {
            // expected
        }
    }
}

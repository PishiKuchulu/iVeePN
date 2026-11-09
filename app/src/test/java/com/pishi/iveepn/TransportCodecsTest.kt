package com.pishi.iveepn

import com.pishi.iveepn.data.DnsWire
import com.pishi.iveepn.vpn.upstream.FirstWriteSegmenter
import com.pishi.iveepn.vpn.upstream.SegmentingOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsWireTest {

    @Test
    fun buildQueryLayout() {
        val query = DnsWire.buildQuery("api.example.com", DnsWire.TYPE_A, id = 0x1234)
        assertEquals(0x12, query[0].toInt() and 0xFF)
        assertEquals(0x34, query[1].toInt() and 0xFF)
        assertEquals(1, query[5].toInt()) // QDCOUNT
        // QNAME: 3 api 7 example 3 com 0
        assertEquals(3, query[12].toInt())
        assertEquals('a'.code, query[13].toInt())
        assertEquals(1, query[query.lastIndex].toInt()) // QCLASS IN (low byte)
        assertEquals(DnsWire.TYPE_A, query[query.lastIndex - 2].toInt()) // QTYPE (low byte)
    }

    @Test
    fun parseAddressesExtractsAAndAaaaSkippingCname() {
        val response = buildResponse()
        val addresses = DnsWire.parseAddresses(response)
        assertEquals(2, addresses.size)
        assertTrue(addresses[0].contentEquals(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
        assertEquals(16, addresses[1].size)
        assertEquals(0x20, addresses[1][0].toInt() and 0xFF)
    }

    @Test
    fun parseAddressesRejectsGarbage() {
        assertTrue(DnsWire.parseAddresses(byteArrayOf(1, 2, 3)).isEmpty())
        assertTrue(DnsWire.parseAddresses(ByteArray(12)).isEmpty())
    }

    /** Response: 1 question; answers CNAME (pointer name), A, AAAA. */
    private fun buildResponse(): ByteArray {
        val out = ByteArrayOutputStream()
        // Header: id 0x1234, flags 0x8180, QD=1, AN=3
        out.write(byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 3, 0, 0, 0, 0))
        // Question: 3 api 7 example 3 com 0, type A, class IN (offset 12)
        out.write(byteArrayOf(3))
        out.write("api".toByteArray())
        out.write(byteArrayOf(7))
        out.write("example".toByteArray())
        out.write(byteArrayOf(3))
        out.write("com".toByteArray())
        out.write(byteArrayOf(0, 0, 1, 0, 1))
        // Answer 1: name = pointer to 12, CNAME, ttl 300, rdata = pointer to 16
        out.write(byteArrayOf(0xC0.toByte(), 0x0C, 0, 5, 0, 1, 0, 0, 0x01, 0x2C, 0, 2, 0xC0.toByte(), 0x10))
        // Answer 2: name pointer, A, ttl 300, rdata 93.184.216.34
        out.write(
            byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0x01, 0x2C, 0, 4, 93, 184.toByte(), 216.toByte(), 34),
        )
        // Answer 3: name pointer, AAAA, ttl 300, rdata 2001:db8::1
        val aaaa = ByteArray(16)
        aaaa[0] = 0x20
        aaaa[1] = 0x01
        aaaa[2] = 0x0D
        aaaa[3] = 0xB8.toByte()
        aaaa[15] = 1
        out.write(byteArrayOf(0xC0.toByte(), 0x0C, 0, 28, 0, 1, 0, 0, 0x01, 0x2C, 0, 16))
        out.write(aaaa)
        return out.toByteArray()
    }
}

class FirstWriteSegmenterTest {

    @Test
    fun unarmedWritePassesThroughWhole() {
        val segmenter = FirstWriteSegmenter(listOf(1, 64))
        assertEquals(listOf(0 to 500), segmenter.segment(0, 500))
    }

    @Test
    fun armedFirstWriteIsSegmentedAndCoversInput() {
        val segmenter = FirstWriteSegmenter(listOf(1, 64))
        segmenter.arm()
        val parts = segmenter.segment(10, 500)
        assertEquals(listOf(10 to 1, 11 to 64, 75 to 435), parts)
        assertEquals(500, parts.sumOf { it.second })
        // Second write is whole again.
        assertEquals(listOf(0 to 100), segmenter.segment(0, 100))
    }

    @Test
    fun shortFirstWriteSegmentsClamp() {
        val segmenter = FirstWriteSegmenter(listOf(1, 64))
        segmenter.arm()
        assertEquals(listOf(0 to 1, 1 to 29), segmenter.segment(0, 30))
    }

    @Test
    fun streamWritesSegmentsInOrder() {
        val segmenter = FirstWriteSegmenter(listOf(2, 3))
        segmenter.arm()
        val sink = ByteArrayOutputStream()
        val pauses = mutableListOf<Long>()
        val stream = SegmentingOutputStream(sink, segmenter, pauseMs = 7) { pauses += it }
        val data = ByteArray(10) { it.toByte() }
        stream.write(data, 0, 10)
        assertTrue(data.contentEquals(sink.toByteArray()))
        assertEquals(listOf(7L, 7L), pauses) // between 3 segments
        stream.write(data, 0, 4) // second write: no pauses
        assertEquals(2, pauses.size)
    }
}

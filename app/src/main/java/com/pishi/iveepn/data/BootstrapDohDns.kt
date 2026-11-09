package com.pishi.iveepn.data

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Wire-format DNS codec (RFC 1035) for bootstrap resolution: builds
 * A/AAAA queries and extracts A/AAAA answers, following compression
 * pointers in names. Pure and unit-tested.
 */
internal object DnsWire {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    fun buildQuery(hostname: String, qtype: Int, id: Int = Random.nextInt(0, 65536)): ByteArray {
        val name = hostname.trimEnd('.')
        require(name.isNotEmpty() && name.length <= 253) { "Invalid host name for DNS query" }
        val labels = name.split('.')
        val qnameSize = labels.sumOf { it.length + 1 } + 1
        val out = ByteArray(12 + qnameSize + 4)
        out[0] = (id ushr 8).toByte()
        out[1] = id.toByte()
        out[2] = 0x01 // RD
        out[5] = 0x01 // QDCOUNT = 1
        var at = 12
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..63) { "Invalid DNS label" }
            out[at++] = bytes.size.toByte()
            System.arraycopy(bytes, 0, out, at, bytes.size)
            at += bytes.size
        }
        out[at++] = 0
        out[at++] = (qtype ushr 8).toByte()
        out[at++] = qtype.toByte()
        out[at++] = 0
        out[at] = 1 // QCLASS IN
        return out
    }

    /** All A and AAAA rdata byte arrays in [response], in answer order. */
    fun parseAddresses(response: ByteArray): List<ByteArray> {
        if (response.size < 12) return emptyList()
        val qdCount = u16(response, 4)
        val anCount = u16(response, 6)
        var at = 12
        repeat(qdCount) {
            at = skipName(response, at) ?: return emptyList()
            at += 4
            if (at > response.size) return emptyList()
        }
        val addresses = mutableListOf<ByteArray>()
        repeat(anCount) {
            at = skipName(response, at) ?: return addresses
            if (at + 10 > response.size) return addresses
            val type = u16(response, at)
            val rdLength = u16(response, at + 8)
            at += 10
            if (at + rdLength > response.size) return addresses
            if (type == TYPE_A && rdLength == 4) {
                addresses += response.copyOfRange(at, at + 4)
            } else if (type == TYPE_AAAA && rdLength == 16) {
                addresses += response.copyOfRange(at, at + 16)
            }
            at += rdLength
        }
        return addresses
    }

    /** Offset just past the name at [start], honouring compression pointers; null if malformed. */
    private fun skipName(message: ByteArray, start: Int): Int? {
        var at = start
        var endAfterFirstPointer: Int? = null
        var hops = 0
        while (at < message.size) {
            val length = message[at].toInt() and 0xFF
            when {
                length == 0 -> return endAfterFirstPointer ?: (at + 1)
                length and 0xC0 == 0xC0 -> {
                    if (at + 1 >= message.size) return null
                    if (endAfterFirstPointer == null) endAfterFirstPointer = at + 2
                    at = ((length and 0x3F) shl 8) or (message[at + 1].toInt() and 0xFF)
                    if (++hops > 16) return null
                }
                else -> at += 1 + length
            }
        }
        return null
    }

    private fun u16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
}

/**
 * Independent bootstrap DNS for the VeePN API client: resolves API
 * domains through the configured DoH provider (DNS-over-HTTPS,
 * wire format) instead of the system resolver, so DNS poisoning of
 * the API domains cannot strand the app before the tunnel exists.
 *
 * The DoH call itself uses a bare client on the system resolver —
 * the built-in providers are IP literals, so no bootstrap paradox
 * arises; a custom DoH URL with a host name resolves that one host
 * via the system. Any DoH failure falls back to the system resolver,
 * and results are cached briefly.
 */
class BootstrapDohDns(
    private val dohUrl: String,
    private val fallback: Dns = Dns.SYSTEM,
) : Dns {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private data class Cached(val addresses: List<InetAddress>, val atMs: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    override fun lookup(hostname: String): List<InetAddress> {
        if (isIpLiteral(hostname)) {
            return listOf(InetAddress.getByName(hostname))
        }
        val cached = cache[hostname]
        if (cached != null && System.currentTimeMillis() - cached.atMs < CACHE_TTL_MS) {
            return cached.addresses
        }
        val resolved = runCatching { queryAll(hostname) }.getOrNull()
        if (!resolved.isNullOrEmpty()) {
            cache[hostname] = Cached(resolved, System.currentTimeMillis())
            return resolved
        }
        return fallback.lookup(hostname)
    }

    private fun queryAll(hostname: String): List<InetAddress> {
        val out = mutableListOf<InetAddress>()
        for (qtype in listOf(DnsWire.TYPE_A, DnsWire.TYPE_AAAA)) {
            val body = query(hostname, qtype) ?: continue
            for (rdata in DnsWire.parseAddresses(body)) {
                out += InetAddress.getByAddress(rdata)
            }
        }
        if (out.isEmpty()) throw UnknownHostException("DoH returned no addresses for $hostname")
        return out
    }

    private fun query(hostname: String, qtype: Int): ByteArray? {
        val request = Request.Builder()
            .url(dohUrl)
            .header("Accept", DNS_MESSAGE_MEDIA)
            .post(DnsWire.buildQuery(hostname, qtype).toRequestBody(DNS_MESSAGE_MEDIA.toMediaType()))
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.bytes()
        }
    }

    private fun isIpLiteral(host: String): Boolean =
        ':' in host || host.all { it.isDigit() || it == '.' }

    companion object {
        private const val CACHE_TTL_MS = 300_000L
        private const val DNS_MESSAGE_MEDIA = "application/dns-message"
    }
}

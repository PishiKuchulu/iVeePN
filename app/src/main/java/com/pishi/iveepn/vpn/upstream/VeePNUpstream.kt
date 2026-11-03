package com.pishi.iveepn.vpn.upstream

import com.pishi.iveepn.data.model.VeePNServer
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** How a failed upstream CONNECT should be handled by the server pool. */
enum class ConnectFailureKind {
    /** 401/403: guest credentials rejected - refresh them, then retry. */
    CREDENTIALS,

    /** 429: rate limited - brief breather, then rotate to another server. */
    ROTATE,

    /** The proxy itself could not be reached, or the target does not exist. */
    HOST_UNREACHABLE,

    /** The proxy refused the tunnel (5xx family). */
    REFUSED,

    /** Anything else. */
    GENERAL,
}

/** Failure of an upstream CONNECT, carrying the pool-actionable [kind]. */
class UpstreamError(
    val kind: ConnectFailureKind,
    message: String,
    val statusCode: Int? = null,
) : IOException(message)

/**
 * Pure construction/classification helpers for the CONNECT handshake.
 * Mirrors the desktop upstream connector; java.* only, no Android imports,
 * so it is fully unit-testable on the JVM.
 */
object ConnectRequest {

    /**
     * Authority form of a target: `host:port`, with bare IPv6 literals
     * bracketed (`[2001:db8::1]:443`). Already-bracketed hosts are left
     * alone; hostnames and IPv4 pass through untouched.
     */
    fun formatTarget(host: String, port: Int): String {
        val trimmed = host.trim()
        val hostPart = when {
            trimmed.startsWith("[") && trimmed.endsWith("]") -> trimmed
            ':' in trimmed -> "[$trimmed]"
            else -> trimmed
        }
        return "$hostPart:$port"
    }

    /**
     * A target host must never carry whitespace or CR/LF: it is written
     * verbatim into the request line and the Host header, so either would
     * allow request smuggling into the CONNECT head.
     */
    fun requireSanitisedHost(host: String) {
        require(host.isNotEmpty()) { "Target host is empty" }
        require(host.none { it == ' ' || it == '\r' || it == '\n' }) {
            "Target host contains whitespace or CR/LF"
        }
    }

    /** Full CONNECT request head for [server] -> target, CRLF-terminated. */
    fun build(server: VeePNServer, targetHost: String, targetPort: Int, userAgent: String): String {
        requireSanitisedHost(targetHost)
        val target = formatTarget(targetHost, targetPort)
        val auth = Base64.getEncoder()
            .encodeToString("${server.username}:${server.password}".toByteArray(Charsets.UTF_8))
        return buildString {
            append("CONNECT ").append(target).append(" HTTP/1.1\r\n")
            append("Host: ").append(target).append("\r\n")
            append("Proxy-Authorization: Basic ").append(auth).append("\r\n")
            append("User-Agent: ").append(userAgent).append("\r\n")
            append("Proxy-Connection: keep-alive\r\n")
            append("\r\n")
        }
    }

    /** Map a CONNECT reply status code to the pool's failure action. */
    fun classifyStatus(code: Int): ConnectFailureKind = when (code) {
        401, 403 -> ConnectFailureKind.CREDENTIALS
        429 -> ConnectFailureKind.ROTATE
        404, 410 -> ConnectFailureKind.HOST_UNREACHABLE
        500, 502, 503, 504 -> ConnectFailureKind.REFUSED
        else -> ConnectFailureKind.GENERAL
    }
}

/**
 * TLS CONNECT connector to an upstream HTTPS proxy.
 *
 * TLS always uses the default [SSLSocketFactory] (system trust store);
 * there is deliberately no way to relax verification here.
 */
object VeePNUpstream {

    /** Chrome 130 user agent, identical to the desktop client. */
    const val USER_AGENT: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private const val MAX_HEAD_BYTES = 16 * 1024
    private const val MAX_HEAD_LINES = 64
    private const val MAX_EXIT_IP_BYTES = 1024 * 1024
    private const val EXIT_IP_READ_TIMEOUT_MS = 10_000

    // Exit-IP echo probes, in desktop order: ipify (JSON) first, then the
    // ifconfig.me mirror (plain page, IP extracted by regex).
    private val EXIT_IP_PROBES = listOf(
        "api.ipify.org" to "/?format=json",
        "ifconfig.me" to "/",
    )

    private val IPV4_IN_TEXT = Regex("""\b(\d{1,3}(?:\.\d{1,3}){3})\b""")
    private val JSON_IP_FIELD = Regex(""""ip"\s*:\s*"([^"]+)"""")

    /**
     * Open a tunnel to `targetHost:targetPort` through [server]: TCP to a
     * server address (tried in order), TLS wrap with SNI = proxy host,
     * CONNECT with basic auth. On HTTP 200 the live socket is returned,
     * positioned at the first tunnel byte. Non-200 replies close the
     * socket and throw [UpstreamError] with the classified kind.
     */
    fun openTlsConnect(
        server: VeePNServer,
        targetHost: String,
        targetPort: Int,
        connectTimeoutMs: Int = 10_000,
    ): Socket {
        ConnectRequest.requireSanitisedHost(targetHost)
        if (server.addresses.isEmpty() || server.port !in 1..65535) {
            throw UpstreamError(ConnectFailureKind.GENERAL, "Malformed server entry")
        }
        var lastFailure: Exception? = null
        for (address in server.addresses) {
            var socket: Socket? = null
            try {
                socket = openTlsSocket(address, server.port, connectTimeoutMs)
                val request = ConnectRequest.build(server, targetHost, targetPort, USER_AGENT)
                val out = socket.getOutputStream()
                out.write(request.toByteArray(Charsets.ISO_8859_1))
                out.flush()

                val input = socket.getInputStream()
                val statusLine = readLineBounded(input)
                    ?: throw IOException("Upstream closed the connection without a reply")
                val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                // Consume the remaining response headers.
                var lines = 0
                while (lines < MAX_HEAD_LINES) {
                    val line = readLineBounded(input) ?: break
                    if (line.isEmpty()) break
                    lines++
                }
                if (statusCode == 200) {
                    // The tunnel is a long-lived stream now; the handshake
                    // deadline must not apply to it.
                    runCatching { socket.soTimeout = 0 }
                    runCatching { socket.keepAlive = true }
                    return socket
                }
                throw UpstreamError(
                    ConnectRequest.classifyStatus(statusCode ?: -1),
                    "Upstream CONNECT failed: ${statusLine.trim()}",
                    statusCode,
                )
            } catch (e: UpstreamError) {
                closeQuietly(socket)
                throw e
            } catch (e: IOException) {
                // TCP / TLS / timeout failure on this address: try the next.
                lastFailure = e
                closeQuietly(socket)
            }
        }
        // String(timeout exceptions) can be empty, so tag them explicitly.
        val detail = if (lastFailure is SocketTimeoutException) {
            "handshake timeout"
        } else {
            lastFailure?.message ?: lastFailure?.javaClass?.simpleName ?: "unknown error"
        }
        throw UpstreamError(
            ConnectFailureKind.HOST_UNREACHABLE,
            "Cannot reach upstream proxy ${server.addresses}:${server.port}: $detail",
        )
    }

    /**
     * Fetch the public exit IP through a fresh tunnel to [server].
     * Mirrors are tried in order for inner TLS/HTTP hiccups, but an
     * [UpstreamError] from the tunnel itself aborts immediately: if the
     * tunnel cannot be opened, another echo host will not help.
     * Returns null when every mirror answered without a usable IP.
     */
    fun checkExitIp(server: VeePNServer): String? {
        for ((echoHost, path) in EXIT_IP_PROBES) {
            try {
                val ip = exitIpVia(server, echoHost, path)
                if (ip != null) return ip
                // Tunnel opened but no IP in the answer: try the mirror.
            } catch (e: UpstreamError) {
                throw e
            } catch (e: Exception) {
                // Inner TLS/HTTP hiccup: fall through to the next mirror.
            }
        }
        return null
    }

    private fun exitIpVia(server: VeePNServer, echoHost: String, path: String): String? {
        val tunnel = openTlsConnect(server, echoHost, 443)
        var secure: Socket? = null
        try {
            // Inside the tunnel the client does TLS with the target itself;
            // the proxy only relays bytes.
            secure = wrapTls(tunnel, echoHost, 443)
            secure.soTimeout = EXIT_IP_READ_TIMEOUT_MS
            secure.startHandshake()
            val request = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(echoHost).append("\r\n")
                append("User-Agent: ").append(USER_AGENT).append("\r\n")
                append("Accept: */*\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            val out = secure.getOutputStream()
            out.write(request.toByteArray(Charsets.ISO_8859_1))
            out.flush()

            val input = secure.getInputStream()
            val buffer = ByteArray(MAX_EXIT_IP_BYTES)
            var total = 0
            val chunk = ByteArray(16 * 1024)
            while (total < MAX_EXIT_IP_BYTES) {
                val read = try {
                    input.read(chunk, 0, minOf(chunk.size, MAX_EXIT_IP_BYTES - total))
                } catch (e: SocketTimeoutException) {
                    break
                }
                if (read < 0) break
                System.arraycopy(chunk, 0, buffer, total, read)
                total += read
            }
            val text = String(buffer, 0, total, Charsets.UTF_8)
            val body = text.substringAfter("\r\n\r\n", text)
            val jsonIp = JSON_IP_FIELD.find(body)?.groupValues?.get(1)?.trim()
            if (!jsonIp.isNullOrEmpty()) return jsonIp
            return IPV4_IN_TEXT.find(body)?.groupValues?.get(1)
        } finally {
            closeQuietly(secure ?: tunnel)
            if (secure != null) closeQuietly(tunnel)
        }
    }

    private fun openTlsSocket(address: String, port: Int, timeoutMs: Int): SSLSocket {
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(address, port), timeoutMs)
            plain.soTimeout = timeoutMs
            return wrapTls(plain, address, port).apply { soTimeout = timeoutMs }
        } catch (e: IOException) {
            closeQuietly(plain)
            throw e
        }
    }

    /**
     * Layer TLS over [underlying] with certificate verification against the
     * system trust store and SNI set to [host] (skipped for IP literals,
     * which are not valid SNI values).
     */
    private fun wrapTls(underlying: Socket, host: String, port: Int): SSLSocket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val tls = factory.createSocket(underlying, host, port, true) as SSLSocket
        if (!isIpLiteral(host)) {
            runCatching {
                val params = tls.sslParameters
                params.serverNames = listOf(SNIHostName(host))
                tls.sslParameters = params
            }
        }
        return tls
    }

    private fun isIpLiteral(host: String): Boolean =
        ':' in host || host.all { it.isDigit() || it == '.' }

    /**
     * Read one CRLF/LF-terminated line byte by byte from the raw socket
     * stream. Deliberately unbuffered: a buffered reader could prefetch
     * tunnel bytes past the CONNECT headers, and those bytes belong to
     * the caller. Returns null on clean EOF before any byte.
     */
    private fun readLineBounded(input: InputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (bytes.size < MAX_HEAD_BYTES) {
            val next = input.read()
            if (next < 0) return if (bytes.isEmpty()) null else decodeLine(bytes)
            if (next == '\n'.code) return decodeLine(bytes)
            bytes.add(next.toByte())
        }
        throw IOException("Upstream response head exceeds $MAX_HEAD_BYTES bytes")
    }

    private fun decodeLine(bytes: List<Byte>): String {
        val raw = ByteArray(bytes.size) { bytes[it] }
        return String(raw, Charsets.ISO_8859_1).trimEnd('\r')
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (e: Exception) {
            // Closing a failed socket is best-effort.
        }
    }
}

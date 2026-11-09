package com.pishi.iveepn.vpn.upstream

import com.pishi.iveepn.data.UpstreamProxyConfig
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException

/** The upstream SOCKS5 proxy refused or garbled the handshake. */
class Socks5Exception(message: String) : IOException(message)

/**
 * Minimal SOCKS5 client (RFC 1928 + RFC 1929 username/password auth),
 * just enough to open a CONNECT stream through a user-configured
 * upstream proxy before TLS is layered over it. The byte-building and
 * reply-parsing halves are pure and unit-tested; [connect] is the thin
 * socket glue.
 */
internal object Socks5Protocol {

    const val VERSION: Byte = 0x05
    const val METHOD_NO_AUTH: Byte = 0x00
    const val METHOD_USER_PASS: Byte = 0x02
    const val METHOD_NONE_ACCEPTABLE: Byte = 0xFF.toByte()

    fun greeting(withAuth: Boolean): ByteArray =
        if (withAuth) {
            byteArrayOf(VERSION, 0x02, METHOD_NO_AUTH, METHOD_USER_PASS)
        } else {
            byteArrayOf(VERSION, 0x01, METHOD_NO_AUTH)
        }

    /** Returns the method the proxy selected; throws on anything unusable. */
    fun selectedMethod(reply: ByteArray): Byte {
        if (reply.size != 2 || reply[0] != VERSION) {
            throw Socks5Exception("Malformed SOCKS5 greeting reply")
        }
        val method = reply[1]
        if (method == METHOD_NONE_ACCEPTABLE) {
            throw Socks5Exception("Upstream proxy accepts none of the offered auth methods")
        }
        if (method != METHOD_NO_AUTH && method != METHOD_USER_PASS) {
            throw Socks5Exception("Upstream proxy selected an unsupported auth method: ${method.toInt() and 0xFF}")
        }
        return method
    }

    fun authRequest(username: String, secret: String): ByteArray {
        val user = username.toByteArray(Charsets.UTF_8)
        val pass = secret.toByteArray(Charsets.UTF_8)
        require(user.size <= 255 && pass.size <= 255) { "SOCKS5 credentials exceed 255 bytes" }
        val out = ByteArray(3 + user.size + pass.size)
        out[0] = 0x01 // RFC 1929 version
        out[1] = user.size.toByte()
        System.arraycopy(user, 0, out, 2, user.size)
        out[2 + user.size] = pass.size.toByte()
        System.arraycopy(pass, 0, out, 3 + user.size, pass.size)
        return out
    }

    fun checkAuthReply(reply: ByteArray) {
        if (reply.size != 2 || reply[0] != 0x01.toByte()) {
            throw Socks5Exception("Malformed SOCKS5 auth reply")
        }
        if (reply[1] != 0x00.toByte()) {
            throw Socks5Exception("Upstream proxy rejected the supplied credentials")
        }
    }

    fun connectRequest(targetHost: String, targetPort: Int): ByteArray {
        require(targetPort in 1..65535) { "Invalid target port: $targetPort" }
        val address = addressBytes(targetHost)
        val out = ByteArray(3 + address.size + 2)
        out[0] = VERSION
        out[1] = 0x01 // CONNECT
        out[2] = 0x00 // reserved
        System.arraycopy(address, 0, out, 3, address.size)
        out[3 + address.size] = (targetPort ushr 8).toByte()
        out[4 + address.size] = targetPort.toByte()
        return out
    }

    /** VER + ATYP + address bytes for an IPv4, IPv6, or domain target. */
    private fun addressBytes(targetHost: String): ByteArray {
        val literal = parseIpLiteral(targetHost)
        if (literal != null) {
            return if (literal.size == 4) {
                byteArrayOf(0x01) + literal
            } else {
                byteArrayOf(0x04) + literal
            }
        }
        val domain = targetHost.toByteArray(Charsets.UTF_8)
        require(domain.size in 1..255) { "Target host name too long for SOCKS5" }
        return byteArrayOf(0x03, domain.size.toByte()) + domain
    }

    private fun parseIpLiteral(host: String): ByteArray? {
        if (host.contains(':')) {
            return try {
                InetAddress.getByName(host).address.takeIf { it.size == 16 }
            } catch (e: UnknownHostException) {
                null
            }
        }
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.all(Char::isDigit) && p.toInt() in 0..255 }) {
            return ByteArray(4) { parts[it].toInt().toByte() }
        }
        return null
    }

    /** Human-readable meaning of a CONNECT reply code (RFC 1928 §6). */
    fun replyErrorMessage(rep: Int): String = when (rep) {
        0x00 -> "succeeded"
        0x01 -> "general SOCKS server failure"
        0x02 -> "connection not allowed by ruleset"
        0x03 -> "network unreachable"
        0x04 -> "host unreachable"
        0x05 -> "connection refused"
        0x06 -> "TTL expired"
        0x07 -> "command not supported"
        0x08 -> "address type not supported"
        else -> "unknown reply code $rep"
    }

    /** Reads and validates a CONNECT reply from [input]; consumes exactly the reply bytes. */
    fun readConnectReply(input: InputStream) {
        val head = readExactly(input, 4)
        if (head[0] != VERSION) throw Socks5Exception("Malformed SOCKS5 connect reply")
        val rep = head[1].toInt() and 0xFF
        val addressLength = when (head[3].toInt() and 0xFF) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> {
                val len = readExactly(input, 1)[0].toInt() and 0xFF
                len
            }
            else -> throw Socks5Exception("Malformed SOCKS5 connect reply (address type)")
        }
        readExactly(input, addressLength + 2) // bound address + port
        if (rep != 0x00) {
            throw Socks5Exception("Upstream proxy CONNECT failed: ${replyErrorMessage(rep)}")
        }
    }

    fun readExactly(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(out, read, count - read)
            if (n < 0) throw Socks5Exception("Upstream proxy closed the connection mid-handshake")
            read += n
        }
        return out
    }
}

/** Opens TCP streams through a user-configured upstream SOCKS5 proxy. */
internal object Socks5Connector {

    /**
     * Connects to [proxy], performs the handshake, and returns a socket
     * whose stream is a tunnel to [targetHost]:[targetPort]. The caller
     * owns the returned socket.
     */
    fun connect(
        proxy: UpstreamProxyConfig,
        targetHost: String,
        targetPort: Int,
        timeoutMs: Int,
    ): Socket {
        val socket = Socket()
        try {
            socket.connect(java.net.InetSocketAddress(proxy.host, proxy.port), timeoutMs)
            socket.soTimeout = timeoutMs
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            val withAuth = proxy.username != null
            out.write(Socks5Protocol.greeting(withAuth))
            out.flush()
            val method = Socks5Protocol.selectedMethod(
                Socks5Protocol.readExactly(input, 2),
            )
            if (method == Socks5Protocol.METHOD_USER_PASS) {
                val user = proxy.username ?: throw Socks5Exception(
                    "Upstream proxy requires credentials but none are configured",
                )
                out.write(Socks5Protocol.authRequest(user, proxy.secret ?: ""))
                out.flush()
                Socks5Protocol.checkAuthReply(Socks5Protocol.readExactly(input, 2))
            }
            out.write(Socks5Protocol.connectRequest(targetHost, targetPort))
            out.flush()
            Socks5Protocol.readConnectReply(input)
            return socket
        } catch (failure: Exception) {
            runCatching { socket.close() }
            throw failure
        }
    }
}

package com.pishi.iveepn.vpn.upstream

import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * Experimental TLS ClientHello segmentation ("fragmentation").
 *
 * The first write on an armed stream — in practice the ClientHello,
 * which the TLS stack emits as its first flight — is split into small
 * segments with a short pause between them, so the handshake bytes do
 * not cross the network as one tidy record. Everything after that
 * first write passes through untouched. Segmentation is transparent
 * to the peer (TCP is a byte stream); the cost is a few tens of
 * milliseconds on connection setup.
 *
 * This is OFF by default and labelled experimental in Settings: its
 * benefit depends entirely on the network's filtering behaviour.
 */
internal class FirstWriteSegmenter(
    private val segmentSizes: List<Int> = listOf(1, 64),
) {
    @Volatile
    private var armed = false

    @Volatile
    private var consumed = false

    fun arm() {
        armed = true
    }

    val isConsumed: Boolean get() = consumed

    /**
     * Splits [buffer] for the first armed write; returns a single
     * full-range segment for every other write. Segments are
     * (offset, length) pairs covering the input exactly, in order.
     */
    @Synchronized
    fun segment(offset: Int, length: Int): List<Pair<Int, Int>> {
        if (!armed || consumed || length <= 0) {
            return listOf(offset to length)
        }
        consumed = true
        val parts = mutableListOf<Pair<Int, Int>>()
        var remaining = length
        var at = offset
        for (size in segmentSizes) {
            if (remaining <= 0) break
            val n = minOf(size, remaining)
            parts += at to n
            at += n
            remaining -= n
        }
        if (remaining > 0) parts += at to remaining
        return parts
    }
}

/** Output stream that segments its first armed write via [segmenter]. */
internal class SegmentingOutputStream(
    delegate: OutputStream,
    private val segmenter: FirstWriteSegmenter,
    private val pauseMs: Long = DEFAULT_PAUSE_MS,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : FilterOutputStream(delegate) {

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        val parts = segmenter.segment(offset, length)
        if (parts.size == 1) {
            out.write(buffer, offset, length)
            return
        }
        parts.forEachIndexed { index, (off, len) ->
            out.write(buffer, off, len)
            out.flush()
            if (index < parts.lastIndex && pauseMs > 0) sleeper(pauseMs)
        }
    }

    override fun write(single: Int) {
        out.write(single)
    }

    companion object {
        const val DEFAULT_PAUSE_MS = 25L
    }
}

/**
 * A [Socket] delegating to a real connected socket, whose output
 * stream segments the first write after [armFragmentation]. Used
 * under a layered TLS socket: the handshake bytes flow through this
 * socket's stream, so arming it just before the TLS wrap segments the
 * ClientHello.
 */
internal class FragmentingSocket(
    private val delegate: Socket,
    private val segmenter: FirstWriteSegmenter = FirstWriteSegmenter(),
) : Socket() {

    fun armFragmentation() = segmenter.arm()

    private var stream: OutputStream? = null

    @Synchronized
    override fun getOutputStream(): OutputStream =
        stream ?: SegmentingOutputStream(delegate.getOutputStream(), segmenter).also { stream = it }

    override fun getInputStream() = delegate.getInputStream()
    override fun connect(endpoint: SocketAddress?) = delegate.connect(endpoint)
    override fun connect(endpoint: SocketAddress?, timeout: Int) = delegate.connect(endpoint, timeout)
    override fun bind(bindpoint: SocketAddress?) = delegate.bind(bindpoint)
    override fun close() = delegate.close()
    override fun isClosed() = delegate.isClosed
    override fun isConnected() = delegate.isConnected
    override fun isBound() = delegate.isBound
    override fun isInputShutdown() = delegate.isInputShutdown
    override fun isOutputShutdown() = delegate.isOutputShutdown
    override fun shutdownInput() = delegate.shutdownInput()
    override fun shutdownOutput() = delegate.shutdownOutput()
    override fun getInetAddress(): InetAddress = delegate.inetAddress
    override fun getPort() = delegate.port
    override fun getLocalPort() = delegate.localPort
    override fun getLocalAddress(): InetAddress = delegate.localAddress
    override fun getRemoteSocketAddress(): SocketAddress = delegate.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress = delegate.localSocketAddress
    override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
    override fun getSoTimeout() = delegate.soTimeout
    override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
    override fun getTcpNoDelay() = delegate.tcpNoDelay
    override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
    override fun getKeepAlive() = delegate.keepAlive
    override fun setSoLinger(on: Boolean, linger: Int) { delegate.setSoLinger(on, linger) }
    override fun getSoLinger() = delegate.soLinger
    override fun setReuseAddress(on: Boolean) { delegate.reuseAddress = on }
    override fun getReuseAddress() = delegate.reuseAddress
    override fun setTrafficClass(trafficClass: Int) { delegate.trafficClass = trafficClass }
    override fun getTrafficClass() = delegate.trafficClass
    override fun setSendBufferSize(size: Int) { delegate.sendBufferSize = size }
    override fun getSendBufferSize() = delegate.sendBufferSize
    override fun setReceiveBufferSize(size: Int) { delegate.receiveBufferSize = size }
    override fun getReceiveBufferSize() = delegate.receiveBufferSize
    override fun toString() = "FragmentingSocket($delegate)"
}

/** OkHttp socket factory producing pre-armed fragmenting sockets (API-client TLS). */
internal class FragmentingSocketFactory(
    private val delegate: SocketFactory = getDefault(),
) : SocketFactory() {
    override fun createSocket(): Socket =
        FragmentingSocket(delegate.createSocket()).apply { armFragmentation() }

    override fun createSocket(host: String, port: Int): Socket =
        FragmentingSocket(delegate.createSocket(host, port)).apply { armFragmentation() }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        FragmentingSocket(delegate.createSocket(host, port, localHost, localPort)).apply { armFragmentation() }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        FragmentingSocket(delegate.createSocket(host, port)).apply { armFragmentation() }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        FragmentingSocket(delegate.createSocket(address, port, localAddress, localPort)).apply { armFragmentation() }
}

package com.pishi.iveepn.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.pishi.iveepn.IVeePNApp
import com.pishi.iveepn.MainActivity
import com.pishi.iveepn.R
import com.pishi.iveepn.data.AppLogger
import com.pishi.iveepn.data.SettingsStore
import com.pishi.iveepn.data.formatBytesPerSecond
import com.pishi.iveepn.data.withAppLocale
import com.pishi.iveepn.data.model.ConnectionState
import com.pishi.iveepn.ui.UiErrors
import com.pishi.iveepn.vpn.socks.LocalHttpProxyServer
import com.pishi.iveepn.vpn.socks.LocalSocks5Server
import com.pishi.iveepn.vpn.tun.HevSocks5Tunnel
import com.pishi.iveepn.vpn.tun.HevSocks5TunnelConfig
import com.pishi.iveepn.vpn.upstream.UpstreamSession
import com.pishi.iveepn.vpn.upstream.VeePNSession
import com.pishi.iveepn.widget.QuickConnectWidgetProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

data class VpnLiveStats(
    val txRateBytesPerSec: Long = 0L,
    val rxRateBytesPerSec: Long = 0L,
    val sessionBytes: Long = 0L,
)

private const val TAG = "IVeePNVpnService"
private const val CONNECT_TIMEOUT_MS = 60_000L

private const val UPSTREAM_WATCHDOG_INTERVAL_MS = 2_000L

private const val RECONNECT_FAILURES_BEFORE_WARNING = 5
private const val RECONNECT_BACKOFF_BASE_MS = 3_000L
private const val RECONNECT_BACKOFF_CAP_MS = 60_000L

private const val UNHEALTHY_REDIAL_COOLDOWN_MS = 30_000L

private const val POST_TUN_SETTLE_MS = 250L

private const val INITIAL_DIAL_MAX_ATTEMPTS = 3
private const val INITIAL_DIAL_SETTLE_MS = 600L
private const val INITIAL_DIAL_BACKOFF_BASE_MS = 2_000L
private const val INITIAL_DIAL_BACKOFF_CAP_MS = 8_000L

private const val SPEED_UPDATE_INTERVAL_MS = 2_000L

private const val TUN_ADDRESS_IPV6 = "fd00:1:fd00:1:fd00:1:fd00:1"
private const val TUN_PREFIX_LENGTH_IPV6 = 128

private const val WAKE_LOCK_TAG = "com.pishi.iveepn:vpn"
private const val WIFI_LOCK_TAG = "com.pishi.iveepn:vpn-wifi"

private fun fullJitterBackoffMs(attempt: Int, baseMs: Long, capMs: Long): Long {
    val exponential = baseMs * (1L shl attempt.coerceIn(0, 10))
    val upperBound = exponential.coerceAtMost(capMs)
    return (upperBound.toDouble() * Math.random()).toLong().coerceAtLeast(baseMs / 4)
}

/**
 * VPN service: TUN (hev-socks5-tunnel) + local SOCKS5/HTTP proxies in front
 * of a [VeePNSession] — the guest-token VeePN upstream for the region the
 * user selected on the Locations screen.
 */
class IVeePNVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val opMutex = Mutex()

    private var tunFd: ParcelFileDescriptor? = null
    private var socksServer: LocalSocks5Server? = null
    private var httpProxyServer: LocalHttpProxyServer? = null
    private var upstreamSession: UpstreamSession? = null
    private var connectJob: Job? = null
    private var watchdogJob: Job? = null
    private var speedJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var tunnelInterfaceActive = false

    @Volatile private var notificationProxyOnly: Boolean = false

    @Volatile private var boundNetwork: Network? = null

    @Volatile private var reportedUnderlying: Network? = null

    @Volatile private var lastUnhealthyRedialAt = 0L

    @Volatile private var statusLabel: String = ""

    @Volatile private var foregroundActive = false

    @Volatile private var lastNotificationText: String? = null

    private class SessionResources(
        val tunFd: ParcelFileDescriptor?,
        val socksServer: LocalSocks5Server?,
        val httpProxyServer: LocalHttpProxyServer?,
        val upstreamSession: UpstreamSession?,
    ) {
        val isEmpty: Boolean get() = tunFd == null && socksServer == null && httpProxyServer == null && upstreamSession == null
    }

    @Volatile
    private var connectionGeneration = 0

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            reportUnderlyingNetwork(network)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            reportUnderlyingNetwork(network, capabilities)
        }

        override fun onLost(network: Network) {
            if (boundNetwork != network) return
            boundNetwork = null
            reportedUnderlying = null

            if (tunnelInterfaceActive) {
                runCatching { setUnderlyingNetworks(null) }
            }
        }
    }

    private fun reportUnderlyingNetwork(
        network: Network,
        capabilities: NetworkCapabilities? = null,
    ) {
        val caps = capabilities
            ?: getSystemService(ConnectivityManager::class.java)?.getNetworkCapabilities(network)

        if (caps == null) return

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return

        if (tunnelInterfaceActive && reportedUnderlying != network) {
            reportedUnderlying = network
            runCatching { setUnderlyingNetworks(arrayOf(network)) }
                .onFailure {
                    reportedUnderlying = null
                    AppLogger.w(TAG, "failed to report the underlying network to the platform", it)
                }
        }

        val previous = boundNetwork
        if (previous == network) return

        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && previous != null) return
        boundNetwork = network
        if (previous == null) return

        if (_state.value != ConnectionState.CONNECTED) return
        val session = upstreamSession ?: return

        AppLogger.i(
            TAG,
            "underlying network changed; closing the upstream session bound to the old one so it is redialed now " +
                "(its sockets would otherwise stay ESTABLISHED and look healthy for minutes)",
        )
        runCatching { session.close() }
    }

    private fun onUpstreamSessionUnhealthy() {
        if (_state.value != ConnectionState.CONNECTED) return
        val session = upstreamSession ?: return
        val now = System.currentTimeMillis()
        val sinceLast = now - lastUnhealthyRedialAt
        if (lastUnhealthyRedialAt != 0L && sinceLast < UNHEALTHY_REDIAL_COOLDOWN_MS) {
            AppLogger.d(
                TAG,
                "ignoring an unhealthy-session verdict ${sinceLast}ms after the last rebuild " +
                    "(cooldown ${UNHEALTHY_REDIAL_COOLDOWN_MS}ms)",
            )
            return
        }
        lastUnhealthyRedialAt = now
        AppLogger.w(
            TAG,
            "the local proxy reports the upstream session is failing as a whole rather than for one destination; " +
                "closing it so the watchdog redials",
        )
        runCatching { session.close() }
    }

    private fun loc(): Context =
        (applicationContext as? IVeePNApp)?.let { applicationContext.withAppLocale(it.settingsStore) }
            ?: applicationContext

    private fun s(@androidx.annotation.StringRes res: Int, vararg args: Any): String =
        loc().getString(res, *args)

    override fun onCreate() {
        super.onCreate()
        statusLabel = s(R.string.notif_connecting)
        runCatching {
            val underlyingNetworks = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            getSystemService(ConnectivityManager::class.java)
                ?.registerNetworkCallback(underlyingNetworks, networkCallback)
        }.onFailure { AppLogger.w(TAG, "could not register a network callback; handovers will be slower to detect", it) }

        scope.launch {
            _state.collect { state ->
                QuickConnectWidgetProvider.updateAll(this@IVeePNVpnService, state)
            }
        }
    }

    override fun onDestroy() {
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
        }
        connectJob?.cancel()
        connectJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        connectionGeneration++
        releaseResources(detachResources(), stopNativeTunnel = true)
        releaseWakeLocks()
        notificationProxyOnly = false
        foregroundActive = false
        lastNotificationText = null
        boundNetwork = null
        reportedUnderlying = null
        scope.cancel()
        _state.value = ConnectionState.DISCONNECTED
        QuickConnectWidgetProvider.updateAll(this, ConnectionState.DISCONNECTED)
        super.onDestroy()
    }

    private fun acquireWakeLocks() {
        if (wakeLock == null) {
            runCatching {
                val power = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    ?: error("PowerManager unavailable")
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure { AppLogger.d(TAG, "partial wake lock unavailable: ${it.message}") }
        }
        if (wifiLock == null) {
            runCatching {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    ?: error("WifiManager unavailable")
                @Suppress("DEPRECATION")
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure { AppLogger.d(TAG, "Wi-Fi high-performance lock unavailable: ${it.message}") }
        }
    }

    private fun releaseWakeLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            .onFailure { AppLogger.d(TAG, "error releasing the wake lock: ${it.message}") }
        wakeLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
            .onFailure { AppLogger.d(TAG, "error releasing the Wi-Fi lock: ${it.message}") }
        wifiLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_CONNECT
        if (action == ACTION_CONNECT || action == VpnService.SERVICE_INTERFACE) {
            if (_state.value != ConnectionState.DISCONNECTED) {
                AppLogger.d(TAG, "ignoring duplicate connect request while ${_state.value}")
                enterForeground(statusLabel)
                return START_STICKY
            }
            if (intent == null) {
                AppLogger.i(TAG, "the service was restarted by the system; bringing the tunnel back up")
            }
            _state.value = ConnectionState.CONNECTING
            _lastError.value = null
            statusLabel = s(R.string.notif_connecting)
            enterForeground(statusLabel)

            acquireWakeLocks()
            connectJob = scope.launch { opMutex.withLock { connect() } }

            return START_STICKY
        } else if (action == ACTION_DISCONNECT) {
            requestDisconnect("requested by the user")
        }
        return START_NOT_STICKY
    }

    private fun requestDisconnect(reason: String) {
        notificationProxyOnly = false
        val idle = _state.value == ConnectionState.DISCONNECTED &&
            connectJob == null &&
            watchdogJob == null
        if (idle) {
            releaseWakeLocks()
            stopSelf()
            return
        }
        AppLogger.i(TAG, "disconnect: $reason")

        val endedGeneration = ++connectionGeneration
        _state.value = ConnectionState.DISCONNECTED
        _vpnLiveStats.value = VpnLiveStats()
        _connectedAtEpochMs.value = null
        _pingLatencyMs.value = null
        _vpnEdgeIp.value = null
        _connectedEndpoint.value = null
        connectJob?.cancel()
        connectJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        exitForeground()

        releaseWakeLocks()

        val doomed = detachResources()

        scope.launch {
            opMutex.withLock {
                val stillOurs = connectionGeneration == endedGeneration
                releaseResources(doomed, stopNativeTunnel = stillOurs)

                if (stillOurs && _state.value == ConnectionState.DISCONNECTED) {
                    stopSelf()
                }
            }
        }
    }

    private fun ensureGenerationCurrent(myGeneration: Int) {
        if (myGeneration != connectionGeneration) {
            throw CancellationException("connect was superseded by a newer request")
        }
    }

    private fun firstUsableNetwork(): Network? {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val networks = runCatching { manager.allNetworks }.getOrDefault(emptyArray())
        var unvalidated: Network? = null
        for (network in networks) {
            val capabilities = manager.getNetworkCapabilities(network) ?: continue

            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return network
            if (unvalidated == null) unvalidated = network
        }
        return unvalidated
    }

    private fun hasUsableNetwork(): Boolean {
        getSystemService(ConnectivityManager::class.java) ?: return true
        return firstUsableNetwork() != null
    }

    private suspend fun connect() {
        val myGeneration = ++connectionGeneration

        lastUnhealthyRedialAt = 0L
        boundNetwork = null

        runCatching {
            AppLogger.i(TAG, "connect: starting")
            val app = applicationContext as IVeePNApp
            val settingsStore = app.settingsStore
            val region = settingsStore.lastRegion
            val socksPort = settingsStore.socksPort
            val socksBindAddress = settingsStore.socksBindAddress
            val httpProxyPort = settingsStore.httpProxyPort
            val httpProxyEnabled = settingsStore.httpProxyEnabled
            var proxyOnlyMode = settingsStore.proxyOnlyMode
            val excludedApps = settingsStore.excludedApps
            val dohUrl = settingsStore.effectiveDohUrl

            AppLogger.i(TAG, "connect: region=$region proxyOnly=$proxyOnlyMode")

            fun connectedLabel(): String = if (proxyOnlyMode) {
                if (httpProxyEnabled) {
                    s(R.string.notif_proxy_http_format, socksPort, httpProxyPort)
                } else {
                    s(R.string.notif_proxy_format, socksBindAddress, socksPort)
                }
            } else {
                val loc = app.selectedLocationStore.load()
                val name = loc?.let {
                    listOf(it.cityName, it.countryName).filter { s -> s.isNotBlank() }.joinToString(", ")
                }.orEmpty()
                s(R.string.notif_connected_format, name.ifBlank { region.uppercase() })
            }

            val mapdnsActive = !proxyOnlyMode && dohUrl == null
            val socks = LocalSocks5Server(
                socksBindAddress,
                socksPort,
                mapdnsActive,
                dohUrl,
                { upstreamSession },
            ) {
                onUpstreamSessionUnhealthy()
            }
            socks.start()
            socksServer = socks

            if (httpProxyEnabled) {
                val http = LocalHttpProxyServer(
                    socksBindAddress,
                    httpProxyPort,
                    { upstreamSession },
                ) {
                    onUpstreamSessionUnhealthy()
                }
                http.start()
                httpProxyServer = http
            }
            ensureGenerationCurrent(myGeneration)

            if (proxyOnlyMode) {
                tunnelInterfaceActive = false
                AppLogger.i(
                    TAG,
                    "connect: proxy-only mode enabled, skipping TUN interface and tun2socks; " +
                        "local proxy at $socksBindAddress:$socksPort",
                )
            } else {
                val fd = establishTun(excludedApps, dohUrl, mtu = settingsStore.mtu)
                if (fd == null) {
                    // F-12: explicit TUN failure behaviour. The default is a
                    // LOUD fallback to proxy-only mode; with the fallback off
                    // the connect fails instead of leaking traffic.
                    if (!settingsStore.tunFallbackToProxy) {
                        error(s(R.string.service_err_tun_failed))
                    }
                    AppLogger.e(
                        TAG,
                        "TUN interface could not be established; falling back to proxy-only mode " +
                            "(only apps configured to use the local proxy are protected)",
                    )
                    _lastError.value = s(R.string.notif_tun_fallback, socksBindAddress, socksPort)
                    proxyOnlyMode = true
                    tunnelInterfaceActive = false
                } else {
                    tunFd = fd
                    tunnelInterfaceActive = true

                    reportedUnderlying = null
                    runCatching {
                        firstUsableNetwork()?.let { underlying -> reportUnderlyingNetwork(underlying) }
                    }

                    val tunnelConfigPath = HevSocks5TunnelConfig.write(
                        applicationContext,
                        socksPort,
                        useMapDns = mapdnsActive,
                        mtu = settingsStore.mtu,
                    )
                    val tunnelStarted = HevSocks5Tunnel.start(tunnelConfigPath, fd.fd)
                    if (!tunnelStarted) error(s(R.string.service_err_tunnel_start))
                    AppLogger.i(TAG, "connect: TUN interface up, hev-socks5-tunnel started")
                    delay(POST_TUN_SETTLE_MS)
                }
            }
            notificationProxyOnly = proxyOnlyMode
            ensureGenerationCurrent(myGeneration)

            suspend fun dialUpstream(): VeePNSession {
                val session = VeePNSession(app.veePNApi, region)
                try {
                    session.connect()
                } catch (failure: Throwable) {
                    runCatching { session.close() }
                    throw failure
                }
                AppLogger.i(TAG, "connect: upstream session ready for region $region")
                return session
            }

            var dialAttempt = 0
            var lastDialFailure: Throwable? = null
            while (true) {
                dialAttempt++
                ensureGenerationCurrent(myGeneration)

                val dialResult = try {
                    withTimeout(CONNECT_TIMEOUT_MS) { dialUpstream() }
                } catch (cancellation: CancellationException) {
                    if (cancellation !is TimeoutCancellationException) throw cancellation
                    lastDialFailure = cancellation
                    AppLogger.w(TAG, "dial timed out (attempt $dialAttempt)")
                    null
                } catch (error: Throwable) {
                    lastDialFailure = error
                    AppLogger.w(TAG, "dial failed (attempt $dialAttempt): ${error.message}")
                    null
                }

                if (dialResult != null) {
                    if (myGeneration != connectionGeneration) {
                        runCatching { dialResult.close() }
                        ensureGenerationCurrent(myGeneration)
                    }
                    upstreamSession = dialResult
                    delay(INITIAL_DIAL_SETTLE_MS)
                    if (upstreamSession?.isConnected == true) break
                    AppLogger.w(TAG, "upstream session died immediately after connecting (attempt $dialAttempt/$INITIAL_DIAL_MAX_ATTEMPTS)")
                    runCatching { upstreamSession?.close() }
                    upstreamSession = null
                    lastDialFailure = null
                }

                if (dialAttempt >= INITIAL_DIAL_MAX_ATTEMPTS) {
                    val failure = lastDialFailure
                    if (failure != null) {
                        error(s(R.string.service_err_unreachable, dialAttempt))
                    }
                    error(s(R.string.service_err_session_closed, dialAttempt))
                }

                delay(fullJitterBackoffMs(dialAttempt - 1, INITIAL_DIAL_BACKOFF_BASE_MS, INITIAL_DIAL_BACKOFF_CAP_MS))
            }

            ensureGenerationCurrent(myGeneration)

            val connectedText = connectedLabel()
            _connectedAtEpochMs.value = System.currentTimeMillis()
            _state.value = ConnectionState.CONNECTED
            statusLabel = connectedText
            updateNotification(connectedText)
            AppLogger.i(TAG, "connect: CONNECTED (region $region)")

            val liveSession = upstreamSession as? VeePNSession
            val endpoint = liveSession?.currentServers()?.firstOrNull()
                ?.let { server -> server.addresses.firstOrNull()?.let { host -> host to server.port } }
            _connectedEndpoint.value = endpoint
            if (endpoint != null) {
                scope.launch {
                    val ping = PingUtil.measureTcpLatencyMs(endpoint.first, endpoint.second)
                    if (myGeneration == connectionGeneration) {
                        _pingLatencyMs.value = ping
                    }
                }
            }

            if (settingsStore.exitCheckEnabled) {
                scope.launch {
                    val ip = runCatching { liveSession?.probeExitIp() }.getOrNull()
                    if (ip != null && myGeneration == connectionGeneration) {
                        val cc = app.selectedLocationStore.load()?.countryCode.orEmpty()
                        _vpnEdgeIp.value = IpLocationInfo(ip, cc)
                        AppLogger.i(TAG, "exit check: observed exit ip=$ip")
                    }
                }
            }

            if (!proxyOnlyMode) {
                startSpeedUpdates()
            }

            watchdogJob = scope.launch {
                var consecutiveFailures = 0
                var waitingForNetwork = false

                while (isActive) {
                    delay(UPSTREAM_WATCHDOG_INTERVAL_MS)
                    val current = upstreamSession
                    if (current != null && current.isConnected) {
                        if (consecutiveFailures > 0 || waitingForNetwork) {
                            AppLogger.i(TAG, "upstream session is healthy again")
                            _lastError.value = null
                            statusLabel = connectedLabel()
                            updateNotification(statusLabel)
                        }
                        consecutiveFailures = 0
                        waitingForNetwork = false
                        continue
                    }

                    if (!hasUsableNetwork()) {
                        if (!waitingForNetwork) {
                            AppLogger.i(TAG, "no usable network; holding the session and waiting for connectivity")
                            waitingForNetwork = true
                            statusLabel = s(R.string.notif_waiting_network)
                            updateNotification(statusLabel)
                        }
                        consecutiveFailures = 0
                        continue
                    }
                    if (waitingForNetwork) {
                        AppLogger.i(TAG, "network is back; redialing the upstream session")
                        waitingForNetwork = false
                    }

                    AppLogger.w(TAG, "upstream session is down; attempting to reconnect")
                    runCatching { current?.close() }
                    if (upstreamSession === current) upstreamSession = null
                    statusLabel = s(R.string.notif_reconnecting)
                    updateNotification(statusLabel)

                    val fresh = try {
                        withTimeout(CONNECT_TIMEOUT_MS) { dialUpstream() }
                    } catch (cancellation: CancellationException) {
                        if (cancellation !is TimeoutCancellationException) throw cancellation
                        consecutiveFailures++
                        AppLogger.w(TAG, "upstream reconnect attempt $consecutiveFailures timed out")
                        null
                    } catch (error: Throwable) {
                        consecutiveFailures++
                        AppLogger.w(TAG, "upstream reconnect attempt $consecutiveFailures failed: ${error.message}")
                        null
                    }

                    if (fresh == null) {
                        if (consecutiveFailures == RECONNECT_FAILURES_BEFORE_WARNING) {
                            _lastError.value = s(R.string.service_err_reconnecting)
                            AppLogger.e(
                                TAG,
                                "$consecutiveFailures consecutive reconnect failures; continuing to retry with backoff " +
                                    "(capped at ${RECONNECT_BACKOFF_CAP_MS / 1_000}s between attempts)",
                            )
                        }
                        delay(fullJitterBackoffMs(consecutiveFailures - 1, RECONNECT_BACKOFF_BASE_MS, RECONNECT_BACKOFF_CAP_MS))
                        continue
                    }

                    if (myGeneration != connectionGeneration) {
                        AppLogger.i(TAG, "discarding stale reconnect from a torn-down session generation")
                        runCatching { fresh.close() }
                        return@launch
                    }

                    upstreamSession = fresh
                    consecutiveFailures = 0
                    _lastError.value = null

                    statusLabel = connectedLabel()
                    updateNotification(statusLabel)
                    AppLogger.i(TAG, "upstream session reconnected (region $region)")
                }
            }
        }.onFailure { failure ->
            if (failure is CancellationException) {
                AppLogger.i(TAG, "connect: aborted because the connection was cancelled")
                releaseResources(detachResources(), stopNativeTunnel = true)
                return@onFailure
            }
            val message = if (failure is TimeoutCancellationException) {
                s(R.string.err_timeout)
            } else {
                UiErrors.friendlyMessage(loc(), failure)
            }
            AppLogger.e(TAG, "connect failed", failure)
            _lastError.value = message
            teardown()
            exitForeground()
            releaseWakeLocks()
            stopSelf()
        }
        connectJob = null
    }

    private fun establishTun(
        excludedApps: Set<String>,
        dohUrl: String?,
        mtu: Int = HevSocks5TunnelConfig.TUN_MTU,
    ): ParcelFileDescriptor? {
        val dnsServer = HevSocks5TunnelConfig.MAPDNS_ADDRESS
        if (dohUrl != null) {
            AppLogger.i(TAG, "establishTun: dns=DoH ($dohUrl)")
        } else {
            AppLogger.i(TAG, "establishTun: dns=mapdns ($dnsServer)")
        }
        val builder = Builder()
            .setSession("iVeePN")
            .addAddress(HevSocks5TunnelConfig.TUN_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)

        val ipv6Address = runCatching {
            builder.addAddress(TUN_ADDRESS_IPV6, TUN_PREFIX_LENGTH_IPV6)
        }.onFailure {
            AppLogger.w(
                TAG,
                "could not add an IPv6 address to the TUN interface; IPv6 will be captured by the " +
                    "tunnel's default route and dropped rather than sent in the clear",
                it,
            )
        }

        // Fail closed: a failed IPv6 default route must refuse the TUN
        // instead of leaking IPv6 traffic outside the tunnel.
        val ipv6Route = runCatching { builder.addRoute("::", 0) }
        if (ipv6Route.isFailure) {
            AppLogger.e(
                TAG,
                "could not claim the IPv6 default route; refusing to establish the tunnel " +
                    "instead of leaking IPv6 traffic outside it",
                ipv6Route.exceptionOrNull(),
            )
            return null
        }
        if (ipv6Address.isFailure) {
            AppLogger.i(TAG, "establishTun: IPv6 is routed into the tunnel and blackholed (no IPv6 address)")
        }

        return builder
            .addDnsServer(dnsServer)
            .setMtu(mtu)
            .setBlocking(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching { setMetered(false) }
                        .onFailure { AppLogger.d(TAG, "could not mark the tunnel as unmetered: ${it.message}") }
                }
            }
            .apply {
                runCatching { addDisallowedApplication(packageName) }
                    .onFailure { AppLogger.w(TAG, "failed to exclude own app from the VPN tunnel", it) }
                for (excludedPackage in excludedApps) {
                    runCatching { addDisallowedApplication(excludedPackage) }
                        .onFailure { AppLogger.w(TAG, "failed to exclude $excludedPackage from the VPN tunnel (app may be uninstalled)", it) }
                }
            }
            .establish()
    }

    private fun teardown() {
        connectionGeneration++
        watchdogJob?.cancel()
        watchdogJob = null
        releaseResources(detachResources(), stopNativeTunnel = true)
        _state.value = ConnectionState.DISCONNECTED
        _vpnLiveStats.value = VpnLiveStats()
        _connectedAtEpochMs.value = null
        _pingLatencyMs.value = null
        _vpnEdgeIp.value = null
        _connectedEndpoint.value = null
    }

    private fun detachResources(): SessionResources {
        speedJob?.cancel()
        speedJob = null
        val detached = SessionResources(tunFd, socksServer, httpProxyServer, upstreamSession)
        tunFd = null
        tunnelInterfaceActive = false
        socksServer = null
        httpProxyServer = null
        upstreamSession = null
        return detached
    }

    private fun releaseResources(resources: SessionResources, stopNativeTunnel: Boolean) {
        if (resources.isEmpty && !stopNativeTunnel) return
        if (stopNativeTunnel) {
            runCatching { HevSocks5Tunnel.stop() }.onFailure { AppLogger.w(TAG, "error stopping tun2socks tunnel", it) }
        }
        runCatching { resources.socksServer?.stop() }.onFailure { AppLogger.w(TAG, "error stopping local SOCKS5 server", it) }
        runCatching { resources.httpProxyServer?.stop() }.onFailure { AppLogger.w(TAG, "error stopping local HTTP proxy server", it) }
        runCatching { resources.upstreamSession?.close() }.onFailure { AppLogger.w(TAG, "error closing upstream session", it) }
        runCatching { resources.tunFd?.close() }.onFailure { AppLogger.w(TAG, "error closing TUN interface", it) }
    }

    override fun onRevoke() {
        requestDisconnect(s(R.string.service_err_revoked))
        super.onRevoke()
    }

    private fun startSpeedUpdates() {
        speedJob?.cancel()
        speedJob = scope.launch {
            var lastStats = HevSocks5Tunnel.stats()
            var lastSampleAt = System.currentTimeMillis()
            while (isActive) {
                delay(SPEED_UPDATE_INTERVAL_MS)
                val stats = HevSocks5Tunnel.stats()
                val now = System.currentTimeMillis()
                val elapsedSeconds = ((now - lastSampleAt).coerceAtLeast(1)).toDouble() / 1_000.0

                val txRate = ((stats[1] - lastStats[1]).coerceAtLeast(0) / elapsedSeconds).toLong()
                val rxRate = ((stats[3] - lastStats[3]).coerceAtLeast(0) / elapsedSeconds).toLong()
                val totalSessionBytes = (stats[1] + stats[3]).coerceAtLeast(0L)
                lastStats = stats
                lastSampleAt = now
                if (_state.value != ConnectionState.CONNECTED) continue
                _vpnLiveStats.value = VpnLiveStats(
                    txRateBytesPerSec = txRate,
                    rxRateBytesPerSec = rxRate,
                    sessionBytes = totalSessionBytes,
                )
                updateNotification(
                    statusLabel,
                    s(
                        R.string.notif_speed_format,
                        formatBytesPerSecond(rxRate),
                        formatBytesPerSecond(txRate),
                    ),
                )
            }
        }
    }

    private fun buildNotification(text: String, subText: String? = null): Notification {
        val openIntent = PendingIntent.getActivity(
            this, REQUEST_CODE_OPEN, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val disconnectIntent = PendingIntent.getService(
            this, REQUEST_CODE_DISCONNECT, Intent(this, IVeePNVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, IVeePNApp.VPN_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(
                if (notificationProxyOnly) s(R.string.notif_proxy_only_title) else s(R.string.app_name),
            )
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setLocalOnly(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (subText != null) "$text\n$subText" else text,
                ),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                s(R.string.notif_disconnect),
                disconnectIntent,
            )
        return builder.build()
    }

    private fun enterForeground(text: String) {
        foregroundActive = true
        lastNotificationText = text
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun exitForeground() {
        foregroundActive = false
        lastNotificationText = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun updateNotification(text: String, subText: String? = null) {
        if (!foregroundActive) return
        val key = if (subText == null) text else "$text\n$subText"
        if (key == lastNotificationText) return
        lastNotificationText = key
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text, subText)) }
    }

    companion object {
        const val ACTION_CONNECT = "com.pishi.iveepn.action.CONNECT"
        const val ACTION_DISCONNECT = "com.pishi.iveepn.action.DISCONNECT"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_CODE_OPEN = 0
        private const val REQUEST_CODE_DISCONNECT = 1

        private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
        val state: StateFlow<ConnectionState> = _state

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError

        private val _vpnLiveStats = MutableStateFlow(VpnLiveStats())
        val vpnLiveStats: StateFlow<VpnLiveStats> = _vpnLiveStats

        private val _connectedAtEpochMs = MutableStateFlow<Long?>(null)
        val connectedAtEpochMs: StateFlow<Long?> = _connectedAtEpochMs

        private val _pingLatencyMs = MutableStateFlow<Int?>(null)
        val pingLatencyMs: StateFlow<Int?> = _pingLatencyMs

        private val _vpnEdgeIp = MutableStateFlow<IpLocationInfo?>(null)
        val vpnEdgeIp: StateFlow<IpLocationInfo?> = _vpnEdgeIp

        private val _connectedEndpoint = MutableStateFlow<Pair<String, Int>?>(null)
        val connectedEndpoint: StateFlow<Pair<String, Int>?> = _connectedEndpoint

        suspend fun pingNow(): Int? {
            val endpoint = _connectedEndpoint.value ?: return null
            val latency = PingUtil.measureTcpLatencyMs(endpoint.first, endpoint.second)
            if (_state.value == ConnectionState.CONNECTED) {
                _pingLatencyMs.value = latency
            }
            return latency
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, IVeePNVpnService::class.java).setAction(ACTION_CONNECT))
        }

        fun stop(context: Context) {
            val intent = Intent(context, IVeePNVpnService::class.java).setAction(ACTION_DISCONNECT)
            runCatching { context.startService(intent) }
                .onFailure {
                    AppLogger.w(TAG, "could not deliver the disconnect intent; the service is not running", it)
                }
        }
    }
}

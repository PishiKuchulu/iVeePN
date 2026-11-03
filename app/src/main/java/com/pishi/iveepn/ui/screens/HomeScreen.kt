// Placeholder UI — original iVeePN design lands in P4
package com.pishi.iveepn.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pishi.iveepn.IVeePNApp
import com.pishi.iveepn.data.formatBytes
import com.pishi.iveepn.data.model.ConnectionState
import com.pishi.iveepn.data.model.countryCodeToEmojiFlag
import com.pishi.iveepn.vpn.ExitCheck
import com.pishi.iveepn.vpn.IVeePNVpnService

/**
 * Functional wiring shell: connection state, connect/disconnect, the
 * selected location, live stats and the exit/direct IP readouts.
 */
@Composable
fun HomeScreen(
    app: IVeePNApp,
    onNavigateToServers: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onRequestConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val state by IVeePNVpnService.state.collectAsState()
    val lastError by IVeePNVpnService.lastError.collectAsState()
    val stats by IVeePNVpnService.vpnLiveStats.collectAsState()
    val pingMs by IVeePNVpnService.pingLatencyMs.collectAsState()
    val exitIp by IVeePNVpnService.vpnEdgeIp.collectAsState()
    val selected by app.selectedLocationStore.selectedLocationFlow.collectAsState()

    var directIp by remember { mutableStateOf(app.settingsStore.cachedRealIp) }
    LaunchedEffect(state) {
        if (state == ConnectionState.DISCONNECTED) {
            ExitCheck().fetchDirectIpInfo().onSuccess { info ->
                directIp = info.ip
                app.settingsStore.cachedRealIp = info.ip
                app.settingsStore.cachedRealCountry = info.countryCode
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("iVeePN", style = MaterialTheme.typography.headlineMedium)
        Text(
            when (state) {
                ConnectionState.CONNECTED -> "Connected"
                ConnectionState.CONNECTING -> "Connecting…"
                ConnectionState.DISCONNECTED -> "Disconnected"
            },
            style = MaterialTheme.typography.titleLarge,
        )

        val locationLabel = selected?.let { loc ->
            val flag = countryCodeToEmojiFlag(loc.countryCode)
            listOf(flag, loc.cityName, loc.countryName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { loc.region.uppercase() }
        } ?: "No location selected (default: Netherlands)"
        Text("Location: $locationLabel", style = MaterialTheme.typography.bodyLarge)

        if (state == ConnectionState.CONNECTED) {
            Text("Session traffic: ${formatBytes(stats.sessionBytes)}")
            Text("↓ ${formatBytes(stats.rxRateBytesPerSec)}/s · ↑ ${formatBytes(stats.txRateBytesPerSec)}/s")
            pingMs?.let { Text("Ping: ${it} ms") }
            exitIp?.let { Text("Exit IP: ${it.ip} (${it.countryCode})") }
        } else {
            directIp?.let { Text("Your IP: $it") }
        }

        lastError?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(8.dp))
        when (state) {
            ConnectionState.CONNECTED, ConnectionState.CONNECTING -> {
                Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Text("Disconnect")
                }
            }
            ConnectionState.DISCONNECTED -> {
                Button(onClick = onRequestConnect, modifier = Modifier.fillMaxWidth()) {
                    Text("Connect")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onNavigateToServers, modifier = Modifier.weight(1f)) {
                Text("Locations")
            }
            OutlinedButton(onClick = onNavigateToSettings, modifier = Modifier.weight(1f)) {
                Text("Settings")
            }
            OutlinedButton(onClick = onNavigateToLogs, modifier = Modifier.weight(1f)) {
                Text("Logs")
            }
        }

        if (app.settingsStore.proxyOnlyMode) {
            Text(
                "Proxy-only mode is on: apps must use the local proxy " +
                    "(${app.settingsStore.socksBindAddress}:${app.settingsStore.socksPort}).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

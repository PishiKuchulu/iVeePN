package com.pishi.iveepn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.pishi.iveepn.IVeePNApp
import com.pishi.iveepn.R
import com.pishi.iveepn.data.SettingsStore
import com.pishi.iveepn.data.formatBytes
import com.pishi.iveepn.data.model.ConnectionState
import com.pishi.iveepn.data.model.countryCodeToEmojiFlag
import com.pishi.iveepn.ui.components.VpnButtonState
import com.pishi.iveepn.ui.components.VpnConnectButton
import com.pishi.iveepn.ui.theme.LocalIVeePNStatusColors
import com.pishi.iveepn.ui.theme.Spacing
import com.pishi.iveepn.util.UpdateInfo
import com.pishi.iveepn.vpn.ExitCheck
import com.pishi.iveepn.vpn.IVeePNVpnService
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Home — direction #2 "Modern Premium": a single centred column capped at
 * [Spacing.contentMaxWidth] with the power button as the only primary
 * action, semantic status colours, and contextual cards (update, proxy-only,
 * LAN exposure, location, live stats, error) around it.
 */
@Composable
fun HomeScreen(
    app: IVeePNApp,
    onNavigateToServers: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onRequestConnect: () -> Unit,
    onDisconnect: () -> Unit,
    updateInfo: UpdateInfo?,
    onUpdateClick: () -> Unit,
) {
    val state by IVeePNVpnService.state.collectAsState()
    val lastError by IVeePNVpnService.lastError.collectAsState()
    val stats by IVeePNVpnService.vpnLiveStats.collectAsState()
    val pingMs by IVeePNVpnService.pingLatencyMs.collectAsState()
    val endpoint by IVeePNVpnService.connectedEndpoint.collectAsState()
    val exitIp by IVeePNVpnService.vpnEdgeIp.collectAsState()
    val connectedAtEpochMs by IVeePNVpnService.connectedAtEpochMs.collectAsState()
    val selected by app.selectedLocationStore.selectedLocationFlow.collectAsState()

    val statusColors = LocalIVeePNStatusColors.current
    val statusColor = when (state) {
        ConnectionState.CONNECTED -> statusColors.connected
        ConnectionState.CONNECTING -> statusColors.connecting
        ConnectionState.DISCONNECTED -> statusColors.disconnected
    }
    val statusText = stringResource(
        when (state) {
            ConnectionState.CONNECTED -> R.string.status_connected
            ConnectionState.CONNECTING -> R.string.status_connecting
            ConnectionState.DISCONNECTED -> R.string.status_disconnected
        },
    )

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

    // Connecting elapsed ticker (seconds since this CONNECTING phase began).
    var connectingElapsedSec by remember { mutableIntStateOf(0) }
    LaunchedEffect(state) {
        if (state == ConnectionState.CONNECTING) {
            connectingElapsedSec = 0
            while (true) {
                delay(1000)
                connectingElapsedSec++
            }
        }
    }

    val locationLabel = selected?.let { loc ->
        val flag = countryCodeToEmojiFlag(loc.countryCode)
        listOf(flag, loc.cityName, loc.countryName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { loc.region.uppercase() }
    } ?: stringResource(R.string.home_location_default)

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = Spacing.contentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenPadding, vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(statusColor, CircleShape),
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleLarge,
                        color = statusColor,
                        modifier = Modifier.semantics { stateDescription = statusText },
                    )
                }

                if (updateInfo != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(Spacing.cardPadding),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            Icon(Icons.Filled.ArrowDownward, contentDescription = null)
                            Text(
                                text = stringResource(
                                    R.string.update_banner_text,
                                    updateInfo.version,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = onUpdateClick,
                                modifier = Modifier.heightIn(min = Spacing.touchTarget),
                            ) {
                                Text(stringResource(R.string.update_banner_action))
                            }
                        }
                    }
                }

                if (app.settingsStore.proxyOnlyMode) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(Spacing.cardPadding),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            Icon(Icons.Filled.WarningAmber, contentDescription = null)
                            Column(
                                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                            ) {
                                Text(
                                    text = stringResource(R.string.home_proxy_only_title),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.home_proxy_only_message,
                                        app.settingsStore.socksBindAddress,
                                        app.settingsStore.socksPort,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                if (!SettingsStore.isLoopbackBind(app.settingsStore.socksBindAddress)) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(Spacing.cardPadding),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            Icon(Icons.Filled.Lan, contentDescription = null)
                            Column(
                                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                            ) {
                                Text(
                                    text = stringResource(R.string.home_lan_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = statusColors.warning,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.home_lan_message,
                                        app.settingsStore.socksBindAddress,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                VpnConnectButton(
                    state = when (state) {
                        ConnectionState.DISCONNECTED -> VpnButtonState.Disconnected
                        ConnectionState.CONNECTING -> VpnButtonState.Connecting
                        ConnectionState.CONNECTED -> VpnButtonState.Connected
                    },
                    onClick = if (state == ConnectionState.DISCONNECTED) {
                        onRequestConnect
                    } else {
                        onDisconnect
                    },
                    connectedAtEpochMs = connectedAtEpochMs,
                    disconnectedTitle = stringResource(R.string.status_disconnected),
                    disconnectedSubtitle = stringResource(R.string.home_tap_to_connect),
                    connectingTitle = stringResource(R.string.status_connecting),
                    connectingSubtitle = stringResource(R.string.home_please_wait),
                    stateDesc = statusText,
                )

                if (state == ConnectionState.CONNECTING) {
                    Text(
                        text = stringResource(R.string.home_connecting_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.home_connecting_elapsed,
                            formatElapsedMmSs(connectingElapsedSec),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Card(
                    onClick = onNavigateToServers,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.touchTarget),
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.cardPadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.home_location_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = locationLabel,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    }
                }

                if (state == ConnectionState.CONNECTED) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(Spacing.cardPadding),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.home_stat_traffic,
                                    formatBytes(stats.sessionBytes),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(
                                    R.string.home_speed_format,
                                    formatBytes(stats.rxRateBytesPerSec),
                                    formatBytes(stats.txRateBytesPerSec),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            pingMs?.let {
                                Text(
                                    text = stringResource(R.string.home_ping_format, it),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            // V-13 (partial): the current upstream server,
                            // from the endpoint flow the service already
                            // publishes. The pool's breaker state itself is
                            // not exposed by ServerPool, so only the active
                            // server is shown.
                            endpoint?.let {
                                Text(
                                    text = stringResource(
                                        R.string.home_server_format,
                                        it.first,
                                        it.second,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            exitIp?.let {
                                Text(
                                    text = stringResource(
                                        R.string.home_exit_ip_format,
                                        it.ip,
                                        it.countryCode,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                } else if (state == ConnectionState.DISCONNECTED && directIp != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.home_your_ip_format, directIp!!),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(Spacing.cardPadding),
                        )
                    }
                }

                val err = lastError?.takeIf { it.isNotBlank() }
                var dismissed by remember { mutableStateOf<String?>(null) }
                if (err != null && err != dismissed) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.cardPadding),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                Icon(
                                    Icons.Filled.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    text = stringResource(R.string.home_error_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = onRequestConnect,
                                    modifier = Modifier.heightIn(min = Spacing.touchTarget),
                                ) {
                                    Text(stringResource(R.string.common_retry))
                                }
                                TextButton(
                                    onClick = onNavigateToLogs,
                                    modifier = Modifier.heightIn(min = Spacing.touchTarget),
                                ) {
                                    Text(stringResource(R.string.home_error_view_logs))
                                }
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { dismissed = err }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.cd_dismiss),
                                    )
                                }
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(
                        onClick = onNavigateToServers,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Spacing.touchTarget),
                    ) {
                        Text(stringResource(R.string.home_nav_locations))
                    }
                    OutlinedButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Spacing.touchTarget),
                    ) {
                        Text(stringResource(R.string.home_nav_settings))
                    }
                    OutlinedButton(
                        onClick = onNavigateToLogs,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Spacing.touchTarget),
                    ) {
                        Text(stringResource(R.string.home_nav_logs))
                    }
                }
            }
        }
    }
}

private fun formatElapsedMmSs(totalSec: Int): String =
    String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)


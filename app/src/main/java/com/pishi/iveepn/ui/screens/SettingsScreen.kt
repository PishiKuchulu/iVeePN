// Placeholder UI — original iVeePN design lands in P4
package com.pishi.iveepn.ui.screens

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pishi.iveepn.BuildConfig
import com.pishi.iveepn.IVeePNApp
import com.pishi.iveepn.data.AppLogger
import com.pishi.iveepn.data.SettingsStore
import com.pishi.iveepn.ui.components.AppUpdateController
import com.pishi.iveepn.ui.components.UpdateUiState
import com.pishi.iveepn.ui.theme.ThemeController
import com.pishi.iveepn.ui.theme.ThemeMode
import com.pishi.iveepn.vpn.upstream.VeePNSession
import kotlinx.coroutines.launch

/**
 * Functional wiring shell: every row here drives a real setting consumed by
 * the VPN service or the UI, including the F-06 live chain test.
 */
@Composable
fun SettingsScreen(
    app: IVeePNApp,
    themeController: ThemeController,
    updateController: AppUpdateController,
    onNavigateToLogs: () -> Unit,
    onBack: () -> Unit,
) {
    val settings = app.settingsStore
    val scope = rememberCoroutineScope()

    var exitCheck by remember { mutableStateOf(settings.exitCheckEnabled) }
    var proxyOnly by remember { mutableStateOf(settings.proxyOnlyMode) }
    var tunFallback by remember { mutableStateOf(settings.tunFallbackToProxy) }
    var httpProxy by remember { mutableStateOf(settings.httpProxyEnabled) }
    var dnsProvider by remember { mutableStateOf(settings.dnsProvider) }

    var chainRunning by remember { mutableStateOf(false) }
    var chainReport by remember { mutableStateOf<String?>(null) }
    var numberDialog by remember { mutableStateOf<NumberField?>(null) }
    var textDialog by remember { mutableStateOf<TextField?>(null) }
    var showExcludedApps by remember { mutableStateOf(false) }
    var showDnsPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onBack) { Text("Back") }
        }

        SectionTitle("Connection")
        Button(
            onClick = {
                chainRunning = true
                scope.launch {
                    chainReport = runChainTest(app)
                    chainRunning = false
                }
            },
            enabled = !chainRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (chainRunning) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            }
            Text("Test connection chain")
        }
        Text(
            "Runs guest token → locations → server list → exit IP against the live VeePN network.",
            style = MaterialTheme.typography.bodySmall,
        )
        SwitchRow("Verify exit location", exitCheck) {
            exitCheck = it
            settings.exitCheckEnabled = it
        }
        SwitchRow("Proxy-only mode (no TUN)", proxyOnly) {
            proxyOnly = it
            settings.proxyOnlyMode = it
        }
        SwitchRow("On TUN failure, fall back to proxy-only", tunFallback) {
            tunFallback = it
            settings.tunFallbackToProxy = it
        }
        ValueRow("Tunnel MTU", settings.mtu.toString()) {
            numberDialog = NumberField("Tunnel MTU", settings.mtu, SettingsStore.MIN_MTU, SettingsStore.MAX_MTU) {
                settings.mtu = it
            }
        }
        ValueRow("Selected region", settings.lastRegion.uppercase()) {}

        SectionTitle("Appearance")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                val selected = themeController.mode == mode
                if (selected) {
                    Button(onClick = {}) { Text(mode.name.lowercase().replaceFirstChar { c -> c.uppercase() }) }
                } else {
                    TextButton(onClick = { themeController.set(mode) }) {
                        Text(mode.name.lowercase().replaceFirstChar { c -> c.uppercase() })
                    }
                }
            }
        }

        SectionTitle("DNS")
        ValueRow("Resolver (local proxy DNS)", dnsProvider.label) { showDnsPicker = true }
        if (dnsProvider == SettingsStore.DnsProvider.CUSTOM) {
            ValueRow("Custom DoH URL", settings.customDohUrl.ifBlank { "Not set" }) {
                textDialog = TextField("Custom DoH URL", settings.customDohUrl) { value ->
                    settings.customDohUrl = value
                }
            }
        }

        SectionTitle("Local proxy")
        ValueRow("SOCKS5 bind address", settings.socksBindAddress) {
            textDialog = TextField("SOCKS5 bind address", settings.socksBindAddress) { value ->
                if (SettingsStore.isValidIpAddress(value)) settings.socksBindAddress = value
            }
        }
        ValueRow("SOCKS5 port", settings.socksPort.toString()) {
            numberDialog = NumberField("SOCKS5 port", settings.socksPort, 1, 65_535) { settings.socksPort = it }
        }
        SwitchRow("HTTP proxy", httpProxy) {
            httpProxy = it
            settings.httpProxyEnabled = it
        }
        ValueRow("HTTP proxy port", settings.httpProxyPort.toString()) {
            numberDialog = NumberField("HTTP proxy port", settings.httpProxyPort, 1, 65_535) {
                settings.httpProxyPort = it
            }
        }

        SectionTitle("Split tunneling")
        ValueRow("Excluded apps", "${settings.excludedApps.size} selected") { showExcludedApps = true }

        SectionTitle("About")
        ValueRow("Version", BuildConfig.VERSION_NAME) {}
        ValueRow("Logs", "") { onNavigateToLogs() }
        ValueRow("App update", updateStateText(updateController.state)) {
            when (updateController.state) {
                is UpdateUiState.Failed -> updateController.showDialog()
                else -> updateController.check()
            }
        }
    }

    numberDialog?.let { field ->
        NumberEditDialog(field) { numberDialog = null }
    }
    textDialog?.let { field ->
        TextEditDialog(field) { textDialog = null }
    }
    if (showDnsPicker) {
        AlertDialog(
            onDismissRequest = { showDnsPicker = false },
            title = { Text("Resolver DNS") },
            text = {
                Column {
                    SettingsStore.DnsProvider.entries.forEach { provider ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    dnsProvider = provider
                                    settings.dnsProvider = provider
                                    showDnsPicker = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = provider == dnsProvider, onCheckedChange = null)
                            Text(provider.label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDnsPicker = false }) { Text("Close") }
            },
        )
    }
    if (showExcludedApps) {
        ExcludedAppsDialog(app) { showExcludedApps = false }
    }
    chainReport?.let { report ->
        AlertDialog(
            onDismissRequest = { chainReport = null },
            title = { Text("Connection chain test") },
            text = { Text(report) },
            confirmButton = {
                TextButton(onClick = { chainReport = null }) { Text("OK") }
            },
        )
    }
}

/**
 * F-06: the live chain test. Each step logs through AppLogger and the whole
 * report is shown in a dialog. Steps: guest token, locations count, server
 * list for the selected region, exit IP through a real tunnel.
 */
private suspend fun runChainTest(app: IVeePNApp): String {
    val lines = mutableListOf<String>()
    fun step(name: String, result: Result<String>) {
        val line = result.fold(
            onSuccess = { "✓ $name — $it" },
            onFailure = { "✗ $name — ${it.message ?: it.javaClass.simpleName}" },
        )
        lines += line
        AppLogger.i(CHAIN_TAG, line)
    }

    val region = app.settingsStore.lastRegion
    step("Guest token", runCatching {
        app.veePNApi.ensureToken(force = true)
        "acquired"
    })
    step("Locations", runCatching {
        val result = app.veePNApi.locations()
        "${result.all.size} total, ${result.free.size} free"
    })
    step("Server list ($region)", runCatching {
        val servers = app.veePNApi.serverList(region, forceRefresh = true)
        "${servers.size} usable servers"
    })
    val session = VeePNSession(app.veePNApi, region)
    try {
        step("Exit IP through tunnel", runCatching {
            session.connect()
            session.probeExitIp() ?: throw IllegalStateException("no IP returned by echo mirrors")
        })
    } finally {
        session.close()
    }
    return lines.joinToString("\n")
}

private const val CHAIN_TAG = "ChainTest"

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ValueRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private class NumberField(
    val title: String,
    val initial: Int,
    val min: Int,
    val max: Int,
    val onSave: (Int) -> Unit,
)

private class TextField(
    val title: String,
    val initial: String,
    val onSave: (String) -> Unit,
)

@Composable
private fun NumberEditDialog(field: NumberField, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(field.initial.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(field.title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() } },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                text.toIntOrNull()?.let { value ->
                    if (value in field.min..field.max) field.onSave(value)
                }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TextEditDialog(field: TextField, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(field.initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(field.title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = {
                field.onSave(text.trim())
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ExcludedAppsDialog(app: IVeePNApp, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val installed = remember {
        val pm = context.packageManager
        pm.getInstalledApplications(0)
            .filter { it.packageName != context.packageName }
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.enabled }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
    }
    var excluded by remember { mutableStateOf(app.settingsStore.excludedApps) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Excluded apps (bypass the tunnel)") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(installed, key = { it.first }) { (pkg, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                excluded = if (pkg in excluded) excluded - pkg else excluded + pkg
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = pkg in excluded, onCheckedChange = null)
                        Text("$label\n$pkg", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                app.settingsStore.excludedApps = excluded
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun updateStateText(state: UpdateUiState): String = when (state) {
    is UpdateUiState.Idle -> "Check for updates"
    is UpdateUiState.Checking -> "Checking…"
    is UpdateUiState.Available -> "Update available: v${state.info.version}"
    is UpdateUiState.UpToDate -> "Up to date"
    is UpdateUiState.Downloading -> "Downloading…"
    is UpdateUiState.ReadyToInstall -> "Ready to install"
    is UpdateUiState.Failed -> "Check failed"
}

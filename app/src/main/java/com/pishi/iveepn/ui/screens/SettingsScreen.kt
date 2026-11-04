package com.pishi.iveepn.ui.screens

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pishi.iveepn.BuildConfig
import com.pishi.iveepn.IVeePNApp
import com.pishi.iveepn.R
import com.pishi.iveepn.data.AppLanguage
import com.pishi.iveepn.data.AppLogger
import com.pishi.iveepn.data.SettingsStore
import com.pishi.iveepn.data.resolvedLanguageTag
import com.pishi.iveepn.ui.UiErrors
import com.pishi.iveepn.ui.components.AppUpdateController
import com.pishi.iveepn.ui.components.UpdateUiState
import com.pishi.iveepn.ui.theme.Spacing
import com.pishi.iveepn.ui.theme.ThemeController
import com.pishi.iveepn.ui.theme.ThemeMode
import com.pishi.iveepn.vpn.upstream.VeePNSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings — every row drives a real setting consumed by the VPN service
 * or the UI, including the F-06 live chain test. Redesigned with the rest
 * of the app: Scaffold with safe-drawing insets, the unified TopAppBar,
 * grouped cards, full string resources (EN/FA), ≥48dp targets, inline
 * dialog validation, and a LAN-exposure confirmation before a
 * non-loopback bind address is persisted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    app: IVeePNApp,
    themeController: ThemeController,
    updateController: AppUpdateController,
    onNavigateToLogs: () -> Unit,
    onNavigateToLocations: () -> Unit,
    onBack: () -> Unit,
) {
    val settings = app.settingsStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var exitCheck by remember { mutableStateOf(settings.exitCheckEnabled) }
    var proxyOnly by remember { mutableStateOf(settings.proxyOnlyMode) }
    var tunFallback by remember { mutableStateOf(settings.tunFallbackToProxy) }
    var httpProxy by remember { mutableStateOf(settings.httpProxyEnabled) }
    var dnsProvider by remember { mutableStateOf(settings.dnsProvider) }
    var language by remember { mutableStateOf(settings.appLanguage) }

    var chainRunning by remember { mutableStateOf(false) }
    var chainResults by remember { mutableStateOf<List<ChainStepResult>?>(null) }
    var chainDialogOpen by remember { mutableStateOf(false) }
    var retryingStep by remember { mutableStateOf<ChainStepId?>(null) }
    var numberDialog by remember { mutableStateOf<NumberField?>(null) }
    var textDialog by remember { mutableStateOf<TextField?>(null) }
    var showExcludedApps by remember { mutableStateOf(false) }
    var showDnsPicker by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var pendingBindValue by remember { mutableStateOf<String?>(null) }

    fun runWholeChain() {
        chainRunning = true
        scope.launch {
            chainResults = ChainStepId.entries.map { runChainStep(context, app, it) }
            chainRunning = false
            chainDialogOpen = true
        }
    }

    fun retryStep(id: ChainStepId) {
        if (retryingStep != null) return
        retryingStep = id
        scope.launch {
            val fresh = runChainStep(context, app, id)
            chainResults = chainResults?.map { if (it.id == id) fresh else it }
            retryingStep = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = Spacing.contentMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenPadding, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                SettingsSection(R.string.settings_section_connection) {
                    Column(
                        modifier = Modifier.padding(vertical = Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Button(
                            onClick = { runWholeChain() },
                            enabled = !chainRunning,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Spacing.touchTarget),
                        ) {
                            if (chainRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            Text(
                                stringResource(R.string.settings_chain_test),
                                modifier = if (chainRunning) {
                                    Modifier.padding(start = Spacing.sm)
                                } else {
                                    Modifier
                                },
                            )
                        }
                        Text(
                            stringResource(R.string.settings_chain_test_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SwitchRow(stringResource(R.string.settings_exit_check), exitCheck) {
                        exitCheck = it
                        settings.exitCheckEnabled = it
                    }
                    SwitchRow(stringResource(R.string.settings_proxy_only), proxyOnly) {
                        proxyOnly = it
                        settings.proxyOnlyMode = it
                    }
                    SwitchRow(stringResource(R.string.settings_tun_fallback), tunFallback) {
                        tunFallback = it
                        settings.tunFallbackToProxy = it
                    }
                    ValueRow(
                        stringResource(R.string.settings_mtu),
                        settings.mtu.toString(),
                    ) {
                        numberDialog = NumberField(
                            R.string.settings_mtu,
                            settings.mtu,
                            SettingsStore.MIN_MTU,
                            SettingsStore.MAX_MTU,
                        ) { settings.mtu = it }
                    }
                    ValueRow(
                        stringResource(R.string.settings_selected_region),
                        settings.lastRegion.uppercase(),
                        onNavigateToLocations,
                    )
                }

                SettingsSection(R.string.settings_section_appearance) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            val label = stringResource(
                                when (mode) {
                                    ThemeMode.SYSTEM -> R.string.theme_system
                                    ThemeMode.LIGHT -> R.string.theme_light
                                    ThemeMode.DARK -> R.string.theme_dark
                                },
                            )
                            if (themeController.mode == mode) {
                                Button(
                                    onClick = {},
                                    modifier = Modifier.heightIn(min = Spacing.touchTarget),
                                ) { Text(label) }
                            } else {
                                TextButton(
                                    onClick = { themeController.set(mode) },
                                    modifier = Modifier.heightIn(min = Spacing.touchTarget),
                                ) { Text(label) }
                            }
                        }
                    }
                }

                SettingsSection(R.string.settings_section_language) {
                    ValueRow(
                        stringResource(R.string.settings_language),
                        languageLabel(language, settings),
                    ) { showLanguagePicker = true }
                }

                SettingsSection(R.string.settings_section_dns) {
                    ValueRow(
                        stringResource(R.string.settings_dns_resolver),
                        dnsProviderLabel(dnsProvider),
                    ) { showDnsPicker = true }
                    if (dnsProvider == SettingsStore.DnsProvider.CUSTOM) {
                        ValueRow(
                            stringResource(R.string.settings_custom_doh),
                            settings.customDohUrl.ifBlank {
                                stringResource(R.string.settings_not_set)
                            },
                        ) {
                            textDialog = TextField(
                                R.string.settings_custom_doh,
                                settings.customDohUrl,
                                R.string.settings_doh_supporting,
                                R.string.settings_error_invalid_url,
                                validate = {
                                    it.isBlank() || SettingsStore.isValidDohUrl(it)
                                },
                            ) { value -> settings.customDohUrl = value }
                        }
                    }
                }

                SettingsSection(R.string.settings_section_local_proxy) {
                    ValueRow(
                        stringResource(R.string.settings_socks_bind),
                        settings.socksBindAddress,
                    ) {
                        textDialog = TextField(
                            R.string.settings_socks_bind,
                            settings.socksBindAddress,
                            R.string.settings_bind_supporting,
                            R.string.settings_error_invalid_ip,
                            validate = { SettingsStore.isValidIpAddress(it) },
                        ) { value ->
                            // Audit row 9: a non-loopback bind exposes the
                            // proxy to the whole LAN — warn and confirm
                            // before persisting it.
                            if (SettingsStore.isLoopbackBind(value)) {
                                settings.socksBindAddress = value
                            } else {
                                pendingBindValue = value
                            }
                        }
                    }
                    ValueRow(
                        stringResource(R.string.settings_socks_port),
                        settings.socksPort.toString(),
                    ) {
                        numberDialog = NumberField(
                            R.string.settings_socks_port,
                            settings.socksPort,
                            1,
                            65_535,
                        ) { settings.socksPort = it }
                    }
                    SwitchRow(stringResource(R.string.settings_http_proxy), httpProxy) {
                        httpProxy = it
                        settings.httpProxyEnabled = it
                    }
                    ValueRow(
                        stringResource(R.string.settings_http_port),
                        settings.httpProxyPort.toString(),
                    ) {
                        numberDialog = NumberField(
                            R.string.settings_http_port,
                            settings.httpProxyPort,
                            1,
                            65_535,
                        ) { settings.httpProxyPort = it }
                    }
                }

                SettingsSection(R.string.settings_section_split) {
                    ValueRow(
                        stringResource(R.string.settings_excluded_apps),
                        stringResource(
                            R.string.settings_excluded_apps_count,
                            settings.excludedApps.size,
                        ),
                    ) { showExcludedApps = true }
                }

                SettingsSection(R.string.settings_section_about) {
                    ValueRow(
                        stringResource(R.string.settings_version),
                        BuildConfig.VERSION_NAME,
                        onClick = null,
                    )
                    ValueRow(stringResource(R.string.settings_logs), "") {
                        onNavigateToLogs()
                    }
                    ValueRow(
                        stringResource(R.string.settings_app_update),
                        updateStateText(updateController.state),
                    ) {
                        when (updateController.state) {
                            is UpdateUiState.Failed -> updateController.showDialog()
                            else -> updateController.check()
                        }
                    }
                }
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
            title = { Text(stringResource(R.string.settings_dns_picker_title)) },
            text = {
                Column {
                    SettingsStore.DnsProvider.entries.forEach { provider ->
                        val select = {
                            dnsProvider = provider
                            settings.dnsProvider = provider
                            showDnsPicker = false
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = provider == dnsProvider,
                                    role = Role.RadioButton,
                                    onClick = select,
                                )
                                .heightIn(min = Spacing.touchTarget)
                                .padding(vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = provider == dnsProvider,
                                onClick = select,
                            )
                            Text(
                                dnsProviderLabel(provider),
                                modifier = Modifier.padding(start = Spacing.sm),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDnsPicker = false }) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
    }
    if (showLanguagePicker) {
        AlertDialog(
            onDismissRequest = { showLanguagePicker = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column {
                    AppLanguage.entries.forEach { choice ->
                        val select: () -> Unit = {
                            language = choice
                            settings.appLanguage = choice
                            showLanguagePicker = false
                            // The locale is applied in attachBaseContext, so
                            // the activity must be recreated for the new
                            // language to take effect.
                            (context as? Activity)?.recreate()
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = language == choice,
                                    role = Role.RadioButton,
                                    onClick = select,
                                )
                                .heightIn(min = Spacing.touchTarget)
                                .padding(vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = language == choice, onClick = select)
                            Text(
                                stringResource(
                                    when (choice) {
                                        AppLanguage.SYSTEM -> R.string.language_system
                                        AppLanguage.FA -> R.string.language_fa
                                        AppLanguage.EN -> R.string.language_en
                                    },
                                ),
                                modifier = Modifier.padding(start = Spacing.sm),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguagePicker = false }) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
    }
    pendingBindValue?.let { value ->
        AlertDialog(
            onDismissRequest = { pendingBindValue = null },
            title = { Text(stringResource(R.string.settings_lan_warning_title)) },
            text = {
                Text(stringResource(R.string.settings_lan_warning_message, value))
            },
            confirmButton = {
                TextButton(onClick = {
                    settings.socksBindAddress = value
                    pendingBindValue = null
                }) { Text(stringResource(R.string.settings_lan_warning_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingBindValue = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    if (showExcludedApps) {
        ExcludedAppsDialog(app) { showExcludedApps = false }
    }
    if (chainDialogOpen) {
        AlertDialog(
            onDismissRequest = { chainDialogOpen = false },
            title = { Text(stringResource(R.string.chain_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    chainResults.orEmpty().forEach { result ->
                        ChainStepRow(
                            result = result,
                            region = settings.lastRegion,
                            retrying = retryingStep == result.id,
                            onRetry = { retryStep(result.id) },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { chainDialogOpen = false }) {
                    Text(stringResource(R.string.common_ok))
                }
            },
        )
    }
}

// ------------------------------------------------------------- chain test

private enum class ChainStepId { TOKEN, LOCATIONS, SERVERS, EXIT_IP }

private data class ChainStepResult(
    val id: ChainStepId,
    val detail: String?,
    val error: Throwable?,
)

private fun chainStepName(context: Context, id: ChainStepId, region: String): String =
    when (id) {
        ChainStepId.TOKEN -> context.getString(R.string.chain_step_token)
        ChainStepId.LOCATIONS -> context.getString(R.string.chain_step_locations)
        ChainStepId.SERVERS -> context.getString(R.string.chain_step_servers, region)
        ChainStepId.EXIT_IP -> context.getString(R.string.chain_step_exit_ip)
    }

/**
 * F-06: one step of the live chain test (guest token, locations count,
 * server list for the selected region, exit IP through a real tunnel).
 * The full ✓/✗ line with the raw outcome goes to the log; the dialog
 * shows the localised detail or a friendly error (audit row 13) and can
 * re-run a single failed step (V-48).
 */
private suspend fun runChainStep(
    context: Context,
    app: IVeePNApp,
    id: ChainStepId,
): ChainStepResult {
    val region = app.settingsStore.lastRegion
    val outcome = runCatching {
        when (id) {
            ChainStepId.TOKEN -> {
                app.veePNApi.ensureToken(force = true)
                context.getString(R.string.chain_token_ok)
            }
            ChainStepId.LOCATIONS -> {
                val result = app.veePNApi.locations()
                context.getString(R.string.chain_locations_ok, result.all.size, result.free.size)
            }
            ChainStepId.SERVERS -> {
                val servers = app.veePNApi.serverList(region, forceRefresh = true)
                context.getString(R.string.chain_servers_ok, servers.size)
            }
            ChainStepId.EXIT_IP -> {
                val session = VeePNSession(app.veePNApi, region)
                try {
                    session.connect()
                    session.probeExitIp()
                        ?: throw IllegalStateException(
                            context.getString(R.string.chain_exit_ip_missing),
                        )
                } finally {
                    session.close()
                }
            }
        }
    }
    val name = chainStepName(context, id, region)
    outcome.fold(
        onSuccess = { detail -> AppLogger.i(CHAIN_TAG, "✓ $name — $detail") },
        onFailure = { error ->
            AppLogger.w(CHAIN_TAG, "✗ $name — ${error.message ?: error.javaClass.simpleName}", error)
        },
    )
    return ChainStepResult(id, outcome.getOrNull(), outcome.exceptionOrNull())
}

@Composable
private fun ChainStepRow(
    result: ChainStepResult,
    region: String,
    retrying: Boolean,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val name = when (result.id) {
        ChainStepId.TOKEN -> stringResource(R.string.chain_step_token)
        ChainStepId.LOCATIONS -> stringResource(R.string.chain_step_locations)
        ChainStepId.SERVERS -> stringResource(R.string.chain_step_servers, region)
        ChainStepId.EXIT_IP -> stringResource(R.string.chain_step_exit_ip)
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        val line = if (result.error == null) {
            "✓ $name — ${result.detail.orEmpty()}"
        } else {
            "✗ $name"
        }
        Text(line, style = MaterialTheme.typography.bodyMedium)
        if (result.error != null) {
            Text(
                UiErrors.friendlyMessage(context, result.error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            if (retrying) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.heightIn(min = Spacing.touchTarget),
                ) {
                    Text(stringResource(R.string.common_retry))
                }
            }
        }
    }
}

private const val CHAIN_TAG = "ChainTest"

// ------------------------------------------------------------------ rows

@Composable
private fun SettingsSection(
    @StringRes titleRes: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = Spacing.cardPadding),
                content = content,
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // V-27: a light haptic tick confirms the toggle. The whole row is the
    // click target (audit row 21), not just the switch thumb.
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onChange(!checked)
            }
            .heightIn(min = Spacing.touchTarget)
            .padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = { value ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onChange(value)
            },
        )
    }
}

@Composable
private fun ValueRow(label: String, value: String, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = Spacing.touchTarget)
            .padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun languageLabel(choice: AppLanguage?, settings: SettingsStore): String =
    when (choice) {
        AppLanguage.SYSTEM -> stringResource(R.string.language_system)
        AppLanguage.FA -> stringResource(R.string.language_fa)
        AppLanguage.EN -> stringResource(R.string.language_en)
        // Never chosen: MainActivity persists the resolved default on
        // first launch, so this only mirrors that resolution.
        null -> stringResource(
            if (settings.resolvedLanguageTag() == "fa") {
                R.string.language_fa
            } else {
                R.string.language_en
            },
        )
    }

@Composable
private fun dnsProviderLabel(provider: SettingsStore.DnsProvider): String =
    when (provider) {
        SettingsStore.DnsProvider.SYSTEM_DEFAULT ->
            stringResource(R.string.dns_provider_default)
        SettingsStore.DnsProvider.CUSTOM ->
            stringResource(R.string.dns_provider_custom)
        // Brand names (Cloudflare, Google, Quad9, AdGuard) are not translated.
        else -> provider.label
    }

// --------------------------------------------------------------- dialogs

private class NumberField(
    @StringRes val titleRes: Int,
    val initial: Int,
    val min: Int,
    val max: Int,
    val onSave: (Int) -> Unit,
)

private class TextField(
    @StringRes val titleRes: Int,
    val initial: String,
    @StringRes val supportingRes: Int,
    @StringRes val errorRes: Int,
    val validate: (String) -> Boolean,
    val onSave: (String) -> Unit,
)

@Composable
private fun NumberEditDialog(field: NumberField, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(field.initial.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in field.min..field.max
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(field.titleRes)) },
        text = {
            OutlinedTextField(
                value = text,
                // V-18: numeric keyboard + digit-only filtering.
                onValueChange = { text = it.filter { c -> c.isDigit() } },
                label = { Text(stringResource(field.titleRes)) },
                supportingText = {
                    Text(
                        if (valid || text.isEmpty()) {
                            stringResource(
                                R.string.settings_range_supporting,
                                field.min,
                                field.max,
                            )
                        } else {
                            stringResource(R.string.settings_error_range, field.min, field.max)
                        },
                    )
                },
                isError = !valid && text.isNotEmpty(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        },
        confirmButton = {
            // Audit row 8: invalid input keeps the dialog open with a
            // visible error instead of being silently dropped.
            TextButton(
                enabled = valid,
                onClick = {
                    field.onSave(value!!)
                    onDismiss()
                },
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun TextEditDialog(field: TextField, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(field.initial) }
    val valid = field.validate(text.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(field.titleRes)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(field.titleRes)) },
                supportingText = {
                    Text(
                        stringResource(if (valid) field.supportingRes else field.errorRes),
                    )
                },
                isError = !valid,
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    field.onSave(text.trim())
                    onDismiss()
                },
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun ExcludedAppsDialog(app: IVeePNApp, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // V-50 / audit row 18: the installed-app list is loaded off the main
    // thread; the dialog shows a loading state, then a searchable list.
    var installed by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(Unit) {
        installed = withContext(Dispatchers.IO) {
            val pm = context.applicationContext.packageManager
            val ownPackage = context.packageName
            pm.getInstalledApplications(0)
                .filter { it.packageName != ownPackage }
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.enabled }
                .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
                .map { it.packageName to pm.getApplicationLabel(it).toString() }
        }
    }
    var excluded by remember { mutableStateOf(app.settingsStore.excludedApps) }
    var search by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.excluded_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text(stringResource(R.string.excluded_search_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val apps = installed
                when {
                    apps == null -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(stringResource(R.string.excluded_loading))
                    }
                    else -> {
                        val filtered = apps.filter { (pkg, label) ->
                            search.isBlank() ||
                                label.contains(search, ignoreCase = true) ||
                                pkg.contains(search, ignoreCase = true)
                        }
                        if (filtered.isEmpty()) {
                            Text(stringResource(R.string.excluded_empty))
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                items(filtered, key = { it.first }) { (pkg, label) ->
                                    val toggle = {
                                        excluded =
                                            if (pkg in excluded) excluded - pkg else excluded + pkg
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(onClick = toggle)
                                            .heightIn(min = Spacing.touchTarget)
                                            .padding(vertical = Spacing.xs),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = pkg in excluded,
                                            onCheckedChange = { toggle() },
                                        )
                                        Text(
                                            "$label\n$pkg",
                                            modifier = Modifier.padding(start = Spacing.sm),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                app.settingsStore.excludedApps = excluded
                onDismiss()
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun updateStateText(state: UpdateUiState): String = when (state) {
    is UpdateUiState.Idle -> stringResource(R.string.update_state_check)
    is UpdateUiState.Checking -> stringResource(R.string.update_state_checking)
    is UpdateUiState.Available -> stringResource(R.string.update_state_available, state.info.version)
    is UpdateUiState.UpToDate -> stringResource(R.string.update_state_uptodate)
    is UpdateUiState.Downloading -> stringResource(R.string.update_state_downloading)
    is UpdateUiState.ReadyToInstall -> stringResource(R.string.update_state_ready)
    is UpdateUiState.Failed -> stringResource(R.string.update_state_failed)
}

package com.pishi.iveepn.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.pishi.iveepn.IVeePNApp
import com.pishi.iveepn.R
import com.pishi.iveepn.data.AppLogger
import com.pishi.iveepn.data.SelectedLocation
import com.pishi.iveepn.data.model.VeePNLocation
import com.pishi.iveepn.data.model.emojiFlag
import com.pishi.iveepn.ui.UiErrors
import com.pishi.iveepn.ui.theme.LocalIVeePNStatusColors
import com.pishi.iveepn.ui.theme.Spacing
import com.pishi.iveepn.vpn.LocationPingProber
import com.pishi.iveepn.vpn.LocationPingState
import com.pishi.iveepn.vpn.pingQuality
import com.pishi.iveepn.vpn.PingQuality
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Locations from the VeePN API with a Free / All
 * tab split (free = proxyType 0), search, and selection that persists the
 * region for the next dial. Premium locations are visible in the All tab
 * but cannot be selected in guest mode. The currently selected location is
 * highlighted with a check; loading shows skeleton rows and failures show a
 * friendly, localised message with retry. On load, every free location is
 * pinged (pre-connect latency, see [LocationPingProber]) and each free row
 * shows its result; the list can be re-sorted fastest-first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationsScreen(
    app: IVeePNApp,
    onBack: () -> Unit,
    onLocationSelected: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var all by remember { mutableStateOf<List<VeePNLocation>>(emptyList()) }
    var free by remember { mutableStateOf<List<VeePNLocation>>(emptyList()) }
    var optimalRegion by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    // V-09: the filter runs on a debounced copy of the query (~300 ms) so
    // typing stays instant and the ~185-row list is not re-filtered on
    // every keystroke.
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        delay(300)
        debouncedQuery = query
    }
    var reloadKey by remember { mutableIntStateOf(0) }

    val currentSelection by app.selectedLocationStore.selectedLocationFlow.collectAsState()
    val selectedText = stringResource(R.string.locations_selected)
    val loadingText = stringResource(R.string.locations_loading)

    LaunchedEffect(reloadKey) {
        loading = true
        error = null
        try {
            val result = app.veePNApi.locations()
            all = result.all
            free = result.free
            optimalRegion = runCatching { app.veePNApi.optimalLocation()?.region }.getOrNull()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Locations load failed", e)
            error = e
        } finally {
            loading = false
        }
    }

    // Pre-connect ping: as soon as the free locations are known, measure
    // each one's latency so the fastest can be picked before connecting.
    // Only free locations are probed — premium ones cannot be selected
    // in guest mode, and probing all 185 regions would hammer the API
    // for their server lists. A region's server list is cached (12 h),
    // so repeat rounds cost only the short TCP probes.
    var pingStates by remember { mutableStateOf<Map<String, LocationPingState>>(emptyMap()) }
    var pingRunKey by remember { mutableIntStateOf(0) }
    var sortByPing by remember { mutableStateOf(false) }
    val pingInProgress = pingStates.values.any { it is LocationPingState.Testing }
    val fastestRegion = pingStates.entries
        .filter { it.value is LocationPingState.Reachable }
        .minByOrNull { (it.value as LocationPingState.Reachable).ms }
        ?.key

    LaunchedEffect(free, pingRunKey) {
        val regions = free.map { it.region }.distinct()
        if (regions.isEmpty()) return@LaunchedEffect
        pingStates = regions.associateWith { LocationPingState.Testing }
        val prober = LocationPingProber(
            serverList = { region -> app.veePNApi.serverList(region) },
        )
        val gate = Semaphore(PING_PARALLELISM)
        regions.map { region ->
            launch {
                val result = gate.withPermit { prober.pingRegion(region) }
                pingStates = pingStates + (region to result)
            }
        }.forEach { it.join() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.locations_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { pingRunKey++ },
                        enabled = !loading && !pingInProgress && free.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(
                                R.string.locations_ping_retest,
                            ),
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
                    .padding(horizontal = Spacing.screenPadding),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.locations_search_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.sm),
                    singleLine = true,
                    shape = RoundedCornerShape(percent = 50),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                )

                TabRow(
                    selectedTabIndex = tab,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.secondary,
                ) {
                    Tab(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        text = { Text(stringResource(R.string.locations_tab_free, free.size)) },
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        text = { Text(stringResource(R.string.locations_tab_all, all.size)) },
                    )
                }

                when {
                    loading -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.sm)
                                .semantics {
                                    liveRegion = LiveRegionMode.Polite
                                    stateDescription = loadingText
                                },
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            repeat(6) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant,
                                                CircleShape,
                                            ),
                                    )
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(0.6f)
                                                .height(14.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant,
                                                    RoundedCornerShape(7.dp),
                                                ),
                                        )
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(0.35f)
                                                .height(10.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant,
                                                    RoundedCornerShape(7.dp),
                                                ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    error != null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.lg),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            Text(
                                text = stringResource(R.string.locations_error_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = UiErrors.friendlyMessage(LocalContext.current, error!!),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { reloadKey++ },
                                modifier = Modifier.heightIn(min = Spacing.touchTarget),
                            ) {
                                Text(stringResource(R.string.common_retry))
                            }
                        }
                    }
                    else -> {
                        val source = if (tab == 0) free else all
                        val baseFiltered = source.filter { loc ->
                            debouncedQuery.isBlank() ||
                                loc.name.contains(debouncedQuery, ignoreCase = true) ||
                                loc.countryName.contains(debouncedQuery, ignoreCase = true) ||
                                loc.region.contains(debouncedQuery, ignoreCase = true)
                        }
                        val filtered = if (sortByPing) {
                            baseFiltered.sortedWith(
                                compareBy(
                                    { loc: VeePNLocation ->
                                        pingRank(pingStates[loc.region])
                                    },
                                    { loc: VeePNLocation ->
                                        (pingStates[loc.region] as?
                                            LocationPingState.Reachable)?.ms
                                            ?: Int.MAX_VALUE
                                    },
                                    { loc: VeePNLocation -> loc.countryName },
                                ),
                            )
                        } else {
                            baseFiltered.sortedBy { it.countryName }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            FilterChip(
                                selected = sortByPing,
                                onClick = { sortByPing = !sortByPing },
                                label = {
                                    Text(stringResource(R.string.locations_sort_fastest))
                                },
                                enabled = pingStates.values.any {
                                    it is LocationPingState.Reachable
                                },
                            )
                            val fastestLoc = fastestRegion?.let { region ->
                                free.firstOrNull { it.region == region }
                            }
                            val fastestMs = (fastestRegion?.let { pingStates[it] }
                                as? LocationPingState.Reachable)?.ms
                            Text(
                                text = when {
                                    pingInProgress -> stringResource(
                                        R.string.locations_ping_testing,
                                    )
                                    fastestLoc != null && fastestMs != null ->
                                        stringResource(
                                            R.string.locations_ping_summary_fastest,
                                            fastestLoc.name
                                                .ifBlank { fastestLoc.region.uppercase() },
                                            fastestMs,
                                        )
                                    else -> ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (filtered.isEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Spacing.lg),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                if (debouncedQuery.isNotBlank()) {
                                    Text(
                                        text = stringResource(
                                            R.string.locations_search_empty_title,
                                            debouncedQuery,
                                        ),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.locations_search_empty_message,
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(
                                        onClick = { query = "" },
                                        modifier = Modifier.heightIn(
                                            min = Spacing.touchTarget,
                                        ),
                                    ) {
                                        Text(stringResource(R.string.locations_clear_search))
                                    }
                                } else {
                                    Text(
                                        text = stringResource(R.string.locations_empty_title),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        text = stringResource(R.string.locations_empty_message),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Button(
                                        onClick = { reloadKey++ },
                                        modifier = Modifier.heightIn(
                                            min = Spacing.touchTarget,
                                        ),
                                    ) {
                                        Text(stringResource(R.string.common_retry))
                                    }
                                }
                            }
                        } else {
                            // V-64: scrolling the results dismisses the
                            // software keyboard so it stops covering the list.
                            val listState = rememberLazyListState()
                            val keyboardController = LocalSoftwareKeyboardController.current
                            LaunchedEffect(listState.isScrollInProgress) {
                                if (listState.isScrollInProgress) keyboardController?.hide()
                            }
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                                contentPadding = PaddingValues(vertical = Spacing.sm),
                            ) {
                                items(filtered, key = { it.region + it.name }) { loc ->
                                    val isOptimal = loc.region == optimalRegion
                                    val selectable = loc.isFree
                                    val isSelected = currentSelection != null &&
                                        currentSelection!!.region == loc.region &&
                                        currentSelection!!.cityName == loc.name
                                    Card(
                                        shape = MaterialTheme.shapes.medium,
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surface
                                            },
                                        ),
                                        border = if (isSelected) {
                                            BorderStroke(
                                                1.5.dp,
                                                MaterialTheme.colorScheme.primary,
                                            )
                                        } else {
                                            null
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .semantics {
                                                if (isSelected) {
                                                    stateDescription = selectedText
                                                    selected = true
                                                }
                                            },
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .clickable(enabled = selectable) {
                                                    app.selectedLocationStore.save(
                                                        SelectedLocation(
                                                            region = loc.region,
                                                            countryName = loc.countryName,
                                                            countryCode = loc.countryCode,
                                                            cityName = loc.name,
                                                        ),
                                                    )
                                                    app.settingsStore.lastRegion = loc.region
                                                    onLocationSelected()
                                                }
                                                .heightIn(min = Spacing.touchTarget)
                                                .padding(Spacing.md),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    verticalAlignment = Alignment
                                                        .CenterVertically,
                                                    horizontalArrangement = Arrangement
                                                        .spacedBy(Spacing.sm),
                                                ) {
                                                    Text(
                                                        text = "${loc.emojiFlag} " +
                                                            "${loc.name.ifBlank { loc.region.uppercase() }}" +
                                                            " — ${loc.countryName}",
                                                        style = MaterialTheme.typography
                                                            .bodyLarge,
                                                        color = if (selectable) {
                                                            MaterialTheme.colorScheme
                                                                .onSurface
                                                        } else {
                                                            MaterialTheme.colorScheme
                                                                .onSurfaceVariant
                                                        },
                                                        modifier = Modifier.weight(
                                                            1f,
                                                            fill = false,
                                                        ),
                                                    )
                                                    if (loc.isFree) {
                                                        FreeChip()
                                                    }
                                                }
                                                if (!loc.isFree || isOptimal) {
                                                    Text(
                                                        text = stringResource(
                                                            if (!loc.isFree) {
                                                                R.string
                                                                    .locations_premium_note
                                                            } else {
                                                                R.string.locations_optimal
                                                            },
                                                        ),
                                                        style = MaterialTheme.typography
                                                            .bodySmall,
                                                    )
                                                }
                                            }
                                            if (loc.isFree) {
                                                PingChip(
                                                    state = pingStates[loc.region]
                                                        ?: LocationPingState.NotTested,
                                                    isFastest = loc.region == fastestRegion,
                                                )
                                            }
                                            if (isSelected) {
                                                Icon(
                                                    Icons.Filled.CheckCircle,
                                                    contentDescription = stringResource(
                                                        R.string.locations_selected,
                                                    ),
                                                    tint = MaterialTheme.colorScheme.secondary,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Sort rank for the fastest-first ordering: reachable, pending, dead. */
private fun pingRank(state: LocationPingState?): Int = when (state) {
    is LocationPingState.Reachable -> 0
    LocationPingState.Unreachable -> 2
    else -> 1
}

/**
 * Pre-connect latency for one row: a small spinner while the probe is
 * running, then a quality-coloured dot with the best latency, a
 * "Fastest" label on the round's winner, or an unreachable note.
 */
@Composable
private fun PingChip(state: LocationPingState, isFastest: Boolean) {
    val statusColors = LocalIVeePNStatusColors.current
    Column(horizontalAlignment = Alignment.End) {
        when (state) {
            LocationPingState.Testing -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
            }
            is LocationPingState.Reachable -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                when (pingQuality(state.ms)) {
                                    PingQuality.GOOD -> statusColors.connected
                                    PingQuality.FAIR -> statusColors.connecting
                                    PingQuality.POOR -> statusColors.warning
                                },
                                CircleShape,
                            ),
                    )
                    Text(
                        text = stringResource(R.string.locations_ping_ms, state.ms),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isFastest) {
                    Text(
                        text = stringResource(R.string.locations_ping_fastest),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            LocationPingState.Unreachable -> {
                Text(
                    text = stringResource(R.string.locations_ping_unreachable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            LocationPingState.NotTested -> Unit
        }
    }
}

/** Small pale-teal pill marking a free location, as in the Soft UI design. */
@Composable
private fun FreeChip() {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            text = stringResource(R.string.locations_free),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private const val TAG = "LocationsScreen"

/** Regions probed in parallel during one ping round. */
private const val PING_PARALLELISM = 4

package com.pishi.iveepn.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.pishi.iveepn.ui.theme.Spacing

/**
 * Locations for F-02/F-14: locations from the VeePN API with a Free / All
 * tab split (free = proxyType 0), search, and selection that persists the
 * region for the next dial. Premium locations are visible in the All tab
 * but cannot be selected in guest mode. The currently selected location is
 * highlighted with a check; loading shows skeleton rows and failures show a
 * friendly, localised message with retry.
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
    var reloadKey by remember { mutableIntStateOf(0) }

    val selected by app.selectedLocationStore.selectedLocationFlow.collectAsState()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.locations_title)) },
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
                )

                TabRow(selectedTabIndex = tab) {
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
                        val filtered = source.filter { loc ->
                            query.isBlank() ||
                                loc.name.contains(query, ignoreCase = true) ||
                                loc.countryName.contains(query, ignoreCase = true) ||
                                loc.region.contains(query, ignoreCase = true)
                        }.sortedBy { it.countryName }

                        if (filtered.isEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Spacing.lg),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                if (query.isNotBlank()) {
                                    Text(
                                        text = stringResource(
                                            R.string.locations_search_empty_title,
                                            query,
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
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                                contentPadding = PaddingValues(vertical = Spacing.sm),
                            ) {
                                items(filtered, key = { it.region + it.name }) { loc ->
                                    val isOptimal = loc.region == optimalRegion
                                    val selectable = loc.isFree
                                    val isSelected = selected != null &&
                                        selected!!.region == loc.region &&
                                        selected!!.cityName == loc.name
                                    Card(
                                        shape = MaterialTheme.shapes.medium,
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected) {
                                                MaterialTheme.colorScheme.secondaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerLow
                                            },
                                        ),
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
                                                Text(
                                                    text = "${loc.emojiFlag} " +
                                                        "${loc.name.ifBlank { loc.region.uppercase() }}" +
                                                        " — ${loc.countryName}",
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = if (selectable) {
                                                        MaterialTheme.colorScheme.onSurface
                                                    } else {
                                                        MaterialTheme.colorScheme
                                                            .onSurfaceVariant
                                                    },
                                                )
                                                Text(
                                                    text = stringResource(
                                                        when {
                                                            !loc.isFree ->
                                                                R.string.locations_premium_note
                                                            isOptimal ->
                                                                R.string.locations_optimal
                                                            else -> R.string.locations_free
                                                        },
                                                    ),
                                                    style = MaterialTheme.typography.bodySmall,
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

private const val TAG = "LocationsScreen"

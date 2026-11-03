// Placeholder UI — original iVeePN design lands in P4
package com.pishi.iveepn.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pishi.iveepn.IVeePNApp
import com.pishi.iveepn.data.AppLogger
import com.pishi.iveepn.data.SelectedLocation
import com.pishi.iveepn.data.model.VeePNLocation
import com.pishi.iveepn.data.model.emojiFlag

/**
 * Functional wiring shell for F-02/F-14: locations from the VeePN API with
 * a Free / All tab split (free = proxyType 0), search, and selection that
 * persists the region for the next dial. Premium locations are visible in
 * the All tab but cannot be selected in guest mode.
 */
@Composable
fun LocationsScreen(
    app: IVeePNApp,
    onBack: () -> Unit,
    onLocationSelected: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var all by remember { mutableStateOf<List<VeePNLocation>>(emptyList()) }
    var free by remember { mutableStateOf<List<VeePNLocation>>(emptyList()) }
    var optimalRegion by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var reloadKey by remember { mutableIntStateOf(0) }

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
            error = e.message ?: "Could not load locations"
        } finally {
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Locations", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onBack) { Text("Back") }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            singleLine = true,
        )

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Free (${free.size})") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("All (${all.size})") })
        }

        when {
            loading -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))
            error != null -> {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                Button(onClick = { reloadKey++ }) { Text("Retry") }
            }
            else -> {
                val source = if (tab == 0) free else all
                val filtered = source.filter { loc ->
                    query.isBlank() ||
                        loc.name.contains(query, ignoreCase = true) ||
                        loc.countryName.contains(query, ignoreCase = true) ||
                        loc.region.contains(query, ignoreCase = true)
                }.sortedBy { it.countryName }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.region + it.name }) { loc ->
                        val isOptimal = loc.region == optimalRegion
                        val selectable = loc.isFree
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
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
                                .padding(vertical = 10.dp),
                        ) {
                            Text(
                                "${loc.emojiFlag} ${loc.name.ifBlank { loc.region.uppercase() }} — ${loc.countryName}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selectable) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                when {
                                    !loc.isFree -> "Premium — not available in free mode"
                                    isOptimal -> "Optimal location"
                                    else -> "Free"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val TAG = "LocationsScreen"

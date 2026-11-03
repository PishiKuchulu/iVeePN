package com.pishi.iveepn.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The location the user picked on the Locations screen (F-02). */
data class SelectedLocation(
    val region: String,
    val countryName: String = "",
    val countryCode: String = "",
    val cityName: String = "",
)

/**
 * Persists the selected location. The region is mirrored into
 * [SettingsStore.lastRegion], which is what the VPN service dials.
 */
class SelectedLocationStore(context: Context) {
    private val prefs = context.getSharedPreferences("iveepn_location_state", Context.MODE_PRIVATE)

    private val _selectedLocationFlow = MutableStateFlow<SelectedLocation?>(load())
    val selectedLocationFlow: StateFlow<SelectedLocation?> = _selectedLocationFlow

    fun load(): SelectedLocation? {
        val region = prefs.getString(KEY_REGION, null)?.takeIf { it.isNotBlank() } ?: return null
        return SelectedLocation(
            region = region,
            countryName = prefs.getString(KEY_COUNTRY_NAME, "").orEmpty(),
            countryCode = prefs.getString(KEY_COUNTRY_CODE, "").orEmpty(),
            cityName = prefs.getString(KEY_CITY_NAME, "").orEmpty(),
        )
    }

    fun save(location: SelectedLocation) {
        prefs.edit()
            .putString(KEY_REGION, location.region)
            .putString(KEY_COUNTRY_NAME, location.countryName)
            .putString(KEY_COUNTRY_CODE, location.countryCode)
            .putString(KEY_CITY_NAME, location.cityName)
            .apply()
        _selectedLocationFlow.value = location
    }

    fun clear() {
        prefs.edit().clear().apply()
        _selectedLocationFlow.value = null
    }

    companion object {
        private const val KEY_REGION = "region"
        private const val KEY_COUNTRY_NAME = "country_name"
        private const val KEY_COUNTRY_CODE = "country_code"
        private const val KEY_CITY_NAME = "city_name"
    }
}

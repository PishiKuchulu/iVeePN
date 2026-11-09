package com.pishi.iveepn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.res.Configuration
import android.os.Build
import com.pishi.iveepn.data.ApiTransportOptions
import com.pishi.iveepn.data.CrashReporter
import com.pishi.iveepn.data.SelectedLocationStore
import com.pishi.iveepn.data.SettingsStore
import com.pishi.iveepn.data.TokenStore
import com.pishi.iveepn.data.VeePNApi
import com.pishi.iveepn.data.withAppLocale
import com.pishi.iveepn.vpn.IVeePNVpnService
import com.pishi.iveepn.widget.QuickConnectWidgetProvider

class IVeePNApp : Application() {

    // Lazy: constructing the TokenStore opens EncryptedSharedPreferences
    // (Android Keystore work), which must not sit on the main thread in
    // Application.onCreate. First actual use builds both.
    val tokenStore: TokenStore by lazy { TokenStore(this) }
    val veePNApi: VeePNApi by lazy {
        VeePNApi(
            tokenStore,
            transport = { ApiTransportOptions.fromStore(settingsStore) },
        )
    }

    lateinit var settingsStore: SettingsStore
    lateinit var selectedLocationStore: SelectedLocationStore

    override fun onCreate() {
        super.onCreate()

        CrashReporter.install(this)
        CrashReporter.replayLastCrashIfAny(this)

        // Shared log files from previous sessions are stale the moment
        // a new session starts; drop them so cacheDir/logs cannot grow
        // without bound (LogsScreen writes a fresh file per share).
        runCatching {
            java.io.File(cacheDir, "logs").listFiles()?.forEach { it.delete() }
        }

        settingsStore = SettingsStore(this)
        selectedLocationStore = SelectedLocationStore(this)
        // SettingsStore fires this hook when the theme mode changes; the
        // widget refresh lives here so the data layer needs no
        // dependency on the vpn/widget layers.
        SettingsStore.onThemeModeChanged = {
            QuickConnectWidgetProvider.updateAll(this, IVeePNVpnService.state.value)
        }
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        // A channel's name is frozen at creation and shown in the
        // system settings: take it from a context in the app's chosen
        // language, not the raw device locale.
        val localized = withAppLocale(settingsStore)
        manager.createNotificationChannel(
            NotificationChannel(
                VPN_NOTIFICATION_CHANNEL_ID,
                localized.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        QuickConnectWidgetProvider.updateAll(this, IVeePNVpnService.state.value)
    }

    companion object {
        const val VPN_NOTIFICATION_CHANNEL_ID = "iveepn_status"
    }
}

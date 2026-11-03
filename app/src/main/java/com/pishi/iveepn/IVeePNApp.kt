package com.pishi.iveepn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.res.Configuration
import android.os.Build
import com.pishi.iveepn.data.CrashReporter
import com.pishi.iveepn.data.SelectedLocationStore
import com.pishi.iveepn.data.SettingsStore
import com.pishi.iveepn.data.TokenStore
import com.pishi.iveepn.data.VeePNApi
import com.pishi.iveepn.vpn.IVeePNVpnService
import com.pishi.iveepn.widget.QuickConnectWidgetProvider

class IVeePNApp : Application() {

    lateinit var tokenStore: TokenStore
    lateinit var settingsStore: SettingsStore
    lateinit var selectedLocationStore: SelectedLocationStore
    lateinit var veePNApi: VeePNApi

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

        tokenStore = TokenStore(this)
        settingsStore = SettingsStore(this)
        selectedLocationStore = SelectedLocationStore(this)
        veePNApi = VeePNApi(tokenStore)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                VPN_NOTIFICATION_CHANNEL_ID,
                "VPN status",
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

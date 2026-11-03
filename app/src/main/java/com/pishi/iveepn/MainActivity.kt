package com.pishi.iveepn

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.pishi.iveepn.data.AppLanguage
import com.pishi.iveepn.data.SettingsStore
import com.pishi.iveepn.data.withAppLocale
import com.pishi.iveepn.ui.navigation.IVeePNNavGraph
import com.pishi.iveepn.ui.theme.IVeePNTheme
import com.pishi.iveepn.ui.theme.rememberThemeController
import com.pishi.iveepn.vpn.IVeePNVpnService

class MainActivity : ComponentActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            requestNotificationPermissionIfNeeded()
            IVeePNVpnService.start(this)
        } else {

            Toast.makeText(
                this,
                getString(R.string.main_vpn_permission_denied),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {  }

    private fun requestConnect() {
        if ((application as IVeePNApp).settingsStore.proxyOnlyMode) {
            requestNotificationPermissionIfNeeded()
            IVeePNVpnService.start(this)
            return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            requestNotificationPermissionIfNeeded()
            IVeePNVpnService.start(this)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val alreadyGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!alreadyGranted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val store = (newBase.applicationContext as? IVeePNApp)?.settingsStore ?: SettingsStore(newBase)
        if (store.appLanguage == null) {
            // Foundation default rule: Persian unless the system locale is English.
            // Persisted once as an explicit choice; afterwards only an explicit
            // "System default" selection follows the device locale.
            store.appLanguage = if (java.util.Locale.getDefault().language == "en") AppLanguage.EN else AppLanguage.FA
        }
        super.attachBaseContext(newBase.withAppLocale(store))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as IVeePNApp
        setContent {

            val themeController = rememberThemeController(app.settingsStore)
            IVeePNTheme(themeMode = themeController.mode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    IVeePNNavGraph(
                        navController = navController,
                        app = app,
                        themeController = themeController,
                        onRequestConnect = { requestConnect() },
                        onDisconnect = { IVeePNVpnService.stop(this) },
                    )
                }
            }
        }
    }
}

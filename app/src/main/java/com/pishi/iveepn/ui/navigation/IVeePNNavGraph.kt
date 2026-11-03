package com.pishi.iveepn.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.pishi.iveepn.IVeePNApp
import com.pishi.iveepn.ui.components.AppUpdateController
import com.pishi.iveepn.ui.components.AppUpdateDialog
import com.pishi.iveepn.ui.components.UpdateUiState
import com.pishi.iveepn.ui.screens.HomeScreen
import com.pishi.iveepn.ui.screens.LocationsScreen
import com.pishi.iveepn.ui.screens.LogsScreen
import com.pishi.iveepn.ui.screens.SettingsScreen
import com.pishi.iveepn.ui.theme.ThemeController
import com.pishi.iveepn.vpn.IVeePNVpnService

object Routes {
    const val HOME = "home"
    const val SERVERS = "servers"
    const val SETTINGS = "settings"
    const val LOGS = "logs"
}

@Composable
fun IVeePNNavGraph(
    navController: NavHostController,
    app: IVeePNApp,
    themeController: ThemeController,
    onRequestConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val updateController = remember { AppUpdateController(app) }
    androidx.compose.runtime.LaunchedEffect(Unit) { updateController.checkOnLaunch() }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                app = app,
                onNavigateToServers = { navController.navigate(Routes.SERVERS) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onNavigateToLogs = { navController.navigate(Routes.LOGS) },
                onRequestConnect = onRequestConnect,
                onDisconnect = onDisconnect,
                updateInfo = (updateController.state as? UpdateUiState.Available)?.info,
                onUpdateClick = { updateController.showDialog() },
            )
        }
        composable(Routes.SERVERS) {
            LocationsScreen(
                app = app,
                onBack = { navController.popBackStack() },
                onLocationSelected = {
                    // Re-dial on the new region when a session is live.
                    if (IVeePNVpnService.state.value ==
                        com.pishi.iveepn.data.model.ConnectionState.CONNECTED
                    ) {
                        onDisconnect()
                        onRequestConnect()
                    }
                    navController.popBackStack()
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                app = app,
                themeController = themeController,
                updateController = updateController,
                onNavigateToLogs = { navController.navigate(Routes.LOGS) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.LOGS) {
            LogsScreen(onBack = { navController.popBackStack() })
        }
    }

    AppUpdateDialog(updateController)
}

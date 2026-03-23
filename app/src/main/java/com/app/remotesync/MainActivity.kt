package com.app.remotesync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.app.remotesync.ui.screens.DeviceDiscoveryScreen
import com.app.remotesync.ui.screens.RemoteControlViewport
import com.app.remotesync.ui.screens.SettingsScreen
import com.app.remotesync.ui.theme.RemoteSyncTheme
import com.app.remotesync.webrtc.DiscoveredDevice
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RemoteSyncTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
private fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "discovery") {

        composable("discovery") {
            DeviceDiscoveryScreen(
                onNavigateToViewport = { device ->
                    navController.navigate(
                        "viewport/${device.host}/${device.port}/${device.name}"
                    )
                },
                onNavigateToSettings = { navController.navigate("settings") }
            )
        }

        composable(
            route = "viewport/{host}/{port}/{name}",
            arguments = listOf(
                navArgument("host") { type = NavType.StringType },
                navArgument("port") { type = NavType.IntType },
                navArgument("name") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val host = backStackEntry.arguments?.getString("host") ?: ""
            val port = backStackEntry.arguments?.getInt("port") ?: 8765
            val name = backStackEntry.arguments?.getString("name") ?: ""

            RemoteControlViewport(
                hostAddress = "$name ($host)",
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

package de.schwen.openoverdrive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import feature.discovery.DiscoveryScreen
import feature.drive.DriveScreen
import androidx.compose.ui.platform.LocalContext
import android.net.Uri

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppNav()
                }
            }
        }
    }
}

@Composable
fun AppNav(navController: NavHostController = rememberNavController()) {
    val bleClient = remember { BleProvider.client }
    NavHost(navController, startDestination = "discovery") {
        composable("discovery") {
            DiscoveryScreen(
                onConnected = { deviceAddress ->
                    navController.navigate("drive/$deviceAddress")
                },
                bleClient = bleClient,
                onOpenDiagnostics = { navController.navigate("diagnostics") },
                onOpenMultiplayer = { navController.navigate("multiplayer") }
            )
        }
        composable("drive/{address}?name={name}") { backStackEntry ->
            val address = backStackEntry.arguments?.getString("address") ?: ""
            val name = backStackEntry.arguments?.getString("name")
            DriveScreen(address = address, displayName = name, onBack = { navController.popBackStack() }, bleClient = bleClient)
        }
        composable("diagnostics") {
            DiagnosticsScreen(onBack = { navController.popBackStack() }, bleClient = bleClient)
        }
        composable("multiplayer") {
            MultiplayerScreen(
                onBack = { navController.popBackStack() },
                onStartDrive = { address, name ->
                    if (!address.isNullOrBlank()) {
                        val encoded = name?.let { java.net.URLEncoder.encode(it, "UTF-8") }
                        val route = if (encoded != null) "drive/$address?name=$encoded" else "drive/$address"
                        navController.navigate(route)
                    }
                }
            )
        }
    }
}

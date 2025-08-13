package app.openoverdrive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import feature.discovery.DiscoveryScreen
import feature.drive.DriveScreen
import androidx.compose.ui.platform.LocalContext
import core.ble.AndroidBleClient
import core.ble.BleClient

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
    val ctx = LocalContext.current
    val bleClient: BleClient = remember { AndroidBleClient(ctx) }
    NavHost(navController, startDestination = "discovery") {
        composable("discovery") {
            DiscoveryScreen(
                onConnected = { deviceAddress ->
                    navController.navigate("drive/$deviceAddress")
                },
                bleClient = bleClient
            )
        }
        composable("drive/{address}") { backStackEntry ->
            val address = backStackEntry.arguments?.getString("address") ?: ""
            DriveScreen(address = address, onBack = { navController.popBackStack() }, bleClient = bleClient)
        }
    }
}

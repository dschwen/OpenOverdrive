package feature.discovery

import android.Manifest
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import core.ble.BleClient
import core.ble.FakeBleClient
import core.ble.BleDevice
import data.CarRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun DiscoveryScreen(
    onConnected: (String) -> Unit,
    bleClient: BleClient = remember { FakeBleClient() },
    carRepositoryProvider: (BleClient) -> CarRepository = { ble ->
        // In app we’ll provide a real instance via DI; here use a placeholder that won’t crash.
        CarRepository(androidx.compose.ui.platform.LocalContext.current, ble)
    }
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val carRepo = remember { carRepositoryProvider(bleClient) }

    val permissions = if (Build.VERSION.SDK_INT >= 31) {
        rememberMultiplePermissionsState(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        )
    } else {
        rememberMultiplePermissionsState(listOf(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    var devices by remember { mutableStateOf<List<BleDevice>>(emptyList()) }

    LaunchedEffect(Unit) {
        permissions.launchMultiplePermissionRequest()
        bleClient.scanForAnkiDevices().collectLatest { list -> devices = list }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Discover & Connect") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!permissions.allPermissionsGranted) {
                Text(
                    text = "Bluetooth permissions required to scan.",
                    modifier = Modifier.padding(16.dp)
                )
                Button(onClick = { permissions.launchMultiplePermissionRequest() }, modifier = Modifier.padding(16.dp)) {
                    Text("Grant Permissions")
                }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(devices) { dev ->
                    ListItem(
                        headlineText = { Text(dev.name ?: dev.address) },
                        supportingText = { Text(dev.address) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    bleClient.connect(dev.address)
                                    onConnected(dev.address)
                                }
                            }
                    )
                    Divider()
                }
            }
        }
    }
}


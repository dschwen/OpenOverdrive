package feature.drive

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import core.ble.BleClient
import core.ble.FakeBleClient
import core.protocol.VehicleMsg
import core.protocol.VehicleMsgParser
import core.protocol.VehicleMessage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun DriveScreen(
    address: String,
    onBack: () -> Unit,
    bleClient: BleClient = remember { FakeBleClient() }
) {
    val scope = rememberCoroutineScope()
    var speed by remember { mutableStateOf(0) }
    var battery by remember { mutableStateOf<Int?>(null) }
    var laps by remember { mutableStateOf(0) }

    LaunchedEffect(address) {
        bleClient.enableNotifications()
        bleClient.notifications().collectLatest { bytes ->
            when (val msg = VehicleMsgParser.parse(bytes)) {
                is VehicleMessage.BatteryLevel -> battery = msg.percent
                is VehicleMessage.PositionUpdate -> { /* TODO: lap heuristic */ }
                else -> {}
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Driving $address") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Battery: ${battery?.let { "$it%" } ?: "?"}")
                Text("Laps: $laps")
            }

            Text("Speed: $speed mm/s")
            Slider(
                value = speed.toFloat(),
                onValueChange = { v -> speed = v.toInt() },
                onValueChangeFinished = {
                    scope.launch {
                        bleClient.write(VehicleMsg.setSpeed(speed, 25000))
                    }
                },
                valueRange = 0f..1500f
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = {
                    speed = (speed + 150).coerceAtMost(1500)
                    scope.launch { bleClient.write(VehicleMsg.setSpeed(speed, 25000)) }
                }) { Text("Accelerate") }

                Button(onClick = {
                    speed = 0
                    scope.launch { bleClient.write(VehicleMsg.setSpeed(0, 30000)) }
                }) { Text("Brake") }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = {
                    // Left lane change: negative offset
                    scope.launch {
                        bleClient.write(VehicleMsg.setOffsetFromCenter(0f))
                        bleClient.write(VehicleMsg.changeLane(600, 8000, -44f))
                    }
                }) { Text("Lane Left") }

                Button(onClick = {
                    scope.launch {
                        bleClient.write(VehicleMsg.setOffsetFromCenter(0f))
                        bleClient.write(VehicleMsg.changeLane(600, 8000, 44f))
                    }
                }) { Text("Lane Right") }
            }

            Spacer(Modifier.weight(1f))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { scope.launch { bleClient.write(VehicleMsg.batteryRequest()) } }) {
                    Text("Read Battery")
                }
                OutlinedButton(onClick = { onBack() }) { Text("Disconnect") }
            }
        }
    }
}


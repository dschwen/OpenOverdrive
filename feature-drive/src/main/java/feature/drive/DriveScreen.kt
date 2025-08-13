package feature.drive

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import core.ble.AndroidBleClient
import core.ble.BleClient
import core.ble.ConnectionState
import core.protocol.VehicleMsg
import core.protocol.VehicleMsgParser
import core.protocol.VehicleMessage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DriveScreen(
    address: String,
    onBack: () -> Unit,
    bleClient: BleClient = run {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        remember { AndroidBleClient(ctx) }
    }
) {
    val scope = rememberCoroutineScope()
    var speed by remember { mutableStateOf(0) }
    var battery by remember { mutableStateOf<Int?>(null) }
    var laps by remember { mutableStateOf(0) }
    var startPieceId by remember { mutableStateOf<Int?>(null) }
    var lastPieceId by remember { mutableStateOf<Int?>(null) }
    var lastLapTs by remember { mutableStateOf(0L) }
    var initSent by remember { mutableStateOf(false) }

    LaunchedEffect(address) {
        bleClient.notifications().collectLatest { bytes ->
            when (val msg = VehicleMsgParser.parse(bytes)) {
                is VehicleMessage.BatteryLevel -> battery = msg.percent
                is VehicleMessage.PositionUpdate -> {
                    val rp = msg.roadPieceId
                    lastPieceId = rp
                    val marker = startPieceId
                    if (marker != null && rp == marker) {
                        val now = System.currentTimeMillis()
                        if (now - lastLapTs > 5000) {
                            laps += 1
                            lastLapTs = now
                        }
                    }
                }
                else -> {}
            }
        }
    }

    // React to connection state: send SDK mode and periodic battery when connected
    val connState by bleClient.connectionState.collectAsState(initial = ConnectionState.Disconnected)
    LaunchedEffect(connState) {
        if (connState is ConnectionState.Connected && !initSent) {
            initSent = true
            // Enable SDK mode and fetch battery, then poll every 20s
            bleClient.write(VehicleMsg.sdkMode(true))
            bleClient.write(VehicleMsg.batteryRequest())
            while (true) {
                delay(20000)
                bleClient.write(VehicleMsg.batteryRequest())
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

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { scope.launch { bleClient.write(VehicleMsg.batteryRequest()) } }) {
                    Text("Read Battery")
                }
                OutlinedButton(onClick = {
                    startPieceId = lastPieceId
                    laps = 0
                    lastLapTs = 0L
                }) { Text(if (startPieceId == null) "Mark Start" else "Reset Laps") }
                OutlinedButton(onClick = { onBack() }) { Text("Disconnect") }
            }
        }
    }
}

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(
    address: String,
    onBack: () -> Unit,
    bleClient: BleClient? = null
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val client = bleClient ?: remember { AndroidBleClient(ctx) }
    var speed by remember { mutableStateOf(0) }
    var battery by remember { mutableStateOf<Int?>(null) }
    var laps by remember { mutableStateOf(0) }
    var startPieceId by remember { mutableStateOf<Int?>(null) }
    var lastPieceId by remember { mutableStateOf<Int?>(null) }
    var lastLapTs by remember { mutableStateOf(0L) }
    var initSent by remember { mutableStateOf(false) }
    var lastLapTimeMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(address) {
        client.notifications().collectLatest { bytes ->
            when (val msg = VehicleMsgParser.parse(bytes)) {
                is VehicleMessage.BatteryLevel -> battery = msg.percent
                is VehicleMessage.PositionUpdate -> {
                    val rp = msg.roadPieceId
                    if (lastPieceId != rp) {
                        val marker = startPieceId
                        val forward = !msg.reverseDriving
                        if (marker != null && rp == marker && forward) {
                            val now = System.currentTimeMillis()
                            if (now - lastLapTs > 3000) {
                                laps += 1
                                lastLapTimeMs = if (lastLapTs == 0L) null else now - lastLapTs
                                lastLapTs = now
                            }
                        }
                        lastPieceId = rp
                    }
                }
                is VehicleMessage.TransitionUpdate -> {
                    val rp = msg.roadPieceIdx
                    if (lastPieceId != rp) {
                        lastPieceId = rp
                    }
                }
                else -> {}
            }
        }
    }

    // React to connection state: send SDK mode and periodic battery when connected
    val connState by client.connectionState.collectAsState(initial = ConnectionState.Disconnected)
    LaunchedEffect(connState) {
        if (connState is ConnectionState.Connected && !initSent) {
            initSent = true
            // Ensure notifications are enabled, then enable SDK mode and fetch battery; poll every 20s
            try { client.enableNotifications() } catch (_: Throwable) {}
            client.write(VehicleMsg.sdkMode(true))
            client.write(VehicleMsg.batteryRequest())
            while (true) {
                delay(20000)
                client.write(VehicleMsg.batteryRequest())
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
                val lapText = lastLapTimeMs?.let { ms ->
                    val sec = ms / 1000
                    val tenths = (ms % 1000) / 100
                    "Laps: $laps (last: ${sec}.${tenths}s)"
                } ?: "Laps: $laps"
                Text(lapText)
            }

            Text("Speed: $speed mm/s")
            Slider(
                value = speed.toFloat(),
                onValueChange = { v -> speed = v.toInt() },
                onValueChangeFinished = {
                    scope.launch {
                        client.write(VehicleMsg.setSpeed(speed, 25000))
                    }
                },
                valueRange = 0f..1500f
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = {
                    speed = (speed + 150).coerceAtMost(1500)
                    scope.launch { client.write(VehicleMsg.setSpeed(speed, 25000)) }
                }) { Text("Accelerate") }

                Button(onClick = {
                    speed = 0
                    scope.launch { client.write(VehicleMsg.setSpeed(0, 30000)) }
                }) { Text("Brake") }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = {
                    // Left lane change: negative offset
                    scope.launch {
                        client.write(VehicleMsg.setOffsetFromCenter(0f))
                        client.write(VehicleMsg.changeLane(600, 8000, -44f))
                    }
                }) { Text("Lane Left") }

                Button(onClick = {
                    scope.launch {
                        client.write(VehicleMsg.setOffsetFromCenter(0f))
                        client.write(VehicleMsg.changeLane(600, 8000, 44f))
                    }
                }) { Text("Lane Right") }
            }

            Spacer(Modifier.weight(1f))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { scope.launch { client.write(VehicleMsg.batteryRequest()) } }) {
                    Text("Read Battery")
                }
                OutlinedButton(onClick = {
                    startPieceId = lastPieceId
                    laps = 0
                    lastLapTs = 0L
                    lastLapTimeMs = null
                }) { Text(if (startPieceId == null) "Mark Start" else "Reset Laps") }
                OutlinedButton(onClick = { onBack() }) { Text("Disconnect") }
            }
        }
    }
}

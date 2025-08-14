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
    var laneTag by remember { mutableStateOf(0) }

    var wasOnMarker by remember { mutableStateOf(false) }
    LaunchedEffect(address) {
        client.notifications().collectLatest { bytes ->
            when (val msg = VehicleMsgParser.parse(bytes)) {
                is VehicleMessage.BatteryLevel -> battery = msg.percent
                is VehicleMessage.PositionUpdate -> {
                    val rp = msg.roadPieceId
                    lastPieceId = rp
                    val marker = startPieceId
                    val onMarker = (marker != null && rp == marker)
                    if (onMarker && !wasOnMarker) {
                        val now = System.currentTimeMillis()
                        // Require movement to avoid counting when stationary
                        if (now - lastLapTs > 3000 && msg.speedMmPerSec > 100) {
                            laps += 1
                            lastLapTimeMs = if (lastLapTs == 0L) null else now - lastLapTs
                            lastLapTs = now
                        }
                    }
                    wasOnMarker = onMarker
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
        when (connState) {
            is ConnectionState.Connected -> {
                if (!initSent) {
                    initSent = true
                    try { client.enableNotifications() } catch (_: Throwable) {}
                    // Small handshake to ensure car accepts commands
                    delay(150)
                    repeat(2) {
                        client.write(VehicleMsg.sdkMode(true))
                        delay(120)
                    }
                    client.write(VehicleMsg.batteryRequest())
                    while (true) {
                        delay(30000)
                        client.write(VehicleMsg.batteryRequest())
                    }
                }
            }
            is ConnectionState.Disconnected -> {
                initSent = false
            }
            is ConnectionState.Connecting -> { /* no-op */ }
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

            // Debug line for lap logic visibility
            Text("Piece: ${lastPieceId ?: -1}  Marker: ${startPieceId ?: -1}")

            Text("Speed: $speed mm/s")
            Slider(
                value = speed.toFloat(),
                onValueChange = { v -> speed = v.toInt() },
                onValueChangeFinished = {
                    scope.launch {
                        client.write(VehicleMsg.setSpeed(speed, 25000, 1))
                    }
                },
                valueRange = 0f..1500f
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = {
                    speed = (speed + 150).coerceAtMost(1500)
                    scope.launch { client.write(VehicleMsg.setSpeed(speed, 25000, 1)) }
                }) { Text("Accelerate") }

                Button(onClick = {
                    speed = 0
                    scope.launch { client.write(VehicleMsg.setSpeed(0, 30000, 1)) }
                }) { Text("Brake") }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = {
                    scope.launch {
                        val fwd = if (speed < 300) 300 else speed
                        client.write(VehicleMsg.setSpeed(fwd, 25000, 1))
                        delay(100)
                        client.write(VehicleMsg.setOffsetFromCenter(0f))
                        delay(100)
                        client.write(VehicleMsg.changeLane(600, 8000, -44f, hopIntent = 1, tag = laneTag.toByte()))
                        laneTag = (laneTag + 1) and 0xFF
                    }
                }) { Text("Lane Left") }

                Button(onClick = {
                    scope.launch {
                        val fwd = if (speed < 300) 300 else speed
                        client.write(VehicleMsg.setSpeed(fwd, 25000, 1))
                        delay(100)
                        client.write(VehicleMsg.setOffsetFromCenter(0f))
                        delay(100)
                        client.write(VehicleMsg.changeLane(600, 8000, 44f, hopIntent = 1, tag = laneTag.toByte()))
                        laneTag = (laneTag + 1) and 0xFF
                    }
                }) { Text("Lane Right") }
            }

            Spacer(Modifier.weight(1f))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { scope.launch { client.write(VehicleMsg.batteryRequest()) } },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("Read Battery", maxLines = 1) }

                OutlinedButton(
                    onClick = {
                        startPieceId = lastPieceId
                        wasOnMarker = true // require leaving and re-entering to count
                        laps = 0
                        lastLapTs = 0L
                        lastLapTimeMs = null
                    },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text(if (startPieceId == null) "Mark Start" else "Reset Laps", maxLines = 1) }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching { client.write(VehicleMsg.setSpeed(0, 30000, 1)) }
                            delay(150)
                            client.disconnect()
                            onBack()
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("Disconnect", maxLines = 1) }
            }
        }
    }
}

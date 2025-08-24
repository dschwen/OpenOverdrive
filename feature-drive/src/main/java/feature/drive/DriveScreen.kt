package feature.drive

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.BatteryAlert
import core.net.NetSession
import core.net.NetCodec
import core.net.NetMessage
 

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiPlayerDriveScreen(
    address: String,
    displayName: String? = null,
    onBack: () -> Unit,
    bleClient: BleClient? = null
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
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
    var onCharger by remember { mutableStateOf<Boolean?>(null) }
    var chargedBattery by remember { mutableStateOf<Boolean?>(null) }
    var lowBattery by remember { mutableStateOf<Boolean?>(null) }
    // Multiplayer session + countdown state
    var lastTelemetrySentMs by remember { mutableStateOf(0L) }
    val netTransport by NetSession.transport.collectAsState(initial = null)
    val matchStartAt by NetSession.matchStartAtMs.collectAsState(initial = null)
    val targetLaps by NetSession.targetLaps.collectAsState(initial = null)
    // Race/match state
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var wasOnMarker by remember { mutableStateOf(false) }
    var useV4Speed by remember { mutableStateOf(true) }
    var autoMarkPending by remember { mutableStateOf(false) }
    var finishedAtMs by remember { mutableStateOf<Long?>(null) }
    data class Racer(var name: String?, var laps: Int = 0, var bestLapMs: Long? = null, var finishedAt: Long? = null)
    val racers = remember { mutableStateMapOf<String, Racer>() }
    val localPeerName = remember(netTransport) { netTransport?.localPeer?.name }
    LaunchedEffect(localPeerName) { racers["local"] = Racer(name = displayName ?: localPeerName) }
    LaunchedEffect(matchStartAt) {
        while (matchStartAt != null && nowMs < (matchStartAt ?: 0L) + 2000) {
            nowMs = System.currentTimeMillis()
            delay(100)
        }
    }
    LaunchedEffect(matchStartAt, netTransport) {
        if (matchStartAt != null && netTransport != null) {
            autoMarkPending = true
            laps = 0
            lastLapTs = 0L
            lastLapTimeMs = null
            finishedAtMs = null
        }
    }
    val preGo = matchStartAt?.let { nowMs < it } ?: false
    val postGoShowing = matchStartAt?.let { nowMs in it..(it + 1500) } ?: false
    val controlsEnabled = !preGo

    
    LaunchedEffect(address) {
        client.notifications().collectLatest { bytes ->
            when (val msg = VehicleMsgParser.parse(bytes)) {
                is VehicleMessage.BatteryLevel -> { battery = msg.percent }
                is VehicleMessage.CarStatus -> { onCharger = msg.onCharger; chargedBattery = msg.chargedBattery; lowBattery = msg.lowBattery }
                is VehicleMessage.PositionUpdate -> {
                    // Optional: publish telemetry to host if a net session is active (client role)
                    val t = netTransport
                    val now = System.currentTimeMillis()
                    if (t != null && now - lastTelemetrySentMs >= 100) {
                        val flags = msg.parsingFlags ?: 0
                        val payload = NetCodec.encode(
                            NetMessage.CarTelemetry(
                                pieceId = msg.roadPieceId,
                                locationId = msg.locationId,
                                offsetMm = msg.offsetFromCenter,
                                speedMmps = msg.speedMmPerSec,
                                flags = flags,
                                tsClient = now
                            )
                        )
                        // Best-effort; ignore result
                        runCatching { t.broadcast(payload) }
                        lastTelemetrySentMs = now
                    }
                    val rp = msg.roadPieceId
                    lastPieceId = rp
                    if (autoMarkPending && matchStartAt != null && nowMs >= (matchStartAt ?: 0L)) {
                        startPieceId = rp
                        wasOnMarker = true
                        autoMarkPending = false
                    }
                    val marker = startPieceId
                    val onMarker = (marker != null && rp == marker)
                    if (onMarker && !wasOnMarker) {
                        val nowTs = System.currentTimeMillis()
                        // Require movement to avoid counting when stationary
                        if (nowTs - lastLapTs > 3000 && msg.speedMmPerSec > 100) {
                            laps += 1
                            lastLapTimeMs = if (lastLapTs == 0L) null else nowTs - lastLapTs
                            lastLapTs = nowTs
                            // Update local racer stats and broadcast
                            val r = racers.getOrPut("local") { Racer(name = displayName ?: localPeerName) }
                            r.laps = laps
                            if (lastLapTimeMs != null) r.bestLapMs = listOfNotNull(r.bestLapMs, lastLapTimeMs).minOrNull()
                            netTransport?.let { tr ->
                                val bb = java.nio.ByteBuffer.allocate(1 + 8 + 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                bb.put(laps.toByte())
                                bb.putLong(lastLapTimeMs ?: 0L)
                                bb.putLong((matchStartAt?.let { nowTs - it } ?: 0L))
                                val evt = NetCodec.encode(NetMessage.Event(type = 3, payload = bb.array()))
                                runCatching { tr.broadcast(evt) }
                            }
                            targetLaps?.let { tl ->
                                if (laps >= tl && finishedAtMs == null) {
                                    finishedAtMs = nowTs
                                    r.finishedAt = nowTs
                                    netTransport?.let { tr ->
                                        val bb = java.nio.ByteBuffer.allocate(1 + 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                        bb.put(laps.toByte())
                                        bb.putLong((matchStartAt?.let { nowTs - it } ?: 0L))
                                        val evt = NetCodec.encode(NetMessage.Event(type = 4, payload = bb.array()))
                                        runCatching { tr.broadcast(evt) }
                                    }
                                    scope.launch { client.write(VehicleMsg.setSpeed(0, 30000, 1)) }
                                }
                            }
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
                is VehicleMessage.Version -> { useV4Speed = (msg.version >= 12385) }
                else -> {}
            }
        }
    }

    // React to connection state: send SDK mode and periodic battery when connected
    val connState by client.connectionState.collectAsState(initial = ConnectionState.Disconnected)
    // Ensure we are connected when entering Drive (single player path)
    LaunchedEffect(address) {
        if (connState is ConnectionState.Disconnected) {
            runCatching { client.connect(address) }
        }
    }
    LaunchedEffect(connState) {
        when (connState) {
            is ConnectionState.Connected -> {
                if (!initSent) {
                    initSent = true
                    // Robust handshake: ensure notifications, then set SDK mode with retries
                    repeat(3) {
                        try { if (client.enableNotifications()) { return@repeat } } catch (_: Throwable) {}
                        delay(150)
                    }
                    // Even if notifications didn't report success, proceed but be conservative with timing
                    delay(150)
                    repeat(3) {
                        client.write(VehicleMsg.sdkMode(true))
                        delay(150)
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

    // Listen for peer race updates
    LaunchedEffect(netTransport) {
        val t = netTransport ?: return@LaunchedEffect
        launch {
            t.incoming().collectLatest { (peer, bytes) ->
                when (val m = NetCodec.decode(bytes)) {
                    is NetMessage.Event -> when (m.type) {
                        3 -> {
                            val bb = java.nio.ByteBuffer.wrap(m.payload).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            val lp = (bb.get().toInt() and 0xFF)
                            val lapMs = bb.long
                            val elapsedMs = bb.long
                            val r = racers.getOrPut(peer.id) { Racer(name = peer.name) }
                            r.laps = lp
                            if (lapMs > 0) r.bestLapMs = listOfNotNull(r.bestLapMs, lapMs).minOrNull()
                        }
                        4 -> {
                            val bb = java.nio.ByteBuffer.wrap(m.payload).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            val lp = (bb.get().toInt() and 0xFF)
                            val elapsedMs = bb.long
                            val r = racers.getOrPut(peer.id) { Racer(name = peer.name) }
                            r.laps = lp
                            r.finishedAt = (matchStartAt?.let { it + elapsedMs } ?: System.currentTimeMillis())
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    val title = displayName ?: address
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(id = R.string.ood_driving_title, title)) }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(id = R.string.ood_battery_label) + " " + (battery?.let { "$it%" } ?: "?"))
                    if (onCharger == true) {
                        Icon(
                            imageVector = Icons.Outlined.Bolt,
                            contentDescription = "Charging",
                            tint = androidx.compose.ui.graphics.Color(0xFFFFC107)
                        )
                    }
                    if (chargedBattery == true) {
                        Icon(
                            imageVector = Icons.Outlined.BatteryFull,
                            contentDescription = "Charged",
                            tint = androidx.compose.ui.graphics.Color(0xFF4CAF50)
                        )
                    }
                    if (lowBattery == true) {
                        Icon(
                            imageVector = Icons.Outlined.BatteryAlert,
                            contentDescription = "Low battery",
                            tint = androidx.compose.ui.graphics.Color(0xFFD32F2F)
                        )
                    }
                }
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
                        val target = if (!controlsEnabled) 0 else speed
                        val pkt = if (useV4Speed) VehicleMsg.setSpeedV4(target, 25000, 1) else VehicleMsg.setSpeed(target, 25000, 1)
                        client.write(pkt)
                    }
                },
                valueRange = 0f..1500f,
                enabled = controlsEnabled
            )

            // Controls grid: Accelerate, Left+Right, Decelerate, Brake
            val green = androidx.compose.ui.graphics.Color(0xFF2E7D32)
            val blue  = androidx.compose.ui.graphics.Color(0xFF1976D2)
            val orange= androidx.compose.ui.graphics.Color(0xFFFB8C00)
            val red   = androidx.compose.ui.graphics.Color(0xFFD32F2F)
            val textOnColor = androidx.compose.ui.graphics.Color.White

            // Accelerate (full width)
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    speed = (speed + 150).coerceAtMost(1500)
                    scope.launch {
                        val pkt = if (useV4Speed) VehicleMsg.setSpeedV4(speed, 25000, 1) else VehicleMsg.setSpeed(speed, 25000, 1)
                        client.write(pkt)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = green),
                modifier = Modifier.fillMaxWidth().height(64.dp),
                enabled = controlsEnabled
            ) { Text(stringResource(id = R.string.ood_accelerate), color = textOnColor) }

            // Left + Right (split row)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch {
                            val fwd = if (speed < 300) 300 else speed
                            val pkt = if (!controlsEnabled) {
                                if (useV4Speed) VehicleMsg.setSpeedV4(0, 25000, 1) else VehicleMsg.setSpeed(0, 25000, 1)
                            } else {
                                if (useV4Speed) VehicleMsg.setSpeedV4(fwd, 25000, 1) else VehicleMsg.setSpeed(fwd, 25000, 1)
                            }
                            client.write(pkt)
                            delay(100)
                            client.write(VehicleMsg.setOffsetFromCenter(0f))
                            delay(100)
                            client.write(VehicleMsg.changeLane(600, 8000, -44f, hopIntent = 1, tag = laneTag.toByte()))
                            laneTag = (laneTag + 1) and 0xFF
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = blue),
                    modifier = Modifier.weight(1f).height(64.dp),
                    enabled = controlsEnabled
                ) { Text(stringResource(id = R.string.ood_left), color = textOnColor) }

                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch {
                            val fwd = if (speed < 300) 300 else speed
                            val pkt = if (!controlsEnabled) {
                                if (useV4Speed) VehicleMsg.setSpeedV4(0, 25000, 1) else VehicleMsg.setSpeed(0, 25000, 1)
                            } else {
                                if (useV4Speed) VehicleMsg.setSpeedV4(fwd, 25000, 1) else VehicleMsg.setSpeed(fwd, 25000, 1)
                            }
                            client.write(pkt)
                            delay(100)
                            client.write(VehicleMsg.setOffsetFromCenter(0f))
                            delay(100)
                            client.write(VehicleMsg.changeLane(600, 8000, 44f, hopIntent = 1, tag = laneTag.toByte()))
                            laneTag = (laneTag + 1) and 0xFF
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = blue),
                    modifier = Modifier.weight(1f).height(64.dp),
                    enabled = controlsEnabled
                ) { Text(stringResource(id = R.string.ood_right), color = textOnColor) }
            }

            // Decelerate (full width)
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    speed = (speed - 150).coerceAtLeast(0)
                    scope.launch {
                        val accel = if (speed == 0) 30000 else 25000
                        val pkt = if (useV4Speed) VehicleMsg.setSpeedV4(speed, accel, 1) else VehicleMsg.setSpeed(speed, accel, 1)
                        client.write(pkt)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = orange),
                modifier = Modifier.fillMaxWidth().height(64.dp),
                enabled = controlsEnabled
            ) { Text(stringResource(id = R.string.ood_decelerate), color = textOnColor) }

            // Brake (full width)
            Button(
                onClick = {
                    // Stronger haptic for brake: double pulse
                    scope.launch {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        delay(80)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    speed = 0
                    scope.launch {
                        val pkt = if (useV4Speed) VehicleMsg.setSpeedV4(0, 30000, 1) else VehicleMsg.setSpeed(0, 30000, 1)
                        client.write(pkt)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = red),
                modifier = Modifier.fillMaxWidth().height(72.dp),
                enabled = controlsEnabled
            ) { Text(stringResource(id = R.string.ood_brake), color = textOnColor) }

            Spacer(Modifier.weight(1f))

            // Countdown/Go indicator + started overlay
            matchStartAt?.let { goAt ->
                val remaining = goAt - nowMs
                if (remaining <= 4000) {
                    val label = when {
                        remaining > 3000 -> "3"
                        remaining > 2000 -> "2"
                        remaining > 1000 -> "1"
                        remaining > -200 -> "Go!"
                        else -> null
                    }
                    label?.let { Text(it, style = MaterialTheme.typography.headlineLarge) }
                }
                if (postGoShowing) {
                    Text(stringResource(id = R.string.ood_match_started), style = MaterialTheme.typography.titleLarge)
                }
            }

            // Race results overlay when finished
            val tl = targetLaps
            if (tl != null && finishedAtMs != null) {
                val sorted = racers.entries.sortedWith(compareBy({ it.value.finishedAt == null }, { it.value.finishedAt ?: Long.MAX_VALUE }))
                Text(stringResource(id = R.string.ood_results), style = MaterialTheme.typography.titleMedium)
                sorted.forEachIndexed { idx, e ->
                    val place = "${idx + 1}."
                    val name = e.value.name ?: e.key
                    val total = e.value.finishedAt?.let { fa -> matchStartAt?.let { ms -> fa - ms } }
                    val totalStr = total?.let { ms -> "${ms/1000}.${(ms%1000)/100}s" } ?: stringResource(id = R.string.ood_dnf, e.value.laps, tl)
                    val best = e.value.bestLapMs?.let { ms ->
                        val sec = (ms / 1000).toInt()
                        val tenths = ((ms % 1000) / 100).toInt()
                        stringResource(id = R.string.ood_best_lap, sec, tenths)
                    } ?: ""
                    Text("$place  $name  $totalStr  $best")
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching { client.write(VehicleMsg.setSpeed(0, 30000, 1)) }
                            delay(150)
                            // Do not disconnect here; return to lobby and let lobby own disconnect on back to Discover
                            // Leave multiplayer if active
                            NetSession.setMatchStartAt(null)
                            NetSession.setTargetLaps(null)
                            NetSession.transport.value?.let { tr -> runCatching { tr.stop() } }
                            NetSession.set(null)
                            onBack()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text(stringResource(id = R.string.ood_quit_match), maxLines = 1) }
            }
        }
    }
}

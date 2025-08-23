package de.schwen.openoverdrive

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import core.ble.BleClient
import core.ble.BleDevice
import core.ble.ConnectionState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    bleClient: BleClient
) {
    val scope = rememberCoroutineScope()
    val conn by bleClient.connectionState.collectAsState()

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

    var scanning by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<BleDevice>>(emptyList()) }
    var notifCount by remember { mutableStateOf(0) }
    var lastNotif by remember { mutableStateOf<String>("") }
    var lastWriteOk by remember { mutableStateOf<Boolean?>(null) }
    var notifEnabled by remember { mutableStateOf<Boolean?>(null) }
    var lastBatteryMv by remember { mutableStateOf<Int?>(null) }
    var lastBatteryPct by remember { mutableStateOf<Int?>(null) }
    var onCharger by remember { mutableStateOf<Boolean?>(null) }
    var charged by remember { mutableStateOf<Boolean?>(null) }
    var low by remember { mutableStateOf<Boolean?>(null) }
    var lastPos by remember { mutableStateOf<core.protocol.VehicleMessage.PositionUpdate?>(null) }
    var lastTrans by remember { mutableStateOf<core.protocol.VehicleMessage.TransitionUpdate?>(null) }
    var sdkEnabled by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(scanning, permissions.allPermissionsGranted) {
        if (!scanning || !permissions.allPermissionsGranted) return@LaunchedEffect
        bleClient.scanForAnkiDevices().collectLatest { list -> devices = list }
    }

    LaunchedEffect(Unit) {
        bleClient.notifications().collect { bytes ->
            notifCount += 1
            lastNotif = bytes.take(16).joinToString(" ") { b -> "%02X".format(b) }
            // Try to parse battery packets for visibility
            core.protocol.VehicleMsgParser.parse(bytes)?.let { msg ->
                when (msg) {
                    is core.protocol.VehicleMessage.BatteryLevel -> {
                        val raw = msg.raw
                        val mv = if (raw in 2500..5000) raw else null
                        lastBatteryMv = mv
                        lastBatteryPct = msg.percent
                    }
                    is core.protocol.VehicleMessage.CarStatus -> {
                        onCharger = msg.onCharger
                        charged = msg.chargedBattery
                        low = msg.lowBattery
                    }
                    is core.protocol.VehicleMessage.PositionUpdate -> {
                        lastPos = msg
                    }
                    is core.protocol.VehicleMessage.TransitionUpdate -> {
                        lastTrans = msg
                    }
                    is core.protocol.VehicleMessage.Version -> {
                        version = msg.version
                    }
                    else -> { /* ignore */ }
                }
            }
        }
    }

    // When connected, auto-enable notifications and SDK mode for easier testing
    LaunchedEffect(conn) {
        if (conn is ConnectionState.Connected) {
            try { notifEnabled = bleClient.enableNotifications() } catch (_: Throwable) {}
            try { lastWriteOk = bleClient.write(core.protocol.VehicleMsg.sdkMode(true)) } catch (_: Throwable) {}
            sdkEnabled = true
        } else if (conn is ConnectionState.Disconnected) {
            sdkEnabled = false
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Diagnostics") }) }
    ) { padding ->
        val scroll = rememberScrollState()
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(scroll)
        ) {
            Text(
                text = "Connection: " + when (val c = conn) {
                    is ConnectionState.Connected -> "Connected to ${c.address}"
                    is ConnectionState.Connecting -> "Connecting to ${c.address}"
                    ConnectionState.Disconnected -> "Disconnected"
                },
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!permissions.allPermissionsGranted) {
                    Button(onClick = { permissions.launchMultiplePermissionRequest() }) { Text("Grant Bluetooth permissions") }
                } else {
                    if (!scanning) Button(onClick = { scanning = true }) { Text("Start scan") } else Button(onClick = { scanning = false }) { Text("Stop scan") }
                }
                when (conn) {
                    is ConnectionState.Connected -> Button(onClick = { scope.launch { bleClient.disconnect() } }) { Text("Disconnect") }
                    is ConnectionState.Connecting -> {}
                    is ConnectionState.Disconnected -> {}
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { scope.launch { notifEnabled = bleClient.enableNotifications() } }) { Text("Enable notifications") }
                OutlinedButton(onClick = { scope.launch { lastWriteOk = bleClient.write(core.protocol.VehicleMsg.ping()) } }) { Text("Ping") }
                OutlinedButton(onClick = { scope.launch { lastWriteOk = bleClient.write(core.protocol.VehicleMsg.batteryRequest()) } }) { Text("Battery req") }
                OutlinedButton(onClick = { scope.launch { lastWriteOk = bleClient.write(core.protocol.VehicleMsg.versionRequest()) } }) { Text("Version req") }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("SDK mode")
                    androidx.compose.material3.Switch(
                        checked = sdkEnabled,
                        onCheckedChange = { checked ->
                            sdkEnabled = checked
                            scope.launch { lastWriteOk = bleClient.write(core.protocol.VehicleMsg.sdkMode(checked)) }
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { scope.launch { lastWriteOk = bleClient.write(core.protocol.VehicleMsg.setOffsetFromCenter(0f)) } }) { Text("Center offset") }
                OutlinedButton(onClick = { scope.launch { lastWriteOk = bleClient.write(core.protocol.VehicleMsg.setSpeed(600, 25000, 1)) } }) { Text("Test speed") }
                OutlinedButton(onClick = { scope.launch { lastWriteOk = bleClient.write(core.protocol.VehicleMsg.setSpeed(0, 30000, 1)) } }) { Text("Brake") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        lastWriteOk = bleClient.write(core.protocol.VehicleMsg.changeLane(600, 8000, -44f, hopIntent = 1, tag = 1))
                    }
                }) { Text("Lane L") }
                OutlinedButton(onClick = {
                    scope.launch {
                        lastWriteOk = bleClient.write(core.protocol.VehicleMsg.changeLane(600, 8000, 44f, hopIntent = 1, tag = 2))
                    }
                }) { Text("Lane R") }
            }
            Spacer(Modifier.height(8.dp))
            Text("Notifications: $notifCount packets" + (notifEnabled?.let { " (enabled: $it)" } ?: ""))
            if (lastWriteOk != null) Text("Last write ok: $lastWriteOk")
            if (lastNotif.isNotEmpty()) Text("Last (first 16B): $lastNotif")
            lastBatteryPct?.let { pct ->
                val detail = lastBatteryMv?.let { mv -> " (${mv}mV)" } ?: ""
                Text("Battery parsed: ${pct}%$detail")
            }
            version?.let { v -> Text("Firmware version: $v") }
            if (onCharger != null || charged != null || low != null) {
                Text("Status: onCharger=${onCharger ?: "?"} charged=${charged ?: "?"} low=${low ?: "?"}")
            }
            lastPos?.let { p ->
                val flagsStr = p.parsingFlags?.let { f -> "0x%02X".format(f) } ?: "?"
                Text(
                    "Pos: loc=${p.locationId} piece=${p.roadPieceId} off=${String.format("%.1f", p.offsetFromCenter)}mm " +
                        "spd=${p.speedMmPerSec} flags=${flagsStr} recvLC=${p.lastRecvdLaneChangeCmdId ?: "?"} " +
                        "execLC=${p.lastExecutedLaneChangeCmdId ?: "?"} lcSp=${p.lastDesiredLaneChangeSpeedMmps ?: "?"} desSp=${p.lastDesiredSpeedMmps ?: "?"}"
                )
            }
            lastTrans?.let { t ->
                Text(
                    "Trans: piece=${t.roadPieceIdx} prev=${t.roadPieceIdxPrev} off=${t.offsetFromCenter?.let { o -> String.format("%.1f", o) } ?: "?"}mm " +
                        "drift=${t.avgFollowLineDrift ?: "?"} laneAct=${t.hadLaneChangeActivity ?: "?"} " +
                        "L=${t.leftWheelDistanceCm ?: "?"}cm R=${t.rightWheelDistanceCm ?: "?"}cm up=${t.uphillCounter ?: "?"} down=${t.downhillCounter ?: "?"}"
                )
            }

            Spacer(Modifier.height(12.dp))
            Text("Discovered devices (${devices.size})", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(devices) { d ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(d.name ?: d.address)
                            Text("${d.address}  •  RSSI: ${d.rssi ?: "?"}", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { scope.launch { bleClient.connect(d.address) } }) { Text("Connect") }
                        }
                    }
                    HorizontalDivider()
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
    }
}

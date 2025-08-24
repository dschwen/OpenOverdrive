package de.schwen.openoverdrive

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import core.net.*
import kotlinx.coroutines.launch
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import android.os.Build
import kotlinx.coroutines.flow.first
import androidx.activity.compose.BackHandler
import de.schwen.openoverdrive.BleProvider

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun MultiplayerScreen(
    selectedAddress: String?,
    selectedName: String?,
    onBack: () -> Unit,
    onStartDrive: (String?, String?, Boolean) -> Unit = { _, _, _ -> }
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("Player") }
    var isHost by remember { mutableStateOf<Boolean?>(null) }
    var laps by remember { mutableStateOf(3) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Idle") }
    var peers by remember { mutableStateOf<List<Peer>>(emptyList()) }
    var offsetEstimateMs by remember { mutableStateOf<Long?>(null) } // client-side estimate
    val logs = remember { mutableStateListOf<String>() }
    // Persisted preferences
    val prefs: SharedPreferences = remember(ctx) { ctx.getSharedPreferences("openoverdrive", Context.MODE_PRIVATE) }
    LaunchedEffect(Unit) {
        name = prefs.getString("player_name", name) ?: name
    }

    // Hold references to transport/services while running
    var transport by remember { mutableStateOf<Transport?>(null) }
    var hostService by remember { mutableStateOf<HostService?>(null) }
    var clientService by remember { mutableStateOf<ClientService?>(null) }
    val offsetByPeer = remember { mutableStateMapOf<String, Long>() }
    val sendTimes = remember { mutableStateMapOf<Int, Long>() }
    var didNavigateToDrive by remember { mutableStateOf(false) }
    var leavingToDrive by remember { mutableStateOf(false) }

    // Incoming listener
    LaunchedEffect(transport) {
        val t = transport ?: return@LaunchedEffect
        // Peers list updates
        launch {
            t.peers().collect { list -> peers = list }
        }
        // Incoming messages
        launch {
            t.incoming().collect { (peer, bytes) ->
                val msg = core.net.NetCodec.decode(bytes)
                when (msg) {
                    is NetMessage.TimeSync -> {
                        if (isHost == false && msg.tClient == null) {
                            // Client: estimate offset and reply with tClient
                            val now = System.currentTimeMillis()
                            offsetEstimateMs = msg.tHost - now
                            val reply = NetCodec.encode(NetMessage.TimeSync(tHost = msg.tHost, seq = msg.seq, tClient = now))
                            transport?.send(peer, reply)
                            logs.add("<- TimeSync seq=${msg.seq}; offset≈${offsetEstimateMs}ms; replied")
                        } else if (isHost == true) {
                            val tClient = msg.tClient
                            if (tClient != null) {
                                // Host: compute RTT and offset ~ tClientRecv - (tHostSend + RTT/2)
                                val tHs = sendTimes[msg.seq]
                                val tHr = System.currentTimeMillis()
                                if (tHs != null) {
                                    val rtt = tHr - tHs
                                    val off = tClient - (tHs + rtt / 2)
                                    offsetByPeer[peer.id] = off
                                    logs.add("<- TimeSync ack seq=${msg.seq}; RTT=${rtt}ms; offset≈${off}ms from ${peer.name ?: peer.id}")
                                } else {
                                    logs.add("<- TimeSync ack seq=${msg.seq}")
                                }
                            }
                        }
                    }
                    is NetMessage.Event -> {
                        if (msg.type == 1) {
                            // Start Match: payload = [hostGoAt: int64 little-endian, countdownSec: u8]
                            val bb = java.nio.ByteBuffer.wrap(msg.payload).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            val hostGoAt = if (bb.remaining() >= 8) bb.long else System.currentTimeMillis() + 4000
                            val countdownSec = if (bb.remaining() >= 1) (bb.get().toInt() and 0xFF) else 3
                            val targetLaps = if (bb.remaining() >= 1) (bb.get().toInt() and 0xFF) else 3
                            val est = offsetEstimateMs ?: 0L
                            val localGoAt = hostGoAt - est
                            core.net.NetSession.setMatchStartAt(localGoAt)
                            core.net.NetSession.setTargetLaps(targetLaps)
                            logs.add("<- Start Match: ${countdownSec}s, localGoAt=${localGoAt}")
                            if (!didNavigateToDrive) { didNavigateToDrive = true; leavingToDrive = true; onStartDrive(selectedAddress, selectedName, true) }
                        } else if (msg.type == 2) {
                            // Cancel countdown
                            core.net.NetSession.setMatchStartAt(null)
                            logs.add("<- Cancel Match Countdown")
                        }
                    }
                    else -> {
                        val type = bytes.firstOrNull()?.toInt() ?: -1
                        logs.add("<- ${peer.name ?: peer.id}: type=$type len=${bytes.size}")
                    }
                }
            }
        }
    }

    fun startNearby(context: Context) {
        if (running) return
        val serviceId = "de.schwen.openoverdrive.session"
        val role: Role = if (isHost == true) Role.Host else Role.Client
        val t = NearbyTransport(context, role, serviceId, name)
        transport = t
        core.net.NetSession.set(t)
        if (isHost == true) {
            val host = HostService(t)
            hostService = host
            host.start()
            status = "Advertising (Nearby)"
        } else {
            val client = ClientService(t)
            clientService = client
            client.start()
            status = "Discovering (Nearby)"
        }
        scope.launch {
            val ok = t.start()
            running = ok
            if (!ok) status = "Failed to start"
        }
        // Host: send periodic TimeSync
        if (isHost == true) {
            scope.launch {
                var seq = 1
                while (true) {
                    if (!running) break
                    val now = System.currentTimeMillis()
                    val bytes = NetCodec.encode(NetMessage.TimeSync(tHost = now, seq = seq++))
                    sendTimes[seq - 1] = now
                    val count = transport?.broadcast(bytes) ?: 0
                    logs.add("-> TimeSync seq=${seq - 1} to $count peers")
                    kotlinx.coroutines.delay(1000)
                }
            }
        } else {
            // Client: announce join once
            scope.launch {
                kotlinx.coroutines.delay(300)
                val join = NetCodec.encode(NetMessage.Join(name, appVersion = 1))
                transport?.broadcast(join)
                logs.add("-> Join broadcasted")
            }
        }
    }

    fun stopNearby() {
        scope.launch {
            hostService?.stop(); hostService = null
            clientService?.stop(); clientService = null
            transport?.stop(); transport = null
            core.net.NetSession.set(null)
            status = "Stopped"
            running = false
        }
    }

    // Handle leaving the lobby: ensure BLE disconnect when going back to discovery
    fun handleBack() {
        // Run cleanup then navigate back to ensure disconnect succeeds before disposal cancels coroutines.
        scope.launch {
            try { BleProvider.client.disconnect() } catch (_: Throwable) {}
            stopNearby()
            onBack()
        }
    }

    // Intercept system back to apply the same cleanup
    BackHandler(enabled = true) { handleBack() }

    // Safety net: on dispose, if we are leaving via back/pop (not navigating forward to Drive),
    // disconnect BLE and stop Nearby to free the car.
    DisposableEffect(Unit) {
        onDispose {
            if (!leavingToDrive) {
                scope.launch { try { BleProvider.client.disconnect() } catch (_: Throwable) {} }
                stopNearby()
            }
        }
    }

    // Countdown state for host/client UI
    val matchStartAt by core.net.NetSession.matchStartAtMs.collectAsState(initial = null)
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(matchStartAt) {
        while (matchStartAt != null && nowMs < (matchStartAt ?: 0L) + 2000) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(100)
        }
    }
    val preGo = matchStartAt?.let { nowMs < it } ?: false
    val postGoShowing = matchStartAt?.let { nowMs in it..(it + 1500) } ?: false

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(id = R.string.ood_mp_lobby_title)) }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Runtime permissions for Nearby (Android 12+): request BLE advertise/scan/connect
            val permissions = if (Build.VERSION.SDK_INT >= 31) {
                val base = mutableListOf(
                    android.Manifest.permission.BLUETOOTH_ADVERTISE,
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                    // Some devices/Play Services still require location for Nearby discovery
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                )
                if (Build.VERSION.SDK_INT >= 33) {
                    base += android.Manifest.permission.NEARBY_WIFI_DEVICES
                }
                rememberMultiplePermissionsState(base)
            } else {
                // Pre-Android 12: Nearby requires location permission
                rememberMultiplePermissionsState(listOf(android.Manifest.permission.ACCESS_FINE_LOCATION))
            }
            if (!permissions.allPermissionsGranted) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(id = R.string.ood_mp_bt_perms_required))
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { permissions.launchMultiplePermissionRequest() }) { Text(stringResource(id = R.string.ood_grant_permissions)) }
                    }
                }
            }
            // Player name input
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    prefs.edit().putString("player_name", it).apply()
                },
                label = { Text(stringResource(id = R.string.ood_your_name)) },
                modifier = Modifier.fillMaxWidth()
            )
            // Connected vehicle name (below the input)
            Text(
                text = selectedName ?: selectedAddress ?: "",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
            )
            // Laps selector (host can change; value is sent with Start Match)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(id = R.string.ood_laps_label))
                listOf(1,3,5,10).forEach { n ->
                    val sel = n == laps
                    if (sel) Button(onClick = { laps = n }) { Text("$n") } else OutlinedButton(onClick = { laps = n }) { Text("$n") }
                }
            }
            Text("Status: $status")
            // Nearby status line for quick diagnosis
            val nearbyStatus by remember(transport) {
                (transport as? NearbyTransport)?.status ?: kotlinx.coroutines.flow.MutableStateFlow("Idle")
            }.collectAsState(initial = "Idle")
            Text(stringResource(id = R.string.ood_nearby_label) + " " + nearbyStatus)
            if (isHost == false) {
                Text("Time offset (≈): ${offsetEstimateMs?.let { "$it ms" } ?: "?"}")
            }
            // Countdown/Go indicator in lobby, to make state clear
            matchStartAt?.let { goAt ->
                val remaining = goAt - nowMs
                val label = when {
                    remaining > 3000 -> "3"
                    remaining > 2000 -> "2"
                    remaining > 1000 -> "1"
                    remaining > -200 -> "Go!"
                    else -> null
                }
                label?.let { Text(stringResource(id = R.string.ood_start_in_label) + " " + it, style = MaterialTheme.typography.titleMedium) }
                if (postGoShowing) {
                    Text(stringResource(id = R.string.ood_match_started), style = MaterialTheme.typography.titleMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { leavingToDrive = true; onStartDrive(selectedAddress, selectedName, false) }, enabled = selectedAddress != null) { Text(stringResource(id = R.string.ood_single_player)) }
                OutlinedButton(onClick = { handleBack() }) { Text(stringResource(id = R.string.ood_back)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val canStart = permissions.allPermissionsGranted
                Button(onClick = { isHost = true; startNearby(ctx) }, enabled = !running && canStart) { Text(stringResource(id = R.string.ood_host_game)) }
                Button(onClick = { isHost = false; startNearby(ctx) }, enabled = !running && canStart) { Text(stringResource(id = R.string.ood_join_game)) }
                if (running) OutlinedButton(onClick = { stopNearby() }) { Text(stringResource(id = R.string.ood_leave)) }
            }
            HorizontalDivider()
            Text(stringResource(id = R.string.ood_peers_label) + " (" + peers.size + ")")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                items(peers) { p ->
                    val off = offsetByPeer[p.id]
                    Text("• ${p.name ?: p.id}${off?.let { "  (offset≈${it}ms)" } ?: ""}")
                }
            }
            // Host controls (prominent)
            if (isHost == true) {
                val green = androidx.compose.ui.graphics.Color(0xFF2E7D32)
                Button(
                    onClick = {
                        val countdownSec = 3
                        val hostGoAt = System.currentTimeMillis() + (countdownSec + 1) * 1000L
                        // payload: [hostGoAt(int64 LE), countdownSec(u8), laps(u8)]
                        val bb = java.nio.ByteBuffer.allocate(8 + 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        bb.putLong(hostGoAt)
                        bb.put(countdownSec.toByte())
                        bb.put(laps.toByte())
                        val evt = NetCodec.encode(NetMessage.Event(type = 1, payload = bb.array()))
                        scope.launch {
                            val count = transport?.broadcast(evt) ?: 0
                            logs.add("-> Start Match sent to $count peers")
                            core.net.NetSession.setMatchStartAt(hostGoAt)
                            core.net.NetSession.setTargetLaps(laps)
                            if (!didNavigateToDrive) { didNavigateToDrive = true; leavingToDrive = true; onStartDrive(selectedAddress, selectedName, true) }
                        }
                    },
                    enabled = running && !preGo,
                    colors = ButtonDefaults.buttonColors(containerColor = green),
                    modifier = Modifier.fillMaxWidth().height(64.dp)
                ) { Text(stringResource(id = R.string.ood_start_match), color = androidx.compose.ui.graphics.Color.White) }

                Button(
                    onClick = {
                        val evt = NetCodec.encode(NetMessage.Event(type = 2, payload = ByteArray(0)))
                        scope.launch {
                            val count = transport?.broadcast(evt) ?: 0
                            logs.add("-> Cancel Countdown sent to $count peers")
                            core.net.NetSession.setMatchStartAt(null)
                        }
                    },
                    enabled = running && preGo,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text(stringResource(id = R.string.ood_cancel_countdown)) }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(logs) { line -> Text(line) }
            }
        }
    }
}

//

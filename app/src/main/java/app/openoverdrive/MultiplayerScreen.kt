package de.schwen.openoverdrive

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import core.net.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiplayerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("Player") }
    var isHost by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Idle") }
    var peers by remember { mutableStateOf<List<Peer>>(emptyList()) }
    var offsetEstimateMs by remember { mutableStateOf<Long?>(null) } // client-side estimate
    val logs = remember { mutableStateListOf<String>() }

    // Hold references to transport/services while running
    var transport by remember { mutableStateOf<Transport?>(null) }
    var hostService by remember { mutableStateOf<HostService?>(null) }
    var clientService by remember { mutableStateOf<ClientService?>(null) }
    val offsetByPeer = remember { mutableStateMapOf<String, Long>() }
    val sendTimes = remember { mutableStateMapOf<Int, Long>() }

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
                        if (!isHost && msg.tClient == null) {
                            // Client: estimate offset and reply with tClient
                            val now = System.currentTimeMillis()
                            offsetEstimateMs = msg.tHost - now
                            val reply = NetCodec.encode(NetMessage.TimeSync(tHost = msg.tHost, seq = msg.seq, tClient = now))
                            transport?.send(peer, reply)
                            logs.add("<- TimeSync seq=${msg.seq}; offset≈${offsetEstimateMs}ms; replied")
                        } else if (isHost && msg.tClient != null) {
                            // Host: compute RTT and offset ~ tClientRecv - (tHostSend + RTT/2)
                            val tHs = sendTimes[msg.seq]
                            val tHr = System.currentTimeMillis()
                            if (tHs != null) {
                                val rtt = tHr - tHs
                                val off = msg.tClient - (tHs + rtt / 2)
                                offsetByPeer[peer.id] = off
                                logs.add("<- TimeSync ack seq=${msg.seq}; RTT=${rtt}ms; offset≈${off}ms from ${peer.name ?: peer.id}")
                            } else {
                                logs.add("<- TimeSync ack seq=${msg.seq}")
                            }
                        }
                    }
                    is NetMessage.Event -> {
                        if (msg.type == 1) {
                            // Start Match: payload = [hostGoAt: int64 little-endian, countdownSec: u8]
                            val bb = java.nio.ByteBuffer.wrap(msg.payload).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            val hostGoAt = if (bb.remaining() >= 8) bb.long else System.currentTimeMillis() + 4000
                            val countdownSec = if (bb.remaining() >= 1) (bb.get().toInt() and 0xFF) else 3
                            val est = offsetEstimateMs ?: 0L
                            val localGoAt = hostGoAt - est
                            core.net.NetSession.setMatchStartAt(localGoAt)
                            logs.add("<- Start Match: ${countdownSec}s, localGoAt=${localGoAt}")
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
        val role: Role = if (isHost) Role.Host else Role.Client
        val t = NearbyTransport(context, role, serviceId, name)
        transport = t
        core.net.NetSession.set(t)
        if (isHost) {
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
        if (isHost) {
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

    Scaffold(topBar = { TopAppBar(title = { Text("Multiplayer Lobby") }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Your name") })
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Host")
                    Switch(checked = isHost, onCheckedChange = { isHost = it })
                }
            }
            Text("Status: $status")
            if (!isHost) {
                Text("Time offset (≈): ${offsetEstimateMs?.let { "$it ms" } ?: "?"}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!running) Button(onClick = { startNearby(ctx) }) { Text("Start") } else Button(onClick = { stopNearby() }) { Text("Stop") }
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
            HorizontalDivider()
            Text("Peers (${peers.size})")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                items(peers) { p ->
                    val off = offsetByPeer[p.id]
                    Text("• ${p.name ?: p.id}${off?.let { "  (offset≈${it}ms)" } ?: ""}")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val t = transport
                Button(onClick = {
                    val bytes = NetCodec.encode(NetMessage.Ping((0..1_000_000).random()))
                    scope.launch {
                        val count = t?.broadcast(bytes) ?: 0
                        logs.add("-> broadcast Ping to $count peers")
                    }
                }, enabled = running) { Text("Broadcast Ping") }
                Button(onClick = {
                    val bytes = NetCodec.encode(NetMessage.Join(name, appVersion = 1))
                    scope.launch {
                        val count = t?.broadcast(bytes) ?: 0
                        logs.add("-> broadcast Join to $count peers")
                    }
                }, enabled = running) { Text("Broadcast Join") }
                if (isHost) {
                    Button(onClick = {
                        val countdownSec = 3
                        val hostGoAt = System.currentTimeMillis() + (countdownSec + 1) * 1000L
                        // payload: [hostGoAt(int64 LE), countdownSec(u8)]
                        val bb = java.nio.ByteBuffer.allocate(8 + 1).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        bb.putLong(hostGoAt)
                        bb.put(countdownSec.toByte())
                        val evt = NetCodec.encode(NetMessage.Event(type = 1, payload = bb.array()))
                        scope.launch {
                            val count = transport?.broadcast(evt) ?: 0
                            logs.add("-> Start Match sent to $count peers")
                            core.net.NetSession.setMatchStartAt(hostGoAt)
                        }
                    }, enabled = running) { Text("Start Match") }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(logs) { line -> Text(line) }
            }
        }
    }
}

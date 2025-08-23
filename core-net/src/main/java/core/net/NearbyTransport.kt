package core.net

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Nearby Connections transport (Strategy.P2P_STAR), serverless and offline.
 * Host advertises; Clients discover and request connection. Binary Payloads carry NetCodec‑encoded messages.
 */
class NearbyTransport(
    private val context: Context,
    override val role: Role,
    private val serviceId: String,
    name: String
) : Transport {
    override val localPeer: Peer = Peer(id = "local", name = name)
    private val client by lazy { Nearby.getConnectionsClient(context) }

    private val incomingFlow = MutableSharedFlow<Pair<Peer, ByteArray>>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val peers = ConcurrentHashMap<String, Peer>() // endpointId -> Peer
    private val peersFlow = MutableSharedFlow<List<Peer>>(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status

    private val payloadCb = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val p = peers[endpointId] ?: Peer(endpointId)
            peers[endpointId] = p
            incomingFlow.tryEmit(p to bytes)
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // no-op (we only use bytes, which are delivered atomically)
        }
    }

    private val connLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Auto-accept; production can show a prompt
            _status.value = "Conn initiated by ${info.endpointName}"
            client.acceptConnection(endpointId, payloadCb)
                .addOnSuccessListener { _status.value = "Accepted connection from ${info.endpointName}" }
                .addOnFailureListener { _status.value = "Accept failed: ${it.message}" }
            val p = Peer(id = endpointId, name = info.endpointName)
            peers[endpointId] = p
            emitPeers()
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode != ConnectionsStatusCodes.STATUS_OK) {
                _status.value = "Conn failed (${result.status.statusCode})"
                peers.remove(endpointId)
                emitPeers()
            } else {
                _status.value = "Connected (${peers.size} peers)"
            }
        }
        override fun onDisconnected(endpointId: String) {
            peers.remove(endpointId)
            emitPeers()
            _status.value = "Peer disconnected (${peers.size} peers)"
        }
    }

    private val endpointCb = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Client attempts to connect to host
            _status.value = "Found ${info.endpointName}; requesting connection"
            client.requestConnection(localPeer.name ?: "peer", endpointId, connLifecycle)
                .addOnSuccessListener { _status.value = "Request sent to ${info.endpointName}" }
                .addOnFailureListener { _status.value = "Request failed: ${it.message}" }
        }
        override fun onEndpointLost(endpointId: String) { /* ignore */ }
    }

    override fun incoming(): Flow<Pair<Peer, ByteArray>> = incomingFlow.asSharedFlow()
    override fun peers(): Flow<List<Peer>> = peersFlow.asSharedFlow()
    override val peersSnapshot: List<Peer> get() = peers.values.toList()

    override suspend fun start(): Boolean {
        return try {
            // Ensure clean state before (re)starting
            try { client.stopDiscovery() } catch (_: Throwable) {}
            try { client.stopAdvertising() } catch (_: Throwable) {}
            try { client.stopAllEndpoints() } catch (_: Throwable) {}
            if (role is Role.Host) startAdvertising() else startDiscovery()
            true
        } catch (_: Throwable) { false }
    }

    override suspend fun stop() {
        try { client.stopAdvertising() } catch (_: Throwable) {}
        try { client.stopDiscovery() } catch (_: Throwable) {}
        try { client.stopAllEndpoints() } catch (_: Throwable) {}
        peers.clear()
        emitPeers()
    }

    override suspend fun send(to: Peer, bytes: ByteArray): Boolean {
        val endpointId = to.id
        return try {
            client.sendPayload(endpointId, Payload.fromBytes(bytes))
            true
        } catch (_: Throwable) { false }
    }

    override suspend fun broadcast(bytes: ByteArray): Int {
        var count = 0
        for (endpointId in peers.keys) {
            try {
                client.sendPayload(endpointId, Payload.fromBytes(bytes))
                count++
            } catch (_: Throwable) {}
        }
        return count
    }

    private fun startAdvertising() {
        val opts = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        _status.value = "Starting advertising"
        // Ensure discovery is not active
        try { client.stopDiscovery() } catch (_: Throwable) {}
        client.startAdvertising(localPeer.name ?: "host", serviceId, connLifecycle, opts)
            .addOnSuccessListener { _status.value = "Advertising" }
            .addOnFailureListener { _status.value = "Advertise failed: ${it.message}" }
    }

    private fun startDiscovery() {
        val opts = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        _status.value = "Starting discovery"
        // Ensure advertising is not active
        try { client.stopAdvertising() } catch (_: Throwable) {}
        client.startDiscovery(serviceId, endpointCb, opts)
            .addOnSuccessListener { _status.value = "Discovering" }
            .addOnFailureListener { _status.value = "Discover failed: ${it.message}" }
    }

    private fun emitPeers() {
        peersFlow.tryEmit(peers.values.toList())
    }
}

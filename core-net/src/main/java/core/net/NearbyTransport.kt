package core.net

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
            client.acceptConnection(endpointId, payloadCb)
            val p = Peer(id = endpointId, name = info.endpointName)
            peers[endpointId] = p
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode != ConnectionsStatusCodes.STATUS_OK) {
                peers.remove(endpointId)
            }
        }
        override fun onDisconnected(endpointId: String) {
            peers.remove(endpointId)
        }
    }

    private val endpointCb = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Client attempts to connect to host
            client.requestConnection(localPeer.name ?: "peer", endpointId, connLifecycle)
        }
        override fun onEndpointLost(endpointId: String) { /* ignore */ }
    }

    override fun incoming(): Flow<Pair<Peer, ByteArray>> = incomingFlow.asSharedFlow()

    override suspend fun start(): Boolean {
        return try {
            if (role is Role.Host) startAdvertising() else startDiscovery()
            true
        } catch (_: Throwable) { false }
    }

    override suspend fun stop() {
        try { client.stopAdvertising() } catch (_: Throwable) {}
        try { client.stopDiscovery() } catch (_: Throwable) {}
        try { client.stopAllEndpoints() } catch (_: Throwable) {}
        peers.clear()
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
        client.startAdvertising(localPeer.name ?: "host", serviceId, connLifecycle, opts)
    }

    private fun startDiscovery() {
        val opts = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        client.startDiscovery(serviceId, endpointCb, opts)
    }
}


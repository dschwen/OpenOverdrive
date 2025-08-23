package core.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class HostService(private val transport: Transport) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        check(transport.role is Role.Host)
        job = scope.launch {
            transport.start()
            transport.incoming().collect { (peer, bytes) ->
                when (val msg = NetCodec.decode(bytes)) {
                    is NetMessage.Join -> { /* register peer */ }
                    is NetMessage.TimeSync -> { /* reply with tHost and seq */ }
                    is NetMessage.Input -> { /* enqueue input for world tick */ }
                    is NetMessage.CarTelemetry -> { /* update telemetry cache */ }
                    is NetMessage.Ping -> { /* optional: echo */ }
                    else -> {}
                }
            }
        }
    }

    suspend fun stop() {
        job?.cancel()
        transport.stop()
    }
}

class ClientService(private val transport: Transport) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        check(transport.role is Role.Client)
        job = scope.launch {
            transport.start()
            transport.incoming().collect { (_peer, bytes) ->
                when (val msg = NetCodec.decode(bytes)) {
                    is NetMessage.WorldState -> { /* apply world */ }
                    is NetMessage.Event -> { /* apply event */ }
                    is NetMessage.TimeSync -> { /* compute RTT/offset; reply */ }
                    else -> {}
                }
            }
        }
    }

    suspend fun stop() {
        job?.cancel()
        transport.stop()
    }
}


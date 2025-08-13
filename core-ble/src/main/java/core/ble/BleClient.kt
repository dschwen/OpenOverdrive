package core.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val address: String) : ConnectionState
    data class Connected(val address: String) : ConnectionState
}

interface BleClient {
    val connectionState: StateFlow<ConnectionState>

    fun scanForAnkiDevices(): Flow<List<BleDevice>>

    suspend fun connect(address: String)
    suspend fun disconnect()

    suspend fun enableNotifications(): Boolean
    fun notifications(): Flow<ByteArray>

    suspend fun write(bytes: ByteArray, withResponse: Boolean = false): Boolean
}

/**
 * Temporary in-memory fake for UI scaffolding. Replace with AndroidBleClient.
 */
class FakeBleClient : BleClient {
    private val _conn = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _conn

    override fun scanForAnkiDevices(): Flow<List<BleDevice>> = kotlinx.coroutines.flow.flow {
        emit(listOf(BleDevice("AA:BB:CC:DD:EE:FF", "AnkiCar-01", -55)))
    }

    override suspend fun connect(address: String) {
        _conn.value = ConnectionState.Connecting(address)
        _conn.value = ConnectionState.Connected(address)
    }

    override suspend fun disconnect() { _conn.value = ConnectionState.Disconnected }

    override suspend fun enableNotifications(): Boolean = true

    override fun notifications(): Flow<ByteArray> = kotlinx.coroutines.flow.emptyFlow()

    override suspend fun write(bytes: ByteArray, withResponse: Boolean): Boolean = true
}


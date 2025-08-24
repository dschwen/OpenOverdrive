package core.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import android.bluetooth.BluetoothStatusCodes

/**
 * Android BLE client implementing scanning, connect/GATT, notifications, and queued writes.
 * Caller must ensure runtime permissions are granted before using.
 */
class AndroidBleClient(private val context: Context) : BleClient {
    private val appScope = CoroutineScope(Dispatchers.IO)

    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private var scanner: BluetoothLeScanner? = null
    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connection.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var readChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    private val notifyFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val writeQueue = Channel<WriteReq>(capacity = Channel.UNLIMITED)

    private data class WriteReq(val data: ByteArray, val withResponse: Boolean, val cont: (Boolean) -> Unit)

    private val discoveredDevices = ConcurrentHashMap<String, BleDevice>()
    private val lastSeenMs = ConcurrentHashMap<String, Long>()

    override fun scanForAnkiDevices(): Flow<List<BleDevice>> = callbackFlow {
        val adapter = adapter ?: run { trySend(emptyList()); awaitClose { }; return@callbackFlow }
        scanner = adapter.bluetoothLeScanner
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(AnkiUuids.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val key = dev.address ?: return
                val entry = BleDevice(address = key, name = resolveName(result), rssi = result.rssi)
                discoveredDevices[key] = entry
                lastSeenMs[key] = System.currentTimeMillis()
                trySend(discoveredDevices.values.sortedBy { it.name ?: it.address }.toList())
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                var changed = false
                for (r in results) {
                    val dev = r.device ?: continue
                    val key = dev.address ?: continue
                    val entry = BleDevice(address = key, name = resolveName(r), rssi = r.rssi)
                    discoveredDevices[key] = entry
                    lastSeenMs[key] = System.currentTimeMillis()
                    changed = true
                }
                if (changed) trySend(discoveredDevices.values.sortedBy { it.name ?: it.address }.toList())
            }
            override fun onScanFailed(errorCode: Int) {
                // Emit empty on failure; UI can show state
                trySend(discoveredDevices.values.sortedBy { it.name ?: it.address }.toList())
            }
        }
        scanner?.startScan(listOf(filter), settings, callback)
        // Periodically prune devices that haven't been seen recently (disconnected or out of range)
        val pruneJob = launch {
            while (true) {
                kotlinx.coroutines.delay(2000)
                val now = System.currentTimeMillis()
                var changed = false
                val it = lastSeenMs.entries.iterator()
                while (it.hasNext()) {
                    val (addr, ts) = it.next()
                    if (now - ts > 6000) {
                        it.remove()
                        discoveredDevices.remove(addr)
                        changed = true
                    }
                }
                if (changed) trySend(discoveredDevices.values.sortedBy { it.name ?: it.address }.toList())
            }
        }
        awaitClose {
            try { scanner?.stopScan(callback) } catch (_: Throwable) {}
            discoveredDevices.clear()
            lastSeenMs.clear()
            try { pruneJob.cancel() } catch (_: Throwable) {}
        }
    }

    private fun resolveName(result: ScanResult): String? {
        // Prefer model from manufacturer data when available, as some cars
        // advertise corrupted/garbled names on certain phones.
        val sr = result.scanRecord
        val msd = sr?.manufacturerSpecificData
        val COMPANY_ID = 61374 // see PROTOCOL-REVERSE.md
        var model: String? = null
        if (msd != null) {
            val preferred = msd.get(COMPANY_ID)
            val modelId: Int? = when {
                preferred != null && preferred.size >= 2 -> preferred[1].toInt() and 0xFF
                else -> {
                    // Fallback: scan any MSD entry and try byte[1] as model id
                    var id: Int? = null
                    for (i in 0 until msd.size()) {
                        val data = msd.valueAt(i) ?: continue
                        if (data.size >= 2) { id = data[1].toInt() and 0xFF; break }
                    }
                    id
                }
            }
            model = when (modelId) {
            1 -> "Kourai"
            2 -> "Boson"
            3 -> "Rho"
            4 -> "Katal"
            5 -> "Hadion"
            6 -> "Spektrix"
            7 -> "Corax"
            8 -> "GroundShock"
            9 -> "Skull"
            10 -> "Thermo"
            11 -> "Nuke"
            12 -> "Guardian"
            14 -> "Bigbang"
            15 -> "Freewheel"
            16 -> "x52"
            17 -> "x52 Ice"
            18 -> "Mammoth"
            19 -> "Dynamo"
            20 -> "Nuke Phantom"
                else -> null
            }
        }
        if (!model.isNullOrBlank()) return model

        // Fall back to advertised name if model not resolved
        val advertisedName = result.device?.name ?: sr?.deviceName
        // Guard against obviously broken names (very short prefixes before "Drive")
        if (!advertisedName.isNullOrBlank()) {
            val safe = advertisedName.trim()
            val suspicious = run {
                val idx = safe.indexOf("Drive")
                if (idx > 0) {
                    val prefix = safe.substring(0, idx).trim()
                    prefix.length <= 3 || prefix.any { ch -> !(ch.isLetter() || ch == ' ' || ch == '-' || ch == '\'') }
                } else false
            }
            if (!suspicious) return safe
        }
        return null
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(address: String) {
        val device = try { adapter?.getRemoteDevice(address) } catch (_: IllegalArgumentException) { null }
        requireNotNull(device) { "Invalid device address: $address" }
        _connection.value = ConnectionState.Connecting(address)

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    cleanup()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service = gatt.getService(AnkiUuids.SERVICE)
                val r = service?.getCharacteristic(AnkiUuids.READ_CHAR)
                val w = service?.getCharacteristic(AnkiUuids.WRITE_CHAR)
                readChar = r
                writeChar = w
                _connection.value = ConnectionState.Connected(gatt.device.address)
                // Start writer loop if not started
                appScope.launch { processWriteQueue() }
                // Request MTU for better throughput
                try { gatt.requestMtu(185) } catch (_: Throwable) {}
                // Try enabling notifications automatically
                appScope.launch {
                    try { enableNotifications() } catch (_: Throwable) {}
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                // No-op
            }

            @Deprecated("Legacy callback for API < 33")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == AnkiUuids.READ_CHAR) {
                    characteristic.value?.let { bytes -> notifyFlow.tryEmit(bytes.copyOf()) }
                }
            }

            // API 33+ signature
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (characteristic.uuid == AnkiUuids.READ_CHAR) {
                    notifyFlow.tryEmit(value.copyOf())
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                pendingWriteContinuation?.invoke(status == BluetoothGatt.GATT_SUCCESS)
                pendingWriteContinuation = null
            }
        }

        gatt = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect() {
        cleanup()
    }

    @SuppressLint("MissingPermission")
    override suspend fun enableNotifications(): Boolean {
        val g = gatt ?: return false
        val r = readChar ?: return false
        g.setCharacteristicNotification(r, true)
        val cccd = r.getDescriptor(CCCD_UUID) ?: return false
        return suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= 33) {
                // Preferred API 33+ path without using deprecated cccd.value
                val status = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (status == BluetoothStatusCodes.SUCCESS) {
                    appScope.launch { cont.resume(true) }
                } else {
                    // Fallback to indications
                    val status2 = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                    if (status2 == BluetoothStatusCodes.SUCCESS) appScope.launch { cont.resume(true) } else cont.resume(false)
                }
            } else {
                @Suppress("DEPRECATION")
                run {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    val ok = g.writeDescriptor(cccd)
                    if (!ok) {
                        try {
                            @Suppress("DEPRECATION")
                            run { cccd.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE }
                            @Suppress("DEPRECATION")
                            val ok2 = g.writeDescriptor(cccd)
                            if (!ok2) cont.resume(false) else appScope.launch { cont.resume(true) }
                        } catch (_: Throwable) {
                            cont.resume(false)
                        }
                    } else appScope.launch { cont.resume(true) }
                }
            }
        }
    }

    override fun notifications(): Flow<ByteArray> = notifyFlow.asSharedFlow()

    private var pendingWriteContinuation: ((Boolean) -> Unit)? = null

    @SuppressLint("MissingPermission")
    override suspend fun write(bytes: ByteArray, withResponse: Boolean): Boolean {
        return suspendCancellableCoroutine { cont ->
            val offered = writeQueue.trySend(WriteReq(bytes, withResponse) { success -> cont.resume(success) })
            if (!offered.isSuccess) cont.resume(false)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun processWriteQueue() {
        for (req in writeQueue) {
            val g = gatt
            val w = writeChar
            if (g == null || w == null) {
                req.cont(false)
                continue
            }
            val success = writeOnce(g, w, req.data, req.withResponse)
            req.cont(success)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeOnce(g: BluetoothGatt, w: BluetoothGattCharacteristic, data: ByteArray, withResponse: Boolean): Boolean {
        val supportsResp = (w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val supportsNoResp = (w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        val type = when {
            withResponse && supportsResp -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            !withResponse && supportsNoResp -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            supportsResp -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            supportsNoResp -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(w, data, type) == BluetoothStatusCodes.SUCCESS
        } else {
            w.writeType = type
            @Suppress("DEPRECATION")
            w.value = data
            pendingWriteContinuation = { /* legacy path doesn't get callback status reliably */ }
            @Suppress("DEPRECATION")
            g.writeCharacteristic(w)
        }
    }

    @SuppressLint("MissingPermission")
    private fun cleanup() {
        _connection.value = ConnectionState.Disconnected
        try { gatt?.disconnect() } catch (_: Throwable) {}
        try { gatt?.close() } catch (_: Throwable) {}
        gatt = null
        readChar = null
        writeChar = null
    }

    companion object {
        val CCCD_UUID = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

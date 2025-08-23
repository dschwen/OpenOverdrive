package core.net

import java.nio.ByteBuffer
import java.nio.ByteOrder

// Very small binary header: [type:u8, ...payload]
object MsgType {
    const val JOIN: Byte = 1
    const val TIMESYNC: Byte = 2
    const val INPUT: Byte = 3
    const val CAR_TELEMETRY: Byte = 4
    const val WORLD_STATE: Byte = 5
    const val EVENT: Byte = 6
    const val PING: Byte = 7
}

sealed interface NetMessage {
    data class Join(val name: String?, val appVersion: Int) : NetMessage
    data class TimeSync(val tHost: Long, val seq: Int, val tClient: Long? = null) : NetMessage
    data class Input(val throttle: Int, val laneChange: Int, val fire: Boolean, val tsClient: Long) : NetMessage
    data class CarTelemetry(
        val pieceId: Int, val locationId: Int, val offsetMm: Float, val speedMmps: Int, val flags: Int, val tsClient: Long
    ) : NetMessage
    data class WorldState(val tick: Int) : NetMessage // placeholder; expand as needed
    data class Event(val type: Int, val payload: ByteArray) : NetMessage
    data class Ping(val nonce: Int) : NetMessage
}

object NetCodec {
    fun encode(msg: NetMessage): ByteArray = when (msg) {
        is NetMessage.Join -> {
            val nameBytes = (msg.name ?: "").encodeToByteArray()
            val bb = bb(1 + 2 + nameBytes.size + 4)
            bb.put(MsgType.JOIN)
            bb.putShort(nameBytes.size.toShort())
            bb.put(nameBytes)
            bb.putInt(msg.appVersion)
            bb.array()
        }
        is NetMessage.TimeSync -> {
            val bb = bb(1 + 8 + 4 + 8)
            bb.put(MsgType.TIMESYNC)
            bb.putLong(msg.tHost)
            bb.putInt(msg.seq)
            bb.putLong(msg.tClient ?: 0L)
            bb.array()
        }
        is NetMessage.Input -> {
            val bb = bb(1 + 4 + 4 + 1 + 8)
            bb.put(MsgType.INPUT)
            bb.putInt(msg.throttle)
            bb.putInt(msg.laneChange)
            bb.put((if (msg.fire) 1 else 0).toByte())
            bb.putLong(msg.tsClient)
            bb.array()
        }
        is NetMessage.CarTelemetry -> {
            val bb = bb(1 + 4 + 4 + 4 + 4 + 4 + 8)
            bb.put(MsgType.CAR_TELEMETRY)
            bb.putInt(msg.pieceId)
            bb.putInt(msg.locationId)
            bb.putFloat(msg.offsetMm)
            bb.putInt(msg.speedMmps)
            bb.putInt(msg.flags)
            bb.putLong(msg.tsClient)
            bb.array()
        }
        is NetMessage.WorldState -> {
            val bb = bb(1 + 4)
            bb.put(MsgType.WORLD_STATE)
            bb.putInt(msg.tick)
            bb.array()
        }
        is NetMessage.Event -> {
            val bb = bb(1 + 4 + 2 + msg.payload.size)
            bb.put(MsgType.EVENT)
            bb.putInt(msg.type)
            bb.putShort(msg.payload.size.toShort())
            bb.put(msg.payload)
            bb.array()
        }
        is NetMessage.Ping -> {
            val bb = bb(1 + 4)
            bb.put(MsgType.PING)
            bb.putInt(msg.nonce)
            bb.array()
        }
    }

    fun decode(bytes: ByteArray): NetMessage? {
        if (bytes.isEmpty()) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return when (bb.get()) {
            MsgType.JOIN -> {
                val n = bb.short.toInt() and 0xFFFF
                val name = if (n > 0) ByteArray(n).also { bb.get(it) }.toString(Charsets.UTF_8) else null
                val ver = bb.int
                NetMessage.Join(name, ver)
            }
            MsgType.TIMESYNC -> NetMessage.TimeSync(bb.long, bb.int, bb.long)
            MsgType.INPUT -> NetMessage.Input(bb.int, bb.int, (bb.get().toInt() and 1) != 0, bb.long)
            MsgType.CAR_TELEMETRY -> NetMessage.CarTelemetry(bb.int, bb.int, bb.float, bb.int, bb.int, bb.long)
            MsgType.WORLD_STATE -> NetMessage.WorldState(bb.int)
            MsgType.EVENT -> {
                val type = bb.int
                val len = bb.short.toInt() and 0xFFFF
                val payload = ByteArray(len)
                bb.get(payload)
                NetMessage.Event(type, payload)
            }
            MsgType.PING -> NetMessage.Ping(bb.int)
            else -> null
        }
    }

    private fun bb(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}


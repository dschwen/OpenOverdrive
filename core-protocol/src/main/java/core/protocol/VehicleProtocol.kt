package core.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object MsgId {
    const val C2V_DISCONNECT: Byte = 0x0D
    const val C2V_PING: Byte = 0x16.toByte()
    const val V2C_PING_RESP: Byte = 0x17
    const val C2V_VERSION_REQ: Byte = 0x18
    const val V2C_VERSION_RESP: Byte = 0x19
    const val C2V_BATTERY_REQ: Byte = 0x1A
    const val V2C_BATTERY_RESP: Byte = 0x1B
    const val C2V_SET_LIGHTS: Byte = 0x1D
    const val C2V_SET_SPEED: Byte = 0x24
    const val C2V_CHANGE_LANE: Byte = 0x25
    const val C2V_CANCEL_LANE: Byte = 0x26
    const val V2C_LOC_POS: Byte = 0x27
    const val V2C_LOC_TRANS: Byte = 0x29
    const val V2C_LOC_INTERSECT: Byte = 0x2A
    const val C2V_SET_OFFSET: Byte = 0x2C
    const val V2C_OFFSET_UPDATE: Byte = 0x2D
    const val C2V_TURN: Byte = 0x32
    const val C2V_LIGHTS_PATTERN: Byte = 0x33
    const val C2V_SET_CONFIG: Byte = 0x45
    const val C2V_SDK_MODE: Byte = 0x90.toByte()
}

object SdkFlags { const val OVERRIDE_LOCALIZATION: Byte = 0x01 }

/** Message builders (little-endian), matching the C SDK layout. */
object VehicleMsg {
    fun sdkMode(on: Boolean, flags: Byte = SdkFlags.OVERRIDE_LOCALIZATION): ByteArray {
        val bb = bb(1 + 1 + 2)
        bb.put(3) // size of msg_id + payload
        bb.put(MsgId.C2V_SDK_MODE)
        bb.put(if (on) 1 else 0)
        bb.put(flags)
        return bb.array()
    }

    fun setSpeed(speedMmPerSec: Int, accelMmPerSec2: Int, respectLimit: Byte = 0): ByteArray {
        val bb = bb(1 + 1 + 5)
        bb.put(6) // payload size
        bb.put(MsgId.C2V_SET_SPEED)
        bb.putShort(speedMmPerSec.toShort())
        bb.putShort(accelMmPerSec2.toShort())
        bb.put(respectLimit)
        return bb.array()
    }

    fun setOffsetFromCenter(offsetMm: Float): ByteArray {
        val bb = bb(1 + 1 + 4)
        bb.put(5)
        bb.put(MsgId.C2V_SET_OFFSET)
        bb.putFloat(offsetMm)
        return bb.array()
    }

    fun changeLane(
        hSpeedMmPerSec: Int,
        hAccelMmPerSec2: Int,
        offsetFromCenterMm: Float,
        hopIntent: Byte = 0,
        tag: Byte = 0
    ): ByteArray {
        val bb = bb(1 + 1 + 10)
        bb.put(11)
        bb.put(MsgId.C2V_CHANGE_LANE)
        bb.putShort(hSpeedMmPerSec.toShort())
        bb.putShort(hAccelMmPerSec2.toShort())
        bb.putFloat(offsetFromCenterMm)
        bb.put(hopIntent)
        bb.put(tag)
        return bb.array()
    }

    fun batteryRequest(): ByteArray = simple(MsgId.C2V_BATTERY_REQ)
    fun versionRequest(): ByteArray = simple(MsgId.C2V_VERSION_REQ)
    fun ping(): ByteArray = simple(MsgId.C2V_PING)
    fun disconnect(): ByteArray = simple(MsgId.C2V_DISCONNECT)
    fun cancelLane(): ByteArray = simple(MsgId.C2V_CANCEL_LANE)

    private fun simple(id: Byte): ByteArray {
        val bb = bb(1 + 1)
        bb.put(1)
        bb.put(id)
        return bb.array()
    }

    private fun bb(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}

sealed interface VehicleMessage {
    data class BatteryLevel(val raw: Int) : VehicleMessage {
        // Some firmware report battery in millivolts (e.g., 3300..4200), others as 0..100.
        // Heuristic: if raw looks like mV, map 3.3V..4.2V to 0..100%; otherwise use low byte as percent.
        val percent: Int = when {
            raw in 2500..5000 -> (((raw - 3300).coerceIn(0, 900)) * 100 / 900)
            else -> (raw and 0xFF).coerceIn(0, 100)
        }
    }
    data class Version(val version: Int) : VehicleMessage
    data class PositionUpdate(
        val locationId: Int,
        val roadPieceId: Int,
        val offsetFromCenter: Float,
        val speedMmPerSec: Int,
        val clockwise: Boolean,
    ) : VehicleMessage
    data class TransitionUpdate(val roadPieceIdx: Int, val roadPieceIdxPrev: Int) : VehicleMessage
}

object VehicleMsgParser {
    fun parse(bytes: ByteArray): VehicleMessage? {
        if (bytes.size < 2) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val size = (bb.get().toInt() and 0xFF) // payload size
        if (size + 1 > bytes.size) return null
        val id = bb.get()
        return when (id) {
            MsgId.V2C_BATTERY_RESP -> {
                val level = bb.short.toInt() and 0xFFFF
                VehicleMessage.BatteryLevel(level)
            }
            MsgId.V2C_VERSION_RESP -> {
                val version = bb.short.toInt() and 0xFFFF
                VehicleMessage.Version(version)
            }
            MsgId.V2C_LOC_POS -> {
                // Layout: uint8 locationId, uint8 piece, float offset_mm, uint16 speed_mmps, uint8 clockwise
                if (size >= 9) {
                    val locationId = bb.get().toInt() and 0xFF
                    val roadPieceId = bb.get().toInt() and 0xFF
                    val offset = bb.float
                    val speed = bb.short.toInt() and 0xFFFF
                    val clockwise = (bb.get().toInt() and 0xFF) != 0
                    VehicleMessage.PositionUpdate(locationId, roadPieceId, offset, speed, clockwise)
                } else null
            }
            MsgId.V2C_LOC_TRANS -> {
                if (size >= 17) {
                    val roadPieceIdx = bb.get().toInt() // int8
                    val prev = bb.get().toInt()
                    bb.float // offset
                    bb.get() // last_recv_lane_change_id
                    bb.get() // last_exec_lane_change_id
                    bb.short // desired_lane_change_speed
                    bb.get() // ave_follow_line_drift_pixels
                    bb.get() // had_lane_change_activity
                    bb.get() // uphill
                    bb.get() // downhill
                    bb.get() // left_wheel_dist
                    bb.get() // right_wheel_dist
                    VehicleMessage.TransitionUpdate(roadPieceIdx, prev)
                } else null
            }
            else -> null
        }
    }
}

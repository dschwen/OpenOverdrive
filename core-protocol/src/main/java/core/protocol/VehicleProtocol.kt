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
    const val V2C_STATUS: Byte = 0x3F
    const val C2V_SET_CONFIG: Byte = 0x45
    const val C2V_SDK_MODE: Byte = 0x90.toByte()
}

object SdkFlags { const val OVERRIDE_LOCALIZATION: Byte = 0x01 }

object LightChannel {
    const val RED: Byte = 0
    const val TAIL: Byte = 1
    const val BLUE: Byte = 2
    const val GREEN: Byte = 3
    const val FRONTL: Byte = 4
    const val FRONTR: Byte = 5
}

object LightEffect {
    const val STEADY: Byte = 0
    const val FADE: Byte = 1
    const val THROB: Byte = 2
    const val FLASH: Byte = 3
    const val RANDOM: Byte = 4
}

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

    /** V4-compatible Set Speed packet: adds a trailing byte (value 0x01). */
    fun setSpeedV4(speedMmPerSec: Int, accelMmPerSec2: Int, respectLimit: Byte = 0): ByteArray {
        val bb = bb(1 + 1 + 6)
        bb.put(7) // payload size including id (0x24) + 6 bytes
        bb.put(MsgId.C2V_SET_SPEED)
        bb.putShort(speedMmPerSec.toShort())
        bb.putShort(accelMmPerSec2.toShort())
        bb.put(respectLimit)
        bb.put(0x01)
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

    /**
     * Build a lights pattern (0x33) packet.
     * Each entry is (channel, effect, startStrength, endStrength, cyclesPer10Seconds).
     * Channel values: see LightChannel; Effect: see LightEffect. Strength is 0..255.
     */
    fun setLightsPattern(vararg entries: ByteArray): ByteArray {
        require(entries.isNotEmpty()) { "At least one light entry is required" }
        require(entries.all { it.size == 5 }) { "Each entry must be exactly 5 bytes" }
        val count = entries.size
        val payloadSize = 1 /*id*/ + 1 /*count*/ + (count * 5)
        val bb = bb(1 + payloadSize)
        bb.put((payloadSize).toByte())
        bb.put(MsgId.C2V_LIGHTS_PATTERN)
        bb.put(count.toByte())
        entries.forEach { bb.put(it) }
        return bb.array()
    }

    /** Convenience: steady RGB for engine LEDs. Strengths 0..255. */
    fun setEngineRgb(r: Int, g: Int, b: Int): ByteArray {
        fun entry(channel: Byte, strength: Int): ByteArray = byteArrayOf(
            channel,
            LightEffect.STEADY,
            strength.coerceIn(0, 255).toByte(),
            0x00,
            0x00
        )
        return setLightsPattern(
            entry(LightChannel.RED, r),
            entry(LightChannel.GREEN, g),
            entry(LightChannel.BLUE, b)
        )
    }

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
        val parsingFlags: Int? = null,
        val lastRecvdLaneChangeCmdId: Int? = null,
        val lastExecutedLaneChangeCmdId: Int? = null,
        val lastDesiredLaneChangeSpeedMmps: Int? = null,
        val lastDesiredSpeedMmps: Int? = null,
    ) : VehicleMessage
    data class TransitionUpdate(
        val roadPieceIdx: Int,
        val roadPieceIdxPrev: Int,
        val offsetFromCenter: Float? = null,
        val lastRecvdLaneChangeCmdId: Int? = null,
        val lastExecutedLaneChangeCmdId: Int? = null,
        val lastDesiredLaneChangeSpeedMmps: Int? = null,
        val avgFollowLineDrift: Int? = null,
        val hadLaneChangeActivity: Int? = null,
        val uphillCounter: Int? = null,
        val downhillCounter: Int? = null,
        val leftWheelDistanceCm: Int? = null,
        val rightWheelDistanceCm: Int? = null,
    ) : VehicleMessage
    data class CarStatus(
        val onTrack: Boolean,
        val onCharger: Boolean,
        val lowBattery: Boolean,
        val chargedBattery: Boolean,
    ) : VehicleMessage
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
                // Layout per PROTOCOL-REVERSE.md:
                // [2] locationId (u8), [3] pieceId (u8), [4..7] offset (float mm), [8..9] speed (u16),
                // [10] parsingFlags (u8), [11] lastRecvLCId (u8), [12] lastExecLCId (u8),
                // [13..14] lastDesiredLCSpeed (u16), [15..16] lastDesiredSpeed (i16)
                if (bb.remaining() >= 1 + 1 + 4 + 2) {
                    val locationId = bb.get().toInt() and 0xFF
                    val roadPieceId = bb.get().toInt() and 0xFF
                    val offset = bb.float
                    val speed = bb.short.toInt() and 0xFFFF
                    var flags: Int? = null
                    var lastRecv: Int? = null
                    var lastExec: Int? = null
                    var lastLcSpeed: Int? = null
                    var lastDesiredSpeed: Int? = null
                    if (bb.remaining() >= 1) flags = bb.get().toInt() and 0xFF
                    if (bb.remaining() >= 1) lastRecv = bb.get().toInt() and 0xFF
                    if (bb.remaining() >= 1) lastExec = bb.get().toInt() and 0xFF
                    if (bb.remaining() >= 2) lastLcSpeed = bb.short.toInt() and 0xFFFF
                    if (bb.remaining() >= 2) lastDesiredSpeed = bb.short.toInt()
                    VehicleMessage.PositionUpdate(
                        locationId,
                        roadPieceId,
                        offset,
                        speed,
                        flags,
                        lastRecv,
                        lastExec,
                        lastLcSpeed,
                        lastDesiredSpeed,
                    )
                } else null
            }
            MsgId.V2C_LOC_TRANS -> {
                // Layout per PROTOCOL-REVERSE.md (subset decoded)
                if (bb.remaining() >= 2) {
                    val roadPieceIdx = bb.get().toInt()
                    val prev = bb.get().toInt()
                    var offset: Float? = null
                    var lastRecv: Int? = null
                    var lastExec: Int? = null
                    var lastLcSpeed: Int? = null
                    var drift: Int? = null
                    var laneAct: Int? = null
                    var up: Int? = null
                    var down: Int? = null
                    var left: Int? = null
                    var right: Int? = null
                    if (bb.remaining() >= 4) offset = bb.float
                    if (bb.remaining() >= 1) lastRecv = bb.get().toInt() and 0xFF
                    if (bb.remaining() >= 1) lastExec = bb.get().toInt() and 0xFF
                    if (bb.remaining() >= 2) lastLcSpeed = bb.short.toInt() and 0xFFFF
                    if (bb.remaining() >= 1) drift = bb.get().toInt() and 0xFF
                    if (bb.remaining() >= 1) laneAct = bb.get().toInt() and 0xFF
                    if (bb.remaining() >= 1) up = bb.get().toInt() and 0xFF
                    if (bb.remaining() >= 1) down = bb.get().toInt() and 0xFF
                    if (bb.remaining() >= 1) left = bb.get().toInt() and 0xFF
                    if (bb.remaining() >= 1) right = bb.get().toInt() and 0xFF
                    VehicleMessage.TransitionUpdate(
                        roadPieceIdx,
                        prev,
                        offset,
                        lastRecv,
                        lastExec,
                        lastLcSpeed,
                        drift,
                        laneAct,
                        up,
                        down,
                        left,
                        right,
                    )
                } else null
            }
            MsgId.V2C_STATUS -> {
                // Bytes (after id): onTrack, onCharger, lowBattery, chargedBattery (LSB of each)
                if (size >= 5) {
                    val onTrack = (bb.get().toInt() and 0x01) != 0
                    val onCharger = (bb.get().toInt() and 0x01) != 0
                    val low = (bb.get().toInt() and 0x01) != 0
                    val charged = (bb.get().toInt() and 0x01) != 0
                    VehicleMessage.CarStatus(onTrack, onCharger, low, charged)
                } else null
            }
            else -> null
        }
    }
}

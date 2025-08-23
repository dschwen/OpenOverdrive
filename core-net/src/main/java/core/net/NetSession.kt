package core.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object NetSession {
    private val _transport = MutableStateFlow<Transport?>(null)
    val transport: StateFlow<Transport?> = _transport

    fun set(transport: Transport?) { _transport.value = transport }

    // Local clock timestamp when "GO" happens (milliseconds since epoch). Null when no match running.
    private val _matchStartAtMs = MutableStateFlow<Long?>(null)
    val matchStartAtMs: StateFlow<Long?> = _matchStartAtMs

    fun setMatchStartAt(startAtLocalMs: Long?) { _matchStartAtMs.value = startAtLocalMs }

    // Target laps for the current match (null when not set)
    private val _targetLaps = MutableStateFlow<Int?>(null)
    val targetLaps: StateFlow<Int?> = _targetLaps

    fun setTargetLaps(laps: Int?) { _targetLaps.value = laps }
}

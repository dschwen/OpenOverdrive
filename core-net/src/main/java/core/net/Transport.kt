package core.net

import kotlinx.coroutines.flow.Flow

sealed interface Role { data object Host: Role; data object Client: Role }

data class Peer(val id: String, val name: String? = null)

interface Transport {
    val role: Role
    val localPeer: Peer

    // Emits raw payloads from remote peers. First byte should be a message type code.
    fun incoming(): Flow<Pair<Peer, ByteArray>>

    suspend fun start(): Boolean
    suspend fun stop()

    suspend fun send(to: Peer, bytes: ByteArray): Boolean
    suspend fun broadcast(bytes: ByteArray): Int
}


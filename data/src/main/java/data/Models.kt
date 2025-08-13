package data

data class CarProfile(
    val deviceAddress: String,
    val displayName: String? = null,
    val colorArgb: Int? = null,
    val lastSeenName: String? = null,
    val lastConnected: Long? = null,
    val startRoadPieceId: Int? = null
)


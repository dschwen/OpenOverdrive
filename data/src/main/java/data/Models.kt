package data

data class CarProfile(
    val deviceAddress: String,
    val displayName: String? = null,
    val colorArgb: Int? = null,
    val colorStartArgb: Int? = null,
    val colorEndArgb: Int? = null,
    val lastSeenName: String? = null,
    val lastConnected: Long? = null,
    val startRoadPieceId: Int? = null,
    val autoConnect: Boolean = false
)

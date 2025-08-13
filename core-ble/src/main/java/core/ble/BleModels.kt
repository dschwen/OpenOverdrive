package core.ble

import java.util.UUID

data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int?
)

object AnkiUuids {
    val SERVICE: UUID = UUID.fromString("BE15BEEF-6186-407E-8381-0BD89C4D8DF4")
    val READ_CHAR: UUID = UUID.fromString("BE15BEE0-6186-407E-8381-0BD89C4D8DF4")
    val WRITE_CHAR: UUID = UUID.fromString("BE15BEE1-6186-407E-8381-0BD89C4D8DF4")
}


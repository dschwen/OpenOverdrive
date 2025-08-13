package app.openoverdrive

import android.content.Context
import core.ble.AndroidBleClient
import core.ble.BleClient

object BleProvider {
    lateinit var client: BleClient
        private set

    fun init(context: Context) {
        if (!::client.isInitialized) {
            client = AndroidBleClient(context.applicationContext)
        }
    }
}


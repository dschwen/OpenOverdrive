package de.schwen.openoverdrive

import android.app.Application
import core.ble.AndroidBleClient

class OpenOverdriveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BleProvider.init(this)
    }
}

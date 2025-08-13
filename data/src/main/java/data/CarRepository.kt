package data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import core.ble.BleClient
import core.ble.BleDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "car_profiles")

class CarRepository(private val context: Context, private val ble: BleClient) {
    private val KEY = stringPreferencesKey("profiles_json")

    val profilesFlow: Flow<List<CarProfile>> = context.dataStore.data.map { prefs: Preferences ->
        val raw = prefs[KEY]
        if (raw != null) decodeProfiles(raw) else emptyList()
    }

    fun visibleWithProfiles(devicesFlow: Flow<List<BleDevice>>): Flow<List<Pair<BleDevice, CarProfile?>>> =
        combine(devicesFlow, profilesFlow) { devices, profiles ->
            val byAddr = profiles.associateBy { it.deviceAddress }
            devices.map { it to byAddr[it.address] }
        }

    suspend fun upsertProfile(profile: CarProfile) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY]?.let { decodeProfiles(it) } ?: emptyList()
            val next = current.filterNot { it.deviceAddress == profile.deviceAddress } + profile
            prefs[KEY] = encodeProfiles(next)
        }
    }

    suspend fun removeProfile(address: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY]?.let { decodeProfiles(it) } ?: emptyList()
            val next = current.filterNot { it.deviceAddress == address }
            prefs[KEY] = encodeProfiles(next)
        }
    }

    private fun decodeProfiles(json: String): List<CarProfile> =
        JSONArray(json).let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CarProfile(
                    deviceAddress = o.getString("deviceAddress"),
                    displayName = if (o.has("displayName") && !o.isNull("displayName")) o.getString("displayName") else null,
                    colorArgb = if (o.has("colorArgb")) o.getInt("colorArgb") else null,
                    lastSeenName = if (o.has("lastSeenName") && !o.isNull("lastSeenName")) o.getString("lastSeenName") else null,
                    lastConnected = if (o.has("lastConnected")) o.getLong("lastConnected") else null,
                    startRoadPieceId = if (o.has("startRoadPieceId")) o.getInt("startRoadPieceId") else null,
                    autoConnect = o.optBoolean("autoConnect", false)
                )
            }
        }

    private fun encodeProfiles(list: List<CarProfile>): String =
        JSONArray().also { arr ->
            list.forEach { p ->
                arr.put(JSONObject().apply {
                    put("deviceAddress", p.deviceAddress)
                    p.displayName?.let { put("displayName", it) }
                    p.colorArgb?.let { put("colorArgb", it) }
                    p.lastSeenName?.let { put("lastSeenName", it) }
                    p.lastConnected?.let { put("lastConnected", it) }
                    p.startRoadPieceId?.let { put("startRoadPieceId", it) }
                    put("autoConnect", p.autoConnect)
                })
            }
        }.toString()
}

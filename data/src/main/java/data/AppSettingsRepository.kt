package data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

class AppSettingsRepository(private val context: Context) {
    private val KEY_AUTO_CONNECT_ONLY_DISCOVERY = booleanPreferencesKey("auto_connect_only_discovery")
    private val KEY_AUTO_CONNECT_DELAY = booleanPreferencesKey("auto_connect_delay")

    val autoConnectOnlyDiscovery: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_AUTO_CONNECT_ONLY_DISCOVERY] ?: true }
    val autoConnectDelayEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_AUTO_CONNECT_DELAY] ?: false }

    suspend fun setAutoConnectOnlyDiscovery(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_AUTO_CONNECT_ONLY_DISCOVERY] = enabled }
    }
    suspend fun setAutoConnectDelayEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_AUTO_CONNECT_DELAY] = enabled }
    }
}


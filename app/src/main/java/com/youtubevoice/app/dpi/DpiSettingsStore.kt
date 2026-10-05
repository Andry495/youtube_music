package com.youtubevoice.app.dpi

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dpiStore by preferencesDataStore("dpi_settings")

object DpiSettingsStore {
    private val KEY_ENABLED = booleanPreferencesKey("dpi_enabled")
    private val KEY_PRESET_ID = stringPreferencesKey("dpi_preset_id")

    /** Local SOCKS listen port for ByeDPI. */
    const val PROXY_PORT = 1080

    /** Cached args for the native proxy (updated when preset changes). */
    @Volatile
    private var cachedArgs: List<String> = DpiPresets.ALL.first().args

    @Volatile
    private var cachedPresetId: String = DpiPresets.ALL.first().id

    /**
     * Cached enable flag. Default **false** until [hydrate] / DataStore loads —
     * otherwise UI/restore races and can start TUN while the user had DPI off.
     */
    @Volatile
    private var cachedEnabled: Boolean = false

    /** @deprecated Prefer [currentArgs] / selected preset. Kept for call-site compatibility. */
    val DEFAULT_ARGS: List<String>
        get() = currentArgs()

    fun currentArgs(): List<String> = cachedArgs

    fun currentPresetId(): String = cachedPresetId

    fun isEnabled(): Boolean = cachedEnabled

    fun enabledFlow(context: Context): Flow<Boolean> =
        context.applicationContext.dpiStore.data.map { prefs ->
            // Default OFF until explicitly enabled (avoids accidental TUN on cold start).
            (prefs[KEY_ENABLED] ?: false).also { cachedEnabled = it }
        }

    fun presetIdFlow(context: Context): Flow<String> =
        context.applicationContext.dpiStore.data.map { prefs ->
            prefs[KEY_PRESET_ID] ?: DpiPresets.ALL.first().id
        }

    suspend fun hydrate(context: Context) {
        val prefs = context.applicationContext.dpiStore.data.first()
        cachedEnabled = prefs[KEY_ENABLED] ?: false
        val id = prefs[KEY_PRESET_ID] ?: DpiPresets.ALL.first().id
        applyCache(DpiPresets.byId(id))
    }

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        context.applicationContext.dpiStore.edit { prefs ->
            prefs[KEY_ENABLED] = enabled
        }
    }

    suspend fun setPresetId(context: Context, presetId: String) {
        val preset = DpiPresets.byId(presetId)
        applyCache(preset)
        context.applicationContext.dpiStore.edit { prefs ->
            prefs[KEY_PRESET_ID] = preset.id
        }
    }

    private fun applyCache(preset: DpiPreset) {
        cachedPresetId = preset.id
        cachedArgs = preset.args
    }
}

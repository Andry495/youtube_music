package com.youtubevoice.app.dpi

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dpiStore by preferencesDataStore("dpi_settings")

object DpiSettingsStore {
    private val KEY_ENABLED = booleanPreferencesKey("dpi_enabled")

    /** Local SOCKS listen port for ByeDPI. */
    const val PROXY_PORT = 1080

    /**
     * YouTube/HTTPS oriented preset (Android = Linux stack).
     * protect-path is appended at runtime by [DpiVpnService].
     */
    val DEFAULT_ARGS: List<String> = listOf(
        "ciadpi",
        "--ip", "127.0.0.1",
        "--port", PROXY_PORT.toString(),
        "--disorder", "1",
        "--auto=torst",
        "--tlsrec", "1+s",
    )

    fun enabledFlow(context: Context): Flow<Boolean> =
        context.applicationContext.dpiStore.data.map { prefs ->
            prefs[KEY_ENABLED] == true
        }

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.dpiStore.edit { prefs ->
            prefs[KEY_ENABLED] = enabled
        }
    }
}

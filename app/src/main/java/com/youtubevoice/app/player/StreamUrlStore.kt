package com.youtubevoice.app.player

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.youtubevoice.app.data.ResolvedAudio
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

private val Context.streamUrlStore by preferencesDataStore("stream_url_cache")

/**
 * Last-known playable stream URLs so cold start can open [CacheDataSource]
 * without waiting on a flaky [resolveAudio].
 */
object StreamUrlStore {
    private val KEY_MAP = stringPreferencesKey("by_track_json")
    private val hydrateMutex = Mutex()

    @Volatile
    private var memory = mapOf<String, ResolvedAudio>()

    @Volatile
    private var hydrated = false

    suspend fun hydrate(context: Context) {
        hydrateMutex.withLock {
            if (hydrated) return
            val raw = context.applicationContext.streamUrlStore.data.first()[KEY_MAP]
            if (raw != null) {
                memory = runCatching { decode(raw) }.getOrDefault(emptyMap())
            }
            hydrated = true
        }
    }

    /** Ensures DataStore is in memory before [peek] — avoids cold-start races. */
    suspend fun ensureHydrated(context: Context) {
        if (!hydrated) hydrate(context)
    }

    fun peek(trackId: String): ResolvedAudio? = memory[trackId]

    suspend fun save(context: Context, resolved: ResolvedAudio) {
        if (resolved.trackId.isBlank() || resolved.streamUrl.isBlank()) return
        ensureHydrated(context)
        hydrateMutex.withLock {
            val next = memory.toMutableMap()
            next[resolved.trackId] = resolved
            // Cap map size — keep most recently saved entries.
            if (next.size > 80) {
                val drop = next.keys.take(next.size - 80)
                drop.forEach { next.remove(it) }
            }
            memory = next
            hydrated = true
            context.applicationContext.streamUrlStore.edit { prefs ->
                prefs[KEY_MAP] = encode(next)
            }
        }
    }

    private fun encode(map: Map<String, ResolvedAudio>): String {
        val root = JSONObject()
        map.forEach { (id, r) ->
            root.put(
                id,
                JSONObject()
                    .put("trackId", r.trackId)
                    .put("streamUrl", r.streamUrl)
                    .put("mimeType", r.mimeType)
                    .put("expiresAtMs", r.expiresAtMs)
                    .put("userAgent", r.userAgent)
            )
        }
        return root.toString()
    }

    private fun decode(raw: String): Map<String, ResolvedAudio> {
        val root = JSONObject(raw)
        val out = linkedMapOf<String, ResolvedAudio>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val o = root.getJSONObject(id)
            out[id] = ResolvedAudio(
                trackId = o.optString("trackId", id),
                streamUrl = o.getString("streamUrl"),
                mimeType = o.optString("mimeType").takeIf { it.isNotBlank() },
                expiresAtMs = o.optLong("expiresAtMs", 0L),
                userAgent = o.optString("userAgent").takeIf { it.isNotBlank() }
            )
        }
        return out
    }
}

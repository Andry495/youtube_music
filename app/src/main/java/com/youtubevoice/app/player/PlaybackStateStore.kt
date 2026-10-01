package com.youtubevoice.app.player

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.youtubevoice.app.data.PlaylistInfo
import com.youtubevoice.app.data.Track
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.playbackStore by preferencesDataStore("playback_state")

data class SavedPlayback(
    val playlist: PlaylistInfo,
    val trackIndex: Int,
    val positionMs: Long,
    val wasPlaying: Boolean
)

class PlaybackStateStore(private val appContext: Context) {
    suspend fun save(state: SavedPlayback) {
        if (state.playlist.tracks.isEmpty()) return
        appContext.playbackStore.edit { prefs ->
            prefs[KEY_JSON] = encode(state)
        }
    }

    suspend fun load(): SavedPlayback? {
        val raw = appContext.playbackStore.data.first()[KEY_JSON] ?: return null
        return runCatching { decode(raw) }.getOrNull()
    }

    suspend fun clear() {
        appContext.playbackStore.edit { it.remove(KEY_JSON) }
    }

    private fun encode(state: SavedPlayback): String {
        val root = JSONObject()
        root.put("playlistId", state.playlist.id)
        root.put("playlistTitle", state.playlist.title)
        root.put("uploader", state.playlist.uploader)
        root.put("thumbnailUrl", state.playlist.thumbnailUrl)
        root.put("trackIndex", state.trackIndex)
        root.put("positionMs", state.positionMs)
        root.put("wasPlaying", state.wasPlaying)
        val tracks = JSONArray()
        state.playlist.tracks.forEach { track ->
            tracks.put(
                JSONObject()
                    .put("id", track.id)
                    .put("title", track.title)
                    .put("artist", track.artist)
                    .put("thumbnailUrl", track.thumbnailUrl)
                    .put("watchUrl", track.watchUrl)
                    .put("durationSeconds", track.durationSeconds)
                    .put("channelId", track.channelId)
                    .put("playlistItemId", track.playlistItemId)
            )
        }
        root.put("tracks", tracks)
        return root.toString()
    }

    private fun decode(raw: String): SavedPlayback {
        val root = JSONObject(raw)
        val arr = root.getJSONArray("tracks")
        val tracks = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    Track(
                        id = o.getString("id"),
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        thumbnailUrl = o.optString("thumbnailUrl").takeIf { it.isNotBlank() },
                        watchUrl = o.optString("watchUrl").ifBlank {
                            "https://www.youtube.com/watch?v=${o.getString("id")}"
                        },
                        durationSeconds = o.optLong("durationSeconds"),
                        channelId = o.optString("channelId").takeIf { it.isNotBlank() },
                        playlistItemId = o.optString("playlistItemId").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
        require(tracks.isNotEmpty()) { "empty saved queue" }
        val index = root.optInt("trackIndex", 0).coerceIn(0, tracks.lastIndex)
        return SavedPlayback(
            playlist = PlaylistInfo(
                id = root.optString("playlistId", "saved"),
                title = root.optString("playlistTitle", "Продолжить"),
                uploader = root.optString("uploader"),
                thumbnailUrl = root.optString("thumbnailUrl").takeIf { it.isNotBlank() },
                tracks = tracks
            ),
            trackIndex = index,
            positionMs = root.optLong("positionMs", 0L).coerceAtLeast(0L),
            wasPlaying = root.optBoolean("wasPlaying", false)
        )
    }

    companion object {
        private val KEY_JSON = stringPreferencesKey("saved_playback_json")
    }
}

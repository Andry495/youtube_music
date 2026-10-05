package com.youtubevoice.app.player

import android.net.Uri
import androidx.media3.datasource.DataSpec

/**
 * Stable disk-cache keys so HLS segments / googlevideo URLs survive signature refresh
 * and window eviction keyed by track id.
 *
 * Format: `{trackId}|{host}{path}|itag|sq|lastSegment`
 */
object AudioCacheKeys {
    private val threadTrackId = ThreadLocal<String?>()

    @Volatile
    var foregroundTrackId: String? = null

    fun <T> withTrack(trackId: String, block: () -> T): T {
        val prev = threadTrackId.get()
        threadTrackId.set(trackId)
        return try {
            block()
        } finally {
            if (prev == null) threadTrackId.remove() else threadTrackId.set(prev)
        }
    }

    fun keyFor(dataSpec: DataSpec): String {
        dataSpec.key?.takeIf { it.isNotBlank() && !it.contains('?') && !it.startsWith("http") }
            ?.let { return it }
        val explicit = dataSpec.key?.takeIf { it.isNotBlank() }
        if (explicit != null && explicit.contains('|')) return explicit

        val trackId = threadTrackId.get() ?: foregroundTrackId
        val stable = stableResourceId(dataSpec.uri)
        return if (!trackId.isNullOrBlank() && isMediaUri(dataSpec.uri)) {
            "$trackId|$stable"
        } else {
            explicit ?: stable
        }
    }

    fun belongsToTrack(cacheKey: String, trackId: String): Boolean {
        if (trackId.isBlank()) return false
        return cacheKey == trackId || cacheKey.startsWith("$trackId|")
    }

    fun isMediaUri(uri: Uri): Boolean {
        val host = uri.host.orEmpty().lowercase()
        val path = uri.encodedPath.orEmpty().lowercase()
        return host.contains("googlevideo") ||
            host.contains("youtube") ||
            path.contains("videoplayback") ||
            path.contains("manifest") ||
            path.contains("hls") ||
            path.endsWith(".m3u8") ||
            path.endsWith(".ts") ||
            path.endsWith(".m4s") ||
            path.endsWith(".mp4")
    }

    /**
     * Identity of a media resource without volatile signature/expiry query params.
     */
    fun stableResourceId(uri: Uri): String {
        val host = uri.host.orEmpty()
        val path = uri.encodedPath.orEmpty()
        val itag = uri.getQueryParameter("itag").orEmpty()
        val sq = uri.getQueryParameter("sq").orEmpty()
        val last = uri.lastPathSegment.orEmpty()
        val id = uri.getQueryParameter("id").orEmpty().take(48)
        return "$host$path|$itag|$sq|$last|$id"
    }
}

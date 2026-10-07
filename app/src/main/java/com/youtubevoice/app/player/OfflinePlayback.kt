package com.youtubevoice.app.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.youtubevoice.app.data.Track
import java.io.File
import java.net.URLEncoder

/**
 * Play audio that is already on disk — no YouTube URL required.
 *
 * Network URLs are only for filling [AudioCacheStore]. The player reads
 * `ytvcache://` / local m3u8 built from a deduped, ordered segment set.
 *
 * Incomplete cache (excerpt shorter than [Track.durationSeconds]) is tagged and
 * written without `#EXT-X-ENDLIST` so ExoPlayer does not treat the excerpt as
 * the whole track and auto-advance.
 */
@UnstableApi
object OfflinePlayback {
    const val SCHEME = "ytvcache"
    private const val TAG = "OfflinePlayback"
    private const val MIN_BYTES = 64_000L
    /** Nominal HLS audio segment length used for duration estimates. */
    const val SEG_MS = 5_000L
    private const val COMPLETE_SLACK_MS = 25_000L

    data class OfflineTag(
        val incomplete: Boolean,
        val segmentCount: Int,
        val approxDurationMs: Long,
    )

    /** YouTube HLS audio itags commonly used for audio-only. */
    private val AUDIO_ITAGS = setOf(
        "139", "140", "141", "249", "250", "251", "599", "600", "233", "234"
    )

    fun hasPlayableCache(trackId: String): Boolean =
        trackId.isNotBlank() && AudioCacheStore.cachedBytesForTrack(trackId) >= MIN_BYTES

    fun tagOf(item: MediaItem?): OfflineTag? = item?.localConfiguration?.tag as? OfflineTag

    fun isIncomplete(item: MediaItem?): Boolean = tagOf(item)?.incomplete == true

    fun approxCachedMs(trackId: String): Long {
        val n = AudioCacheStore.keysForTrack(trackId)
            .count { AudioCacheKeys.looksLikeMediaSegment(it) }
        // After CDN dupes, unique clips ≈ n / hosts; use canonical count.
        val unique = AudioCacheKeys.canonicalSegments(
            AudioCacheStore.keysForTrack(trackId)
                .filter { AudioCacheKeys.looksLikeMediaSegment(it) }
        ).size
        return unique * SEG_MS
    }

    fun buildMediaItem(context: Context, track: Track): MediaItem? {
        if (!hasPlayableCache(track.id)) return null
        AudioCacheStore.dedupeTrackSegments(track.id)
        val keys = AudioCacheStore.keysForTrack(track.id)
            .filter { it != "${track.id}|playlist" }
        if (keys.isEmpty()) return null

        val trackDurationMs = track.durationSeconds.coerceAtLeast(0L) * 1000L
        val metadata = mediaMetadata(track)

        val progressiveOnly = keys.size == 1 && keys[0] == track.id
        if (progressiveOnly) {
            return MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(cacheUri(track.id))
                .setCustomCacheKey(track.id)
                .setMimeType(MimeTypes.AUDIO_MP4)
                .setMediaMetadata(metadata)
                .setTag(OfflineTag(incomplete = false, segmentCount = 1, approxDurationMs = trackDurationMs))
                .build()
        }

        val segments = selectPlayableSegments(keys)
        if (segments.isEmpty()) {
            Log.w(
                TAG,
                "No playable segment keys for ${track.id} (keys=${keys.size}) " +
                    "sample=${keys.take(3)}"
            )
            return null
        }

        val progress = TrackCacheProgress.snapshot(track.id, track.durationSeconds)
        val approxMs = segments.size * SEG_MS
        val incomplete = !progress.isComplete &&
            trackDurationMs > 0L &&
            approxMs + COMPLETE_SLACK_MS < trackDurationMs
        val dir = File(context.cacheDir, "offline_playlists").apply { mkdirs() }
        val playlist = File(dir, "${track.id}.m3u8")
        playlist.writeText(buildM3u8(segments, complete = !incomplete), Charsets.UTF_8)
        Log.i(
            TAG,
            "Offline playlist ${track.id}: ${segments.size} unique segs " +
                "approx=${approxMs}ms track=${trackDurationMs}ms " +
                "expected=${progress.expectedSegments} incomplete=$incomplete"
        )

        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(Uri.fromFile(playlist))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setMediaMetadata(metadata)
            .setTag(
                OfflineTag(
                    incomplete = incomplete,
                    segmentCount = segments.size,
                    approxDurationMs = approxMs
                )
            )
            .build()
    }

    fun mediaMetadata(track: Track): MediaMetadata {
        val b = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setArtworkUri(track.thumbnailUrl?.let { Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
        val ms = track.durationSeconds.coerceAtLeast(0L) * 1000L
        if (ms > 0L) b.setDurationMs(ms)
        return b.build()
    }

    fun cacheKeyFromUri(uri: Uri): String? {
        if (uri.scheme != SCHEME) return null
        return uri.getQueryParameter("key")
    }

    fun cacheUri(cacheKey: String): Uri =
        Uri.Builder()
            .scheme(SCHEME)
            .authority("local")
            .appendQueryParameter("key", cacheKey)
            .build()

    fun selectPlayableSegments(keys: List<String>): List<String> {
        val segs = keys.filter { AudioCacheKeys.looksLikeMediaSegment(it) }
        if (segs.isEmpty()) return emptyList()

        val audio = segs.filter { itagOf(it) in AUDIO_ITAGS }
        val chosen = when {
            audio.isNotEmpty() -> audio
            else -> segs.filter { !isLikelyVideoItag(itagOf(it)) }.ifEmpty { segs }
        }

        val byItag = chosen.groupBy { itagOf(it).orEmpty() }
        val bestItag = byItag.entries
            .maxByOrNull { (_, list) -> list.size }
            ?.key
        val single = if (bestItag != null) {
            byItag[bestItag].orEmpty()
        } else {
            chosen
        }

        return AudioCacheKeys.canonicalSegments(single)
    }

    private fun itagOf(key: String): String? =
        Regex("itag/(\\d+)").find(key)?.groupValues?.get(1)
            ?: Regex("""\|vp\|(\d+)\|""").find(key)?.groupValues?.get(1)
            ?: Regex("""\|(\d{3})\|""").find(key)?.groupValues?.get(1)

    private fun isLikelyVideoItag(itag: String?): Boolean {
        val v = itag?.toIntOrNull() ?: return false
        return v == 230 || v in 133..137 || v in 160..199 || v in 298..304 || v in 398..406
    }

    private fun buildM3u8(orderedKeys: List<String>, complete: Boolean): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-TARGETDURATION:6\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")
        // Incomplete excerpt: EVENT without ENDLIST — not a finished VOD track.
        if (complete) {
            sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        } else {
            sb.append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
        }
        for (key in orderedKeys) {
            val encoded = URLEncoder.encode(key, Charsets.UTF_8.name())
            sb.append("#EXTINF:5.0,\n")
            sb.append("$SCHEME://local?key=$encoded\n")
        }
        if (complete) {
            sb.append("#EXT-X-ENDLIST\n")
        }
        return sb.toString()
    }
}

package com.youtubevoice.app.player

import android.net.Uri
import androidx.media3.datasource.DataSpec

/**
 * Stable disk-cache keys so HLS segments / googlevideo URLs survive signature refresh
 * and window eviction keyed by track id.
 *
 * Segment identity is (itag + gosq/begin), not CDN host — so prefetch always writes
 * into one slot per clip and offline playback sees a clean ordered set.
 *
 * Format:
 * - HLS media: `{trackId}|vp|{itag}|g{gosq}|b{begin}|seg.ts|{videoId}`
 * - progressive: `{trackId}|videoplayback|{itag}|{sq}|{last}|{videoId}`
 * - other: `{trackId}|{path}|{itag}|{sq}|{last}|{videoId}`
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
        if (explicit != null && explicit.contains('|') && !explicit.startsWith("http")) {
            return explicit
        }

        val trackId = threadTrackId.get() ?: foregroundTrackId
        val stable = stableResourceId(dataSpec.uri)
        return if (!trackId.isNullOrBlank() && isMediaUri(dataSpec.uri)) {
            "$trackId|$stable"
        } else {
            explicit ?: stable
        }
    }

    /** Stable cache key for a track + media URI (used by prefetch writers). */
    fun keyForTrackUri(trackId: String, uri: Uri): String =
        "$trackId|${stableResourceId(uri)}"

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
     * Identity of a media resource without CDN host / signature / expiry.
     */
    fun stableResourceId(uri: Uri): String {
        val path = uri.encodedPath.orEmpty()
        val itag = uri.getQueryParameter("itag").orEmpty()
            .ifEmpty { pathSegmentAfter(path, "itag").orEmpty() }
        val sq = uri.getQueryParameter("sq").orEmpty()
        val gosq = pathSegmentAfter(path, "gosq").orEmpty()
        val begin = pathSegmentAfter(path, "begin").orEmpty()
        val last = uri.lastPathSegment.orEmpty()
        val id = (
            uri.getQueryParameter("id")
                ?: pathSegmentAfter(path, "id")
                ).orEmpty().take(48)

        // HLS audio/video clips under /videoplayback/.../gosq/N/file/seg.ts
        if (path.contains("videoplayback") &&
            (gosq.isNotEmpty() || begin.isNotEmpty() || last == "seg.ts")
        ) {
            return "vp|$itag|g$gosq|b$begin|$last|$id"
        }
        val pathTail = if (path.contains("videoplayback")) "videoplayback" else path
        return "$pathTail|$itag|$sq|$last|$id"
    }

    /**
     * Logical clip id for dedupe: same itag+gosq (or begin) → one offline segment,
     * regardless of CDN host / old key shape.
     */
    fun segmentDedupeId(cacheKey: String): String? {
        if (!looksLikeMediaSegment(cacheKey)) return null
        val itag = Regex("itag/(\\d+)").find(cacheKey)?.groupValues?.get(1)
            ?: Regex("""\|vp\|(\d+)\|""").find(cacheKey)?.groupValues?.get(1)
            ?: Regex("""\|(\d{3})\|""").find(cacheKey)?.groupValues?.get(1)
            ?: "?"
        val gosq = Regex("gosq/(\\d+)").find(cacheKey)?.groupValues?.get(1)
            ?: Regex("""\|g(\d+)\|""").find(cacheKey)?.groupValues?.get(1)
        val begin = Regex("/begin/(\\d+)").find(cacheKey)?.groupValues?.get(1)
            ?: Regex("""\|b(\d+)\|""").find(cacheKey)?.groupValues?.get(1)
        val seq = when {
            !gosq.isNullOrBlank() -> "g$gosq"
            !begin.isNullOrBlank() -> "b$begin"
            else -> return null
        }
        return "$itag|$seq"
    }

    fun looksLikeMediaSegment(cacheKey: String): Boolean {
        if (cacheKey.contains("hls_playlist") || cacheKey.contains("hls_variant")) return false
        if (cacheKey.contains("|playlist")) return false
        if (cacheKey.contains("seg.ts") || cacheKey.contains("/file/seg") ||
            cacheKey.contains("gosq/") || (cacheKey.contains("|vp|") && cacheKey.contains("|g"))
        ) {
            return true
        }
        if (cacheKey.contains("index.m3u8") || cacheKey.contains(".m3u8")) return false
        val parts = cacheKey.split('|')
        return parts.size >= 2 && (parts[1] == "videoplayback" || parts[1] == "vp")
    }

    /**
     * One key per logical clip (itag+gosq/begin), ordered for offline m3u8.
     * Prefer compact stable `|vp|` keys over legacy host+path keys.
     */
    fun canonicalSegments(keys: List<String>): List<String> {
        val grouped = linkedMapOf<String, String>()
        for (key in keys) {
            val id = segmentDedupeId(key) ?: continue
            val prev = grouped[id]
            grouped[id] = when {
                prev == null -> key
                key.contains("|vp|") && !prev.contains("|vp|") -> key
                prev.contains("|vp|") && !key.contains("|vp|") -> prev
                key.length < prev.length -> key
                else -> prev
            }
        }
        return grouped.entries
            .sortedWith(
                compareBy(
                    { gosqFromDedupeId(it.key) },
                    { beginFromDedupeId(it.key) },
                    { it.key }
                )
            )
            .map { it.value }
    }

    private fun gosqFromDedupeId(id: String): Long =
        Regex("""\|g(\d+)$""").find(id)?.groupValues?.get(1)?.toLongOrNull()
            ?: Long.MAX_VALUE

    private fun beginFromDedupeId(id: String): Long =
        Regex("""\|b(\d+)$""").find(id)?.groupValues?.get(1)?.toLongOrNull()
            ?: Long.MAX_VALUE

    private fun pathSegmentAfter(path: String, name: String): String? {
        val marker = "/$name/"
        val i = path.indexOf(marker)
        if (i < 0) return null
        val start = i + marker.length
        val end = path.indexOf('/', start).let { if (it < 0) path.length else it }
        return path.substring(start, end).takeIf { it.isNotEmpty() }
    }
}

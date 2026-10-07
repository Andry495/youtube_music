package com.youtubevoice.app.player

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Disk cache for audio. Size and eviction policy come from [CacheSettingsStore].
 * Keys are track ids and `{trackId}|…` HLS/segment keys from [AudioCacheKeys].
 */
@UnstableApi
object AudioCacheStore {
    private const val TAG = "AudioCacheStore"
    private const val CACHE_DIR = "youtube_voice_audio"

    data class CacheDiskSnapshot(
        val totalBytes: Long = 0L,
        val totalKeys: Int = 0,
        val maxBytes: Long = 0L,
        val trackId: String? = null,
        val trackBytes: Long = 0L,
        val trackKeys: Int = 0,
    ) {
        val totalMb: Double get() = totalBytes / (1024.0 * 1024.0)
        val maxMb: Double get() = maxBytes / (1024.0 * 1024.0)
        val trackMb: Double get() = trackBytes / (1024.0 * 1024.0)
        val fillRatio: Float
            get() = if (maxBytes > 0) (totalBytes.toDouble() / maxBytes).toFloat().coerceIn(0f, 1f) else 0f
    }

    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    private var appliedMaxBytes: Long = 0L

    fun get(context: Context): SimpleCache {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            return open(context.applicationContext)
        }
    }

    fun maxBytes(): Long =
        appliedMaxBytes.takeIf { it > 0 } ?: CacheSettingsStore.current().maxCacheBytes

    fun prefetchBytesPerTrack(): Long = CacheSettingsStore.current().prefetchBytesPerTrack

    /**
     * Recreate cache if the configured size changed (e.g. after settings update).
     * Caller must swap ExoPlayer's DataSource factory to the new [SimpleCache].
     */
    fun reloadIfNeeded(context: Context) {
        val wanted = CacheSettingsStore.current().maxCacheBytes
        synchronized(this) {
            if (cache != null && appliedMaxBytes == wanted) return
            releaseLocked()
            open(context.applicationContext)
        }
    }

    fun removeByTrackId(trackId: String) {
        val c = cache ?: return
        if (trackId.isBlank()) return
        runCatching {
            c.keys.toList().forEach { key ->
                if (AudioCacheKeys.belongsToTrack(key, trackId)) {
                    c.removeResource(key)
                }
            }
        }.onFailure {
            Log.w(TAG, "removeByTrackId failed for $trackId", it)
        }
    }

    /**
     * Drop cached resources that do not belong to [keepTrackIds]
     * (exact track id or `{trackId}|…` segment keys).
     */
    @Synchronized
    fun retainOnly(keepTrackIds: Collection<String>) {
        val mode = CacheSettingsStore.current().evictionMode
        val c = cache ?: return
        val keep = keepTrackIds.filter { it.isNotBlank() }.toHashSet()
        if (mode == CacheEvictionMode.LRU) {
            Log.i(
                TAG,
                "retainOnly skip (mode=LRU) keepTracks=${keep.size} keys=${c.keys.size} " +
                    "bytes=${cachedBytes()} breakdown=${trackBreakdown()}"
            )
            return
        }
        runCatching {
            val keys = c.keys.toList()
            var removed = 0
            for (key in keys) {
                val belongs = keep.any { AudioCacheKeys.belongsToTrack(key, it) }
                if (!belongs) {
                    c.removeResource(key)
                    removed++
                }
            }
            Log.i(
                TAG,
                "retainOnly mode=$mode keep=${keep.joinToString()} keys=${keys.size} " +
                    "removed=$removed bytes=${cachedBytes()} breakdown=${trackBreakdown()}"
            )
        }.onFailure {
            Log.w(TAG, "retainOnly failed", it)
        }
    }

    /** trackId → cached bytes (top tracks by size). */
    fun trackBreakdown(limit: Int = 8): String {
        val c = cache ?: return "{}"
        return runCatching {
            val byTrack = linkedMapOf<String, Long>()
            for (key in c.keys) {
                val trackId = key.substringBefore('|', missingDelimiterValue = key)
                val bytes = c.getCachedBytes(key, 0, Long.MAX_VALUE)
                byTrack[trackId] = (byTrack[trackId] ?: 0L) + bytes
            }
            byTrack.entries
                .sortedByDescending { it.value }
                .take(limit)
                .joinToString(prefix = "{", postfix = "}") { (id, bytes) ->
                    "$id=${"%.1f".format(bytes / (1024.0 * 1024.0))}MB"
                }
        }.getOrDefault("{}")
    }

    fun clearAll() {
        val c = cache ?: return
        runCatching {
            c.keys.toList().forEach { c.removeResource(it) }
            Log.i(TAG, "cache cleared")
        }.onFailure {
            Log.w(TAG, "clearAll failed", it)
        }
    }

    fun cachedBytes(): Long {
        val c = cache ?: return 0L
        return runCatching { c.cacheSpace }.getOrDefault(0L)
    }

    fun keyCount(): Int = cache?.keys?.size ?: 0

    /** Bytes + key count belonging to one track id (exact or `{id}|…` keys). */
    fun cachedBytesForTrack(trackId: String): Long {
        if (trackId.isBlank()) return 0L
        val c = cache ?: return 0L
        return runCatching {
            c.keys.sumOf { key ->
                if (AudioCacheKeys.belongsToTrack(key, trackId)) {
                    c.getCachedBytes(key, 0, Long.MAX_VALUE)
                } else {
                    0L
                }
            }
        }.getOrDefault(0L)
    }

    fun keyCountForTrack(trackId: String): Int {
        if (trackId.isBlank()) return 0
        val c = cache ?: return 0
        return runCatching {
            c.keys.count { AudioCacheKeys.belongsToTrack(it, trackId) }
        }.getOrDefault(0)
    }

    /** All SimpleCache keys belonging to [trackId] (exact id or `{id}|…`). */
    fun keysForTrack(trackId: String): List<String> {
        if (trackId.isBlank()) return emptyList()
        val c = cache ?: return emptyList()
        return runCatching {
            c.keys.filter { AudioCacheKeys.belongsToTrack(it, trackId) }
        }.getOrDefault(emptyList())
    }

    /**
     * Keep one disk entry per logical HLS clip (itag+gosq). Drops CDN/signature duplicates
     * so offline playback always has a clean ordered set. Returns removed key count.
     */
    fun dedupeTrackSegments(trackId: String): Int {
        if (trackId.isBlank()) return 0
        val c = cache ?: return 0
        return runCatching {
            val segmentKeys = keysForTrack(trackId)
                .filter { AudioCacheKeys.looksLikeMediaSegment(it) }
            if (segmentKeys.size < 2) return@runCatching 0
            val keep = AudioCacheKeys.canonicalSegments(segmentKeys).toHashSet()
            var removed = 0
            for (key in segmentKeys) {
                if (key !in keep) {
                    c.removeResource(key)
                    removed++
                }
            }
            if (removed > 0) {
                Log.i(
                    TAG,
                    "dedupe $trackId removed=$removed keep=${keep.size} " +
                        "bytes=${cachedBytesForTrack(trackId)}"
                )
            }
            removed
        }.getOrDefault(0)
    }

    fun snapshot(trackId: String? = null): CacheDiskSnapshot {
        val total = cachedBytes()
        val keys = keyCount()
        val trackBytes = trackId?.let { cachedBytesForTrack(it) } ?: 0L
        val trackKeys = trackId?.let { keyCountForTrack(it) } ?: 0
        return CacheDiskSnapshot(
            totalBytes = total,
            totalKeys = keys,
            maxBytes = maxBytes(),
            trackId = trackId,
            trackBytes = trackBytes,
            trackKeys = trackKeys,
        )
    }

    fun release() {
        synchronized(this) {
            releaseLocked()
        }
    }

    private fun open(app: Context): SimpleCache {
        val dir = File(app.cacheDir, CACHE_DIR).apply { mkdirs() }
        val maxBytes = CacheSettingsStore.current().maxCacheBytes
        appliedMaxBytes = maxBytes
        Log.i(TAG, "Audio cache maxBytes=${maxBytes / (1024 * 1024)} MB at ${dir.absolutePath}")
        return SimpleCache(
            dir,
            LeastRecentlyUsedCacheEvictor(maxBytes),
            StandaloneDatabaseProvider(app)
        ).also { cache = it }
    }

    private fun releaseLocked() {
        cache?.release()
        cache = null
        appliedMaxBytes = 0L
    }
}

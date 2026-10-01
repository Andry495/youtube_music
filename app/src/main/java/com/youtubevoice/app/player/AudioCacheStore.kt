package com.youtubevoice.app.player

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Disk cache for audio: dynamic size from free space, LRU inside the cap,
 * and explicit eviction of tracks outside the current+prefetch window.
 */
@UnstableApi
object AudioCacheStore {
    private const val TAG = "AudioCacheStore"
    private const val CACHE_DIR = "youtube_voice_audio"

    /** Floor / ceiling for the adaptive cache budget. */
    private const val MIN_BYTES = 64L * 1024L * 1024L
    private const val MAX_BYTES = 256L * 1024L * 1024L
    /** Fraction of free space under cacheDir parent (app cache). */
    private const val FREE_SPACE_FRACTION = 0.05

    /** Soft cap per prefetched track so 3 ahead tracks stay small. */
    const val PREFETCH_BYTES_PER_TRACK = 10L * 1024L * 1024L

    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    private var appliedMaxBytes: Long = 0L

    fun get(context: Context): SimpleCache {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val app = context.applicationContext
            val dir = File(app.cacheDir, CACHE_DIR).apply { mkdirs() }
            val maxBytes = computeMaxBytes(dir)
            appliedMaxBytes = maxBytes
            Log.i(TAG, "Audio cache maxBytes=${maxBytes / (1024 * 1024)} MB at ${dir.absolutePath}")
            return SimpleCache(
                dir,
                LeastRecentlyUsedCacheEvictor(maxBytes),
                StandaloneDatabaseProvider(app)
            ).also { cache = it }
        }
    }

    fun maxBytes(): Long = appliedMaxBytes.takeIf { it > 0 } ?: MAX_BYTES

    fun computeMaxBytes(cacheDir: File): Long {
        val free = runCatching {
            val stat = StatFs(cacheDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        }.getOrElse {
            cacheDir.usableSpace
        }.coerceAtLeast(0L)
        val fromFree = (free * FREE_SPACE_FRACTION).toLong()
        return fromFree.coerceIn(MIN_BYTES, MAX_BYTES)
    }

    fun removeByTrackId(trackId: String) {
        val c = cache ?: return
        if (trackId.isBlank()) return
        runCatching {
            c.removeResource(trackId)
        }.onFailure {
            Log.w(TAG, "removeByTrackId failed for $trackId", it)
        }
    }

    /**
     * Drop every cached resource whose key is not in [keepTrackIds].
     * Keys are track ids (customCacheKey / DataSpec.key).
     */
    fun retainOnly(keepTrackIds: Collection<String>) {
        val c = cache ?: return
        val keep = keepTrackIds.filter { it.isNotBlank() }.toHashSet()
        runCatching {
            val keys = c.keys.toList()
            for (key in keys) {
                if (key !in keep) {
                    c.removeResource(key)
                }
            }
            Log.i(TAG, "retainOnly keep=${keep.size} removed=${keys.count { it !in keep }}")
        }.onFailure {
            Log.w(TAG, "retainOnly failed", it)
        }
    }

    fun release() {
        synchronized(this) {
            cache?.release()
            cache = null
            appliedMaxBytes = 0L
        }
    }
}

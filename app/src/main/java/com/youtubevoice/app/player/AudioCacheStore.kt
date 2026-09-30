package com.youtubevoice.app.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Disk cache for audio segments so rebuffer / replay / next-track
 * can read from storage instead of waiting on the network.
 */
@UnstableApi
object AudioCacheStore {
    private const val CACHE_DIR = "youtube_voice_audio"
    private const val MAX_BYTES = 512L * 1024L * 1024L // 512 MB

    @Volatile
    private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val dir = File(context.applicationContext.cacheDir, CACHE_DIR).apply { mkdirs() }
            return SimpleCache(
                dir,
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(context.applicationContext)
            ).also { cache = it }
        }
    }

    fun release() {
        synchronized(this) {
            cache?.release()
            cache = null
        }
    }
}

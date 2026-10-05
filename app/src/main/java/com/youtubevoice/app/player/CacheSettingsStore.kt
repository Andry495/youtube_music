package com.youtubevoice.app.player

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.cacheStore by preferencesDataStore("cache_settings")

enum class CacheEvictionMode {
    /** Keep only current + prefetch window; drop everything else. */
    WINDOW,
    /** Only LRU when over the size cap; do not drop by playlist window. */
    LRU,
    /** Window trim + LRU inside the size cap (default). */
    WINDOW_AND_LRU,
}

enum class CachePrefetchOrder {
    /** Warm next N tracks only. */
    AHEAD,
    /** Warm current track first, then next N. */
    CURRENT_THEN_AHEAD,
    /** Warm previous N and next N around the current track. */
    AROUND,
}

data class CacheSettings(
    val maxCacheMb: Int = 256,
    val prefetchAhead: Int = 3,
    val prefetchBehind: Int = 1,
    val prefetchMbPerTrack: Int = 10,
    val evictionMode: CacheEvictionMode = CacheEvictionMode.WINDOW_AND_LRU,
    /** Default: fill current track first (incl. background FGS), then ahead. */
    val prefetchOrder: CachePrefetchOrder = CachePrefetchOrder.CURRENT_THEN_AHEAD,
) {
    val maxCacheBytes: Long get() = maxCacheMb.toLong() * 1024L * 1024L
    val prefetchBytesPerTrack: Long get() = prefetchMbPerTrack.toLong() * 1024L * 1024L
}

object CacheSettingsStore {
    private val KEY_MAX_MB = intPreferencesKey("max_cache_mb")
    private val KEY_PREFETCH_AHEAD = intPreferencesKey("prefetch_ahead")
    private val KEY_PREFETCH_BEHIND = intPreferencesKey("prefetch_behind")
    private val KEY_PREFETCH_MB = intPreferencesKey("prefetch_mb_per_track")
    private val KEY_EVICTION = stringPreferencesKey("eviction_mode")
    private val KEY_ORDER = stringPreferencesKey("prefetch_order")

    val MAX_MB_OPTIONS = listOf(64, 128, 256, 512)
    val PREFETCH_AHEAD_OPTIONS = listOf(1, 2, 3, 5)
    val PREFETCH_BEHIND_OPTIONS = listOf(0, 1, 2)
    val PREFETCH_MB_OPTIONS = listOf(5, 10, 20)

    @Volatile
    private var cached = CacheSettings()

    fun current(): CacheSettings = cached

    fun settingsFlow(context: Context): Flow<CacheSettings> =
        context.applicationContext.cacheStore.data.map { prefs ->
            CacheSettings(
                maxCacheMb = (prefs[KEY_MAX_MB] ?: 256).coerceIn(64, 512),
                prefetchAhead = (prefs[KEY_PREFETCH_AHEAD] ?: 3).coerceIn(1, 5),
                prefetchBehind = (prefs[KEY_PREFETCH_BEHIND] ?: 1).coerceIn(0, 2),
                prefetchMbPerTrack = (prefs[KEY_PREFETCH_MB] ?: 10).coerceIn(5, 20),
                evictionMode = runCatching {
                    CacheEvictionMode.valueOf(prefs[KEY_EVICTION] ?: CacheEvictionMode.WINDOW_AND_LRU.name)
                }.getOrDefault(CacheEvictionMode.WINDOW_AND_LRU),
                prefetchOrder = runCatching {
                    CachePrefetchOrder.valueOf(
                        prefs[KEY_ORDER] ?: CachePrefetchOrder.CURRENT_THEN_AHEAD.name
                    )
                }.getOrDefault(CachePrefetchOrder.CURRENT_THEN_AHEAD),
            ).also { cached = it }
        }

    suspend fun hydrate(context: Context) {
        cached = settingsFlow(context).first()
    }

    suspend fun update(context: Context, transform: (CacheSettings) -> CacheSettings) {
        // Always base on disk — never overwrite with unhydrated defaults.
        val base = settingsFlow(context).first()
        val next = transform(base)
        cached = next
        context.applicationContext.cacheStore.edit { prefs ->
            prefs[KEY_MAX_MB] = next.maxCacheMb
            prefs[KEY_PREFETCH_AHEAD] = next.prefetchAhead
            prefs[KEY_PREFETCH_BEHIND] = next.prefetchBehind
            prefs[KEY_PREFETCH_MB] = next.prefetchMbPerTrack
            prefs[KEY_EVICTION] = next.evictionMode.name
            prefs[KEY_ORDER] = next.prefetchOrder.name
        }
    }
}

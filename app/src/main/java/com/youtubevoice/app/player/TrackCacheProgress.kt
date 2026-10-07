package com.youtubevoice.app.player

import androidx.media3.common.util.UnstableApi

/**
 * How much of a track is on disk, derived from unique HLS clips (itag+gosq)
 * and the catalog [durationSeconds].
 */
@UnstableApi
object TrackCacheProgress {
    data class Snapshot(
        val trackId: String,
        val durationMs: Long,
        val uniqueSegments: Int,
        val expectedSegments: Int,
        /** Contiguous unique gosq values present (sorted). */
        val gosqList: List<Long>,
        val cachedApproxMs: Long,
    ) {
        val isComplete: Boolean
            get() = when {
                durationMs <= 0L -> uniqueSegments >= 3 && expectedSegments == 0
                expectedSegments <= 0 -> false
                // Allow a couple of trailing segs to be missing (timing slack).
                else -> uniqueSegments >= (expectedSegments - 2).coerceAtLeast(1) &&
                    cachedApproxMs + 20_000L >= durationMs
            }

        val completeRatio: Float
            get() = when {
                durationMs > 0L ->
                    (cachedApproxMs.toFloat() / durationMs).coerceIn(0f, 1f)
                expectedSegments > 0 ->
                    (uniqueSegments.toFloat() / expectedSegments).coerceIn(0f, 1f)
                else -> 0f
            }

        /** Contiguous cached media from the start of the track (gosq 0,1,2…). */
        fun contiguousFromStartMs(): Long {
            if (gosqList.isEmpty()) return 0L
            val set = gosqList.toHashSet()
            var g = 0L
            while (set.contains(g)) g++
            return g * OfflinePlayback.SEG_MS
        }

        /** Contiguous cached media starting at [positionMs], in ms. */
        fun cachedAheadOf(positionMs: Long): Long {
            if (gosqList.isEmpty()) return 0L
            val start = (positionMs.coerceAtLeast(0L) / OfflinePlayback.SEG_MS)
            val set = gosqList.toHashSet()
            var g = start
            while (set.contains(g)) g++
            return ((g - start) * OfflinePlayback.SEG_MS).coerceAtLeast(0L)
        }

        /**
         * Furthest timeline point covered by disk for UI: contiguous from 0,
         * or (position + ahead) when mid-track fill is ahead of a gap.
         */
        fun diskUntilMs(positionMs: Long): Long {
            val fromStart = contiguousFromStartMs()
            val aheadEnd = positionMs.coerceAtLeast(0L) + cachedAheadOf(positionMs)
            val approx = cachedApproxMs
            return maxOf(fromStart, aheadEnd, approx).let { end ->
                if (durationMs > 0L) end.coerceAtMost(durationMs) else end
            }
        }
    }

    fun snapshot(trackId: String, durationSeconds: Long): Snapshot {
        val durationMs = durationSeconds.coerceAtLeast(0L) * 1000L
        val keys = AudioCacheStore.keysForTrack(trackId)
            .filter { AudioCacheKeys.looksLikeMediaSegment(it) }
        val canonical = AudioCacheKeys.canonicalSegments(keys)
        val gosqs = canonical.mapNotNull { gosqOf(it) }.distinct().sorted()
        val expected = if (durationMs > 0L) {
            ((durationMs + OfflinePlayback.SEG_MS - 1) / OfflinePlayback.SEG_MS).toInt()
        } else {
            0
        }
        return Snapshot(
            trackId = trackId,
            durationMs = durationMs,
            uniqueSegments = canonical.size.coerceAtLeast(gosqs.size),
            expectedSegments = expected,
            gosqList = gosqs,
            cachedApproxMs = canonical.size * OfflinePlayback.SEG_MS,
        )
    }

    fun gosqOf(cacheKeyOrUrl: String): Long? =
        Regex("gosq/(\\d+)").find(cacheKeyOrUrl)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("""\|g(\d+)\|""").find(cacheKeyOrUrl)?.groupValues?.get(1)?.toLongOrNull()
}

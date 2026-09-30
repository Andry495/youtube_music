package com.youtubevoice.app.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import android.util.Log
import com.youtubevoice.app.MainActivity
import com.youtubevoice.app.YoutubeVoiceApp
import com.youtubevoice.app.data.Track
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

@UnstableApi
class PlaybackService : MediaSessionService() {
    private val serviceScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, error ->
                Log.e(TAG, "Playback coroutine failed", error)
            }
    )
    private val repository get() = YoutubeVoiceApp.instance.youtubeRepository

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var cacheDataSourceFactory: CacheDataSource.Factory? = null

    private val trackIndex = linkedMapOf<String, Track>()
    private val streamUserAgents = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val resolvingIds = mutableSetOf<String>()
    private val prefetchMutex = Mutex()
    private var prefetchJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        val okHttp = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val original = chain.request()
                val url = original.url.toString()
                val ua = streamUserAgents[url]
                    ?: streamUserAgents.entries.firstOrNull { url.startsWith(it.key.take(120)) }?.value
                    ?: userAgentForStreamUrl(url)
                chain.proceed(
                    original.newBuilder()
                        .header("User-Agent", ua)
                        .header("Referer", "https://www.youtube.com/")
                        .header("Origin", "https://www.youtube.com")
                        .build()
                )
            }
            .build()

        val upstreamFactory = OkHttpDataSource.Factory(okHttp)
            .setUserAgent(ANDROID_UA)

        val cacheFactory = CacheDataSource.Factory()
            .setCache(AudioCacheStore.get(this))
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setCacheKeyFactory { dataSpec ->
                dataSpec.key?.takeIf { it.isNotBlank() } ?: dataSpec.uri.toString()
            }
        cacheDataSourceFactory = cacheFactory

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(cacheFactory)
            .setLoadErrorHandlingPolicy(
                DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount */ 6)
            )

        // Large audio buffers: prefer keeping ~2 minutes ahead to survive network dips
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 45_000,
                /* maxBufferMs = */ 180_000,
                /* bufferForPlaybackMs = */ 2_000,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(
                /* backBufferDurationMs = */ 60_000,
                /* retainBackBufferFromKeyframe = */ true
            )
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem ?: return
                ensureResolved(mediaItem, exoPlayer.currentMediaItemIndex)
                prefetchAround(exoPlayer.currentMediaItemIndex)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                    prefetchAround(exoPlayer.currentMediaItemIndex)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val current = exoPlayer.currentMediaItem ?: return
                val track = trackIndex[current.mediaId] ?: return
                serviceScope.launch {
                    try {
                        repository.invalidate(track.id)
                        val resolved = repository.resolveAudio(track.id, track.watchUrl)
                        val index = exoPlayer.currentMediaItemIndex
                        exoPlayer.replaceMediaItem(
                            index,
                            buildResolvedMediaItem(track, resolved.streamUrl, resolved.userAgent)
                        )
                        exoPlayer.prepare()
                        exoPlayer.play()
                        prefetchAround(index)
                    } catch (_: Exception) {
                        // UI observes player error state
                    }
                }
            }
        })

        player = exoPlayer

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(sessionActivity)
            .setId("youtube_voice_session")
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        if (instance === this) instance = null
        prefetchJob?.cancel()
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        cacheDataSourceFactory = null
        super.onDestroy()
    }

    fun setPlaylist(tracks: List<Track>, startIndex: Int = 0, autoPlay: Boolean = true) {
        val exo = player ?: return
        trackIndex.clear()
        resolvingIds.clear()
        prefetchJob?.cancel()
        tracks.forEach { trackIndex[it.id] = it }

        serviceScope.launch {
            val safeStart = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
            // Placeholders for all items — resolve only the current track + a few ahead.
            val mediaItems = tracks.map { buildPlaceholderMediaItem(it) }
            exo.setMediaItems(mediaItems, safeStart, C.TIME_UNSET)

            val startTrack = tracks.getOrNull(safeStart)
            val resolved = if (startTrack != null) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        repository.resolveAudio(startTrack.id, startTrack.watchUrl)
                    }.onFailure {
                        Log.e(TAG, "Failed to resolve ${startTrack.id}", it)
                    }.getOrNull()
                }
            } else {
                null
            }

            if (resolved != null && startTrack != null && exo.mediaItemCount > safeStart) {
                exo.replaceMediaItem(
                    safeStart,
                    buildResolvedMediaItem(startTrack, resolved.streamUrl, resolved.userAgent)
                )
            }

            exo.prepare()
            if (autoPlay && resolved != null) {
                exo.play()
            } else if (autoPlay && startTrack != null) {
                // Retry once via ensureResolved path
                ensureResolved(exo.getMediaItemAt(safeStart), safeStart)
                exo.playWhenReady = true
            }
            prefetchAround(safeStart)
        }
    }

    fun playTrackAt(index: Int) {
        val exo = player ?: return
        if (index !in 0 until exo.mediaItemCount) return
        serviceScope.launch {
            ensureResolved(exo.getMediaItemAt(index), index)
            exo.seekToDefaultPosition(index)
            exo.prepare()
            exo.play()
            prefetchAround(index)
        }
    }

    /** Prefetch must never touch ExoPlayer off the main thread. */
    private fun prefetchAround(centerIndex: Int) {
        prefetchJob?.cancel()
        prefetchJob = serviceScope.launch {
            prefetchMutex.withLock {
                val exo = player ?: return@withLock
                val last = exo.mediaItemCount - 1
                if (last < 0) return@withLock
                for (offset in 1..PREFETCH_AHEAD) {
                    ensureActive()
                    val index = centerIndex + offset
                    if (index > last) break
                    val item = exo.getMediaItemAt(index)
                    val track = trackIndex[item.mediaId] ?: continue
                    val uri = item.localConfiguration?.uri
                    try {
                        val streamUrl = if (uri?.scheme == "youtubevoice") {
                            val resolved = withContext(Dispatchers.IO) {
                                repository.resolveAudio(track.id, track.watchUrl)
                            }
                            if (exo.mediaItemCount > index &&
                                trackIndex[track.id]?.id == track.id
                            ) {
                                exo.replaceMediaItem(
                                    index,
                                    buildResolvedMediaItem(track, resolved.streamUrl, resolved.userAgent)
                                )
                            }
                            resolved.streamUrl
                        } else {
                            uri?.toString() ?: continue
                        }
                        withContext(Dispatchers.IO) {
                            warmCache(track.id, streamUrl)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Prefetch failed for ${track.id}", t)
                    }
                }
            }
        }
    }

    private suspend fun warmCache(trackId: String, streamUrl: String) {
        val factory = cacheDataSourceFactory ?: return
        coroutineContext.ensureActive()
        try {
            val dataSource = factory.createDataSource()
            val dataSpec = DataSpec.Builder()
                .setUri(streamUrl)
                .setKey(trackId)
                .build()
            // Download into SimpleCache; ExoPlayer will reuse by customCacheKey=trackId
            CacheWriter(dataSource, dataSpec, /* temporaryBuffer */ null, /* progressListener */ null)
                .cache()
        } catch (_: Exception) {
            // Ignore cancel / network errors during background warm-up
        }
    }

    private fun ensureResolved(mediaItem: MediaItem, index: Int) {
        val uri = mediaItem.localConfiguration?.uri
        if (uri?.scheme != "youtubevoice") {
            // Already resolved — still warm next tracks
            return
        }
        val track = trackIndex[mediaItem.mediaId] ?: return
        if (!resolvingIds.add(track.id)) return

        serviceScope.launch {
            try {
                val resolved = repository.resolveAudio(track.id, track.watchUrl)
                val exo = player ?: return@launch
                if (exo.mediaItemCount > index) {
                    val wasPlaying = exo.isPlaying
                    val sameItem = exo.currentMediaItemIndex == index
                    exo.replaceMediaItem(
                        index,
                        buildResolvedMediaItem(track, resolved.streamUrl, resolved.userAgent)
                    )
                    if (sameItem) {
                        exo.prepare()
                        if (wasPlaying || exo.playWhenReady) exo.play()
                    }
                }
                launch(Dispatchers.IO) {
                    warmCache(track.id, resolved.streamUrl)
                }
            } catch (_: Exception) {
                // keep placeholder; error surfaces if user tries to play
            } finally {
                resolvingIds.remove(track.id)
            }
        }
    }

    private fun buildResolveOrder(size: Int, start: Int): List<Int> {
        // Kept for compatibility; prefetch uses sequential window instead
        if (size <= 0) return emptyList()
        return listOf(start)
    }

    private fun buildPlaceholderMediaItem(track: Track): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setArtworkUri(track.thumbnailUrl?.let { android.net.Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()

        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri("youtubevoice://track/${track.id}")
            .setCustomCacheKey(track.id)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun buildResolvedMediaItem(track: Track, streamUrl: String, userAgent: String?): MediaItem {
        userAgent?.takeIf { it.isNotBlank() }?.let { streamUserAgents[streamUrl] = it }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setArtworkUri(track.thumbnailUrl?.let { android.net.Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()

        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(streamUrl)
            .setCustomCacheKey(track.id) // stable key even when googlevideo URL rotates
            .setMediaMetadata(metadata)
            .build()
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val PREFETCH_AHEAD = 1
        private const val ANDROID_UA =
            "com.google.android.youtube/21.03.36 (Linux; U; Android 14) gzip"
        private const val ANDROID_VR_UA =
            "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; Quest 3) gzip"
        private const val TV_UA = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
        private const val VISIONOS_UA =
            "com.google.ios.youtube/1.02 (RealityDevice14,1; U; CPU OS 25_6_0 like Mac OS X;)"

        private fun userAgentForStreamUrl(url: String): String = when {
            url.contains("manifest/hls") || url.contains("hls_playlist") ||
                url.contains("hls_variant") -> VISIONOS_UA
            url.contains("c=TVHTML5") || url.contains("c=TVHTML5_SIMPLY") -> TV_UA
            url.contains("c=ANDROID_VR") -> ANDROID_VR_UA
            url.contains("c=VISIONOS") || url.contains("c=IOS") -> VISIONOS_UA
            url.contains("c=ANDROID") || url.contains("googlevideo.com") -> ANDROID_UA
            else -> VISIONOS_UA
        }

        @Volatile
        var instance: PlaybackService? = null
            private set
    }
}

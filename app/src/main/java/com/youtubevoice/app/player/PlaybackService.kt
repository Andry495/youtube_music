package com.youtubevoice.app.player

import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
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
import com.youtubevoice.app.MainActivity
import com.youtubevoice.app.YoutubeVoiceApp
import com.youtubevoice.app.data.Track
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    private var okHttpClient: OkHttpClient? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val trackIndex = linkedMapOf<String, Track>()
    private val streamUserAgents = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val resolvingIds = mutableSetOf<String>()
    private val prefetchMutex = Mutex()
    private var prefetchJob: Job? = null
    private var recoverJob: Job? = null

    private val networkLost = AtomicBoolean(false)
    private val recoverInFlight = AtomicBoolean(false)
    private val errorRecoverAttempts = AtomicInteger(0)

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
        okHttpClient = okHttp

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
                errorRecoverAttempts.set(0)
                ensureResolved(mediaItem, exoPlayer.currentMediaItemIndex)
                prefetchAround(exoPlayer.currentMediaItemIndex)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    errorRecoverAttempts.set(0)
                }
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                    prefetchAround(exoPlayer.currentMediaItemIndex)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) errorRecoverAttempts.set(0)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Player error: ${error.errorCodeName}", error)
                recoverCurrentTrack(
                    reason = "player_error:${error.errorCodeName}",
                    forceInvalidate = true,
                    resumePlay = exoPlayer.playWhenReady
                )
            }
        })

        player = exoPlayer
        registerNetworkCallback()

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

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing in background; only stop if idle/paused with nothing useful.
        val exo = player
        if (exo == null || (!exo.isPlaying && !exo.playWhenReady)) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        unregisterNetworkCallback()
        recoverJob?.cancel()
        prefetchJob?.cancel()
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        cacheDataSourceFactory = null
        okHttpClient = null
        super.onDestroy()
    }

    fun snapshotTracks(): List<Track> = trackIndex.values.toList()

    fun setPlaylist(
        tracks: List<Track>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true
    ) {
        val exo = player ?: return
        trackIndex.clear()
        resolvingIds.clear()
        errorRecoverAttempts.set(0)
        prefetchJob?.cancel()
        tracks.forEach { trackIndex[it.id] = it }

        serviceScope.launch {
            val safeStart = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
            val mediaItems = tracks.map { buildPlaceholderMediaItem(it) }
            val startPos = startPositionMs.takeIf { it > 0 } ?: C.TIME_UNSET
            exo.setMediaItems(mediaItems, safeStart, startPos)

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
            if (startPos != C.TIME_UNSET) {
                exo.seekTo(safeStart, startPositionMs)
            }
            if (autoPlay && resolved != null) {
                exo.play()
            } else if (autoPlay && startTrack != null) {
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

    /** Resume current item after pause without seeking to start. */
    fun resumeCurrent() {
        val exo = player ?: return
        val index = exo.currentMediaItemIndex
        if (index < 0 || index >= exo.mediaItemCount) return
        val item = exo.getMediaItemAt(index)
        val uri = item.localConfiguration?.uri
        if (uri?.scheme == "youtubevoice") {
            serviceScope.launch {
                val position = exo.currentPosition.coerceAtLeast(0L)
                ensureResolved(item, index)
                if (position > 0) exo.seekTo(index, position)
                exo.play()
            }
        } else {
            exo.play()
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                networkLost.set(true)
                Log.i(TAG, "Network lost")
            }

            override fun onAvailable(network: Network) {
                if (!networkLost.getAndSet(false) && !shouldForceRecover()) return
                Log.i(TAG, "Network available — recovering playback")
                scheduleNetworkRecover()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                if (!networkLost.get() && !shouldForceRecover()) return
                networkLost.set(false)
                Log.i(TAG, "Network validated — recovering playback")
                scheduleNetworkRecover()
            }
        }
        networkCallback = callback
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w(TAG, "Cannot register network callback", it) }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    private fun shouldForceRecover(): Boolean {
        val exo = player ?: return false
        if (exo.mediaItemCount <= 0) return false
        if (exo.playerError != null) return true
        if (exo.playWhenReady && !exo.isPlaying &&
            (exo.playbackState == Player.STATE_BUFFERING || exo.playbackState == Player.STATE_IDLE)
        ) {
            return true
        }
        return false
    }

    private fun scheduleNetworkRecover() {
        recoverJob?.cancel()
        recoverJob = serviceScope.launch {
            // Let the new route settle (Wi‑Fi ↔ mobile)
            delay(700)
            okHttpClient?.connectionPool?.evictAll()
            okHttpClient?.dispatcher?.cancelAll()
            repository.invalidateAll()
            recoverCurrentTrack(
                reason = "network_change",
                forceInvalidate = true,
                resumePlay = player?.playWhenReady == true || player?.isPlaying == true
            )
        }
    }

    private fun recoverCurrentTrack(
        reason: String,
        forceInvalidate: Boolean,
        resumePlay: Boolean
    ) {
        val exo = player ?: return
        val current = exo.currentMediaItem ?: return
        val track = trackIndex[current.mediaId] ?: return
        val attempts = errorRecoverAttempts.incrementAndGet()
        if (attempts > MAX_ERROR_RECOVERIES) {
            Log.e(TAG, "Giving up recovery after $attempts attempts ($reason)")
            return
        }
        if (!recoverInFlight.compareAndSet(false, true)) return

        serviceScope.launch {
            try {
                val position = exo.currentPosition.coerceAtLeast(0L)
                val index = exo.currentMediaItemIndex
                if (forceInvalidate) {
                    repository.invalidate(track.id)
                }
                Log.i(TAG, "Recovering ${track.id} at ${position}ms ($reason, try=$attempts)")
                val resolved = withContext(Dispatchers.IO) {
                    repository.resolveAudio(track.id, track.watchUrl)
                }
                if (exo.mediaItemCount <= index) return@launch
                exo.replaceMediaItem(
                    index,
                    buildResolvedMediaItem(track, resolved.streamUrl, resolved.userAgent)
                )
                exo.prepare()
                if (position > 0) {
                    exo.seekTo(index, position)
                }
                if (resumePlay) {
                    exo.play()
                }
                prefetchAround(index)
            } catch (t: Exception) {
                Log.w(TAG, "Recovery failed ($reason)", t)
                // Retry once more shortly if network may still be settling
                if (attempts < MAX_ERROR_RECOVERIES) {
                    delay(1_500)
                    recoverInFlight.set(false)
                    recoverCurrentTrack(reason, forceInvalidate = true, resumePlay = resumePlay)
                    return@launch
                }
            } finally {
                recoverInFlight.set(false)
            }
        }
    }

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
            CacheWriter(dataSource, dataSpec, /* temporaryBuffer */ null, /* progressListener */ null)
                .cache()
        } catch (_: Exception) {
            // Ignore cancel / network errors during background warm-up
        }
    }

    private fun ensureResolved(mediaItem: MediaItem, index: Int) {
        val uri = mediaItem.localConfiguration?.uri
        if (uri?.scheme != "youtubevoice") return
        val track = trackIndex[mediaItem.mediaId] ?: return
        if (!resolvingIds.add(track.id)) return

        serviceScope.launch {
            try {
                val resolved = repository.resolveAudio(track.id, track.watchUrl)
                val exo = player ?: return@launch
                if (exo.mediaItemCount > index) {
                    val wasPlaying = exo.isPlaying
                    val sameItem = exo.currentMediaItemIndex == index
                    val position = if (sameItem) exo.currentPosition.coerceAtLeast(0L) else 0L
                    exo.replaceMediaItem(
                        index,
                        buildResolvedMediaItem(track, resolved.streamUrl, resolved.userAgent)
                    )
                    if (sameItem) {
                        exo.prepare()
                        if (position > 0) exo.seekTo(index, position)
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
            .setCustomCacheKey(track.id)
            .setMediaMetadata(metadata)
            .build()
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val PREFETCH_AHEAD = 1
        private const val MAX_ERROR_RECOVERIES = 4
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

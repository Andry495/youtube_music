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
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.youtubevoice.app.MainActivity
import com.youtubevoice.app.YoutubeVoiceApp
import com.youtubevoice.app.data.Track
import com.youtubevoice.app.dpi.AppHttp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
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
    /** Lets [reloadCacheSettings] swap cache without rebuilding ExoPlayer. */
    private lateinit var swappableDataSourceFactory: SwappableDataSourceFactory
    private var okHttpClient: OkHttpClient? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val trackIndex = linkedMapOf<String, Track>()
    private val streamUserAgents = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val resolvingIds = mutableSetOf<String>()
    private val prefetchMutex = Mutex()
    private var prefetchJob: Job? = null
    /** Center index the active fill job is targeting; avoids cancel on every BUFFERING. */
    private var prefetchCenter: Int = -1
    private var retainJob: Job? = null
    @Volatile private var pendingRetainCenter: Int = -1
    private var recoverJob: Job? = null

    private val networkLost = AtomicBoolean(false)
    private val recoverInFlight = AtomicBoolean(false)
    private val errorRecoverAttempts = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        instance = this

        val mediaCallFactory = Call.Factory { request ->
            val client = okHttpClient ?: mediaHttpClient().also { okHttpClient = it }
            client.newCall(request)
        }
        okHttpClient = mediaHttpClient()

        val cacheFactory = buildCacheDataSourceFactory(mediaCallFactory)
        cacheDataSourceFactory = cacheFactory
        swappableDataSourceFactory = SwappableDataSourceFactory(cacheFactory)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(swappableDataSourceFactory)
            .setLoadErrorHandlingPolicy(
                DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount */ 6)
            )

        // Audio-only: cap RAM. Time-first 180s + parallel HLS CacheWriter hit the 256 MB heap.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 45_000,
                /* bufferForPlaybackMs = */ 750,
                /* bufferForPlaybackAfterRebufferMs = */ 1_500
            )
            .setTargetBufferBytes(8 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(
                /* backBufferDurationMs = */ 20_000,
                /* retainBackBufferFromKeyframe = */ false
            )
            .build()

        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .setPreferredAudioLanguage("ru")
            )
        }

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
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
                val index = exoPlayer.currentMediaItemIndex
                val reasonLabel = when (reason) {
                    Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
                    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "auto"
                    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "seek"
                    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "playlist"
                    else -> "other:$reason"
                }
                Log.i(
                    TAG,
                    "MediaItemTransition reason=$reasonLabel index=$index " +
                        "id=${mediaItem.mediaId} pos=${exoPlayer.currentPosition} " +
                        "dur=${exoPlayer.duration}"
                )
                errorRecoverAttempts.set(0)
                AudioCacheKeys.foregroundTrackId = mediaItem.mediaId
                ensureResolved(mediaItem, index)
                // Free outside window, then fill remaining for the new window.
                rotateCacheForCenter(index)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    errorRecoverAttempts.set(0)
                    // Kick / resume fill when ready; do not restart on BUFFERING.
                    prefetchAround(exoPlayer.currentMediaItemIndex)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    errorRecoverAttempts.set(0)
                    prefetchAround(exoPlayer.currentMediaItemIndex)
                }
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
        prefetchCenter = -1
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
        prefetchCenter = -1
        tracks.forEach { trackIndex[it.id] = it }

        val safeStartPreview = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        val initialKeep = cacheKeepIndexes(safeStartPreview, tracks.size)
            .mapNotNull { tracks.getOrNull(it)?.id }
        serviceScope.launch(Dispatchers.IO) {
            AudioCacheStore.retainOnly(initialKeep)
        }

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
                    buildResolvedMediaItem(
                        startTrack,
                        resolved.streamUrl,
                        resolved.userAgent,
                        resolved.mimeType
                    )
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
            retainCacheWindow(safeStart)
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
                // Connectivity callbacks run off the main thread — never touch ExoPlayer here.
                networkLost.set(false)
                serviceScope.launch {
                    if (!shouldForceRecover()) return@launch
                    Log.i(TAG, "Network available — recovering playback")
                    scheduleNetworkRecover()
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                networkLost.set(false)
                serviceScope.launch {
                    if (!shouldForceRecover()) return@launch
                    Log.i(TAG, "Network validated — recovering playback")
                    scheduleNetworkRecover()
                }
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

    private fun mediaHttpClient(): OkHttpClient {
        return AppHttp.client().newBuilder()
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
    }

    /** Rebind OkHttp after DPI SOCKS toggle (evicts old sockets off the main thread). */
    fun reloadHttpClient() {
        val previous = okHttpClient
        okHttpClient = mediaHttpClient()
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                previous?.dispatcher?.cancelAll()
                previous?.connectionPool?.evictAll()
            }.onFailure {
                Log.w(TAG, "Failed to evict previous HTTP pool", it)
            }
        }
        Log.i(TAG, "HTTP client reloaded (dpiProxy=${AppHttp.isDpiProxyEnabled()})")
    }

    private fun shouldForceRecover(): Boolean {
        val exo = player ?: return false
        if (exo.mediaItemCount <= 0) return false
        // Only hard errors. Normal BUFFERING must not trigger recover — replaceMediaItem
        // mid-track routinely advances/resets and feels like a random skip to next.
        return exo.playerError != null
    }

    private fun scheduleNetworkRecover() {
        recoverJob?.cancel()
        recoverJob = serviceScope.launch {
            // Let the new route settle (Wi‑Fi ↔ mobile / VPN)
            delay(700)
            if (!shouldForceRecover()) {
                Log.i(TAG, "Skip network recover — player healthy")
                return@launch
            }
            withContext(Dispatchers.IO) {
                runCatching {
                    okHttpClient?.dispatcher?.cancelAll()
                    okHttpClient?.connectionPool?.evictAll()
                }
            }
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
                    buildResolvedMediaItem(
                        track,
                        resolved.streamUrl,
                        resolved.userAgent,
                        resolved.mimeType
                    )
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

    fun reloadCacheSettings() {
        serviceScope.launch(Dispatchers.IO) {
            AudioCacheStore.reloadIfNeeded(this@PlaybackService)
            withContext(Dispatchers.Main) {
                val mediaCallFactory = Call.Factory { request ->
                    val client = okHttpClient ?: mediaHttpClient().also { okHttpClient = it }
                    client.newCall(request)
                }
                val cacheFactory = buildCacheDataSourceFactory(mediaCallFactory)
                cacheDataSourceFactory = cacheFactory
                if (::swappableDataSourceFactory.isInitialized) {
                    swappableDataSourceFactory.delegate = cacheFactory
                }
                Log.i(
                    TAG,
                    "Cache settings reloaded maxMb=${CacheSettingsStore.current().maxCacheMb} " +
                        "keys=${AudioCacheStore.keyCount()} bytes=${AudioCacheStore.cachedBytes()}"
                )
            }
        }
    }

    /**
     * On track switch: drop cache outside the keep window (policy), then warm missing bytes
     * for current + neighbors.
     */
    private fun rotateCacheForCenter(centerIndex: Int) {
        pendingRetainCenter = centerIndex
        // Cancel in-flight fill for the previous center immediately.
        if (prefetchCenter != centerIndex) {
            prefetchJob?.cancel()
            prefetchCenter = -1
        }
        retainJob?.cancel()
        retainJob = serviceScope.launch {
            // Coalesce rapid skips to the latest center.
            while (isActive) {
                val center = pendingRetainCenter
                delay(80)
                if (pendingRetainCenter != center) continue
                val exo = player ?: break
                val last = exo.mediaItemCount - 1
                val settings = CacheSettingsStore.current()
                val keep = LinkedHashSet<String>()
                if (last >= 0) {
                    for (index in cacheKeepIndexes(center, exo.mediaItemCount)) {
                        if (index in 0..last) keep += exo.getMediaItemAt(index).mediaId
                    }
                }
                // Disk trim off the main thread.
                withContext(Dispatchers.IO) {
                    AudioCacheStore.retainOnly(keep)
                }
                Log.i(
                    TAG,
                    "cache rotate center=$center mode=${settings.evictionMode} " +
                        "order=${settings.prefetchOrder} " +
                        "ahead=${settings.prefetchAhead} behind=${settings.prefetchBehind} " +
                        "keep=${keep.joinToString()} breakdown=${AudioCacheStore.trackBreakdown()}"
                )
                if (pendingRetainCenter != center) continue
                if (exo.currentMediaItemIndex == center) {
                    prefetchAround(center)
                }
                break
            }
        }
    }

    private fun retainCacheWindow(centerIndex: Int) {
        pendingRetainCenter = centerIndex
        if (retainJob?.isActive == true) return
        retainJob = serviceScope.launch {
            while (isActive) {
                val center = pendingRetainCenter
                delay(80)
                if (pendingRetainCenter != center) continue
                val exo = player ?: break
                val last = exo.mediaItemCount - 1
                val keep = LinkedHashSet<String>()
                if (last >= 0) {
                    for (index in cacheKeepIndexes(center, exo.mediaItemCount)) {
                        if (index in 0..last) keep += exo.getMediaItemAt(index).mediaId
                    }
                }
                val settings = CacheSettingsStore.current()
                withContext(Dispatchers.IO) {
                    AudioCacheStore.retainOnly(keep)
                }
                Log.i(
                    TAG,
                    "cache window center=$center order=${settings.prefetchOrder} " +
                        "ahead=${settings.prefetchAhead} behind=${settings.prefetchBehind} " +
                        "keep=${keep.joinToString()} breakdown=${AudioCacheStore.trackBreakdown()}"
                )
                if (pendingRetainCenter == center) break
            }
        }
    }

    private fun prefetchAround(centerIndex: Int) {
        // Already filling this center — keep the running job (background continuous fill).
        if (prefetchJob?.isActive == true && prefetchCenter == centerIndex) return
        prefetchJob?.cancel()
        prefetchCenter = centerIndex
        prefetchJob = serviceScope.launch {
            try {
                // Continuous fill while FGS is up and playback is active/pending.
                while (true) {
                    ensureActive()
                    val exo = player ?: break
                    if (!exo.playWhenReady && !exo.isPlaying) break
                    val madeProgress = prefetchMutex.withLock {
                        runPrefetchPass(centerIndex)
                    }
                    if (!madeProgress) {
                        // Nothing left in window; wait and re-check (new segments / next track).
                        delay(15_000)
                        val still = player ?: break
                        if (still.currentMediaItemIndex != centerIndex) break
                        if (!still.playWhenReady && !still.isPlaying) break
                    } else {
                        // Brief yield so playback network isn't starved.
                        delay(750)
                    }
                }
            } finally {
                if (prefetchCenter == centerIndex) prefetchCenter = -1
            }
        }
    }

    /**
     * One pass over the prefetch window. Returns true if any bytes were written to cache.
     */
    private suspend fun runPrefetchPass(centerIndex: Int): Boolean {
        val exo = player ?: return false
        val last = exo.mediaItemCount - 1
        if (last < 0) return false
        retainCacheWindow(centerIndex)
        val settings = CacheSettingsStore.current()
        val order = prefetchIndexes(centerIndex, exo.mediaItemCount, settings)
        var wroteAny = false
        for (index in order) {
            coroutineContext.ensureActive()
            if (index < 0 || index > last) continue
            val item = exo.getMediaItemAt(index)
            val track = trackIndex[item.mediaId] ?: continue
            val uri = item.localConfiguration?.uri
            val isCurrent = index == centerIndex
            try {
                val streamUrl = if (uri?.scheme == "youtubevoice") {
                    // Never replaceMediaItem for the currently playing index from prefetch —
                    // that recreates the source mid-play and can auto-advance to next.
                    if (isCurrent &&
                        exo.currentMediaItemIndex == centerIndex &&
                        (exo.isPlaying || exo.playWhenReady)
                    ) {
                        continue
                    }
                    val resolved = withContext(Dispatchers.IO) {
                        repository.resolveAudio(track.id, track.watchUrl)
                    }
                    if (exo.mediaItemCount > index &&
                        trackIndex[track.id]?.id == track.id
                    ) {
                        exo.replaceMediaItem(
                            index,
                            buildResolvedMediaItem(
                                track,
                                resolved.streamUrl,
                                resolved.userAgent,
                                resolved.mimeType
                            )
                        )
                    }
                    resolved.streamUrl
                } else {
                    uri?.toString() ?: continue
                }
                // Playing item is already written by ExoPlayer's CacheDataSource —
                // a second CacheWriter of the same HLS OOM'd the 256 MB heap.
                if (isCurrent && (exo.isPlaying || exo.playWhenReady)) {
                    continue
                }
                val wrote = withContext(Dispatchers.IO) {
                    warmCache(track.id, streamUrl, fillCompletely = false)
                }
                if (wrote > 0) wroteAny = true
            } catch (t: Throwable) {
                Log.w(TAG, "Prefetch failed for ${track.id}", t)
            }
        }
        return wroteAny
    }

    /**
     * @param fillCompletely current track: keep filling until playlist is done (FGS background).
     * @return bytes newly written to disk cache this call.
     */
    private suspend fun warmCache(
        trackId: String,
        streamUrl: String,
        fillCompletely: Boolean = false
    ): Long {
        val factory = cacheDataSourceFactory ?: return 0L
        coroutineContext.ensureActive()
        return try {
            if (isHlsUrl(streamUrl)) {
                warmHls(trackId, streamUrl, factory, fillCompletely)
            } else {
                AudioCacheKeys.withTrack(trackId) {
                    warmProgressive(trackId, streamUrl, factory, fillCompletely)
                }
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun warmProgressive(
        trackId: String,
        streamUrl: String,
        factory: CacheDataSource.Factory,
        fillCompletely: Boolean
    ): Long {
        val budget = if (fillCompletely) {
            AudioCacheStore.prefetchBytesPerTrack().coerceAtMost(32L * 1024L * 1024L)
        } else {
            AudioCacheStore.prefetchBytesPerTrack()
        }
        var written = 0L
        val dataSource = factory.createDataSource()
        val dataSpec = DataSpec.Builder()
            .setUri(streamUrl)
            .setKey(trackId)
            .setLength(budget)
            .build()
        CacheWriter(
            dataSource,
            dataSpec,
            /* temporaryBuffer */ null
        ) { _, _, newBytesCached ->
            written += newBytesCached
        }.cache()
        return written
    }

    private suspend fun warmHls(
        trackId: String,
        playlistUrl: String,
        factory: CacheDataSource.Factory,
        fillCompletely: Boolean
    ): Long {
        AudioCacheKeys.withTrack(trackId) {
            runCatching {
                val dataSource = factory.createDataSource()
                val playlistSpec = DataSpec.Builder()
                    .setUri(playlistUrl)
                    .setKey("$trackId|playlist")
                    .build()
                CacheWriter(dataSource, playlistSpec, null, null).cache()
            }
        }

        val playlistBody = withContext(Dispatchers.IO) { downloadText(playlistUrl) } ?: return 0L
        val segmentUrls = withContext(Dispatchers.IO) {
            parseM3u8MediaUrls(playlistBody, playlistUrl)
        }
        if (segmentUrls.isEmpty()) {
            Log.w(TAG, "HLS warm: no segments in playlist for $trackId")
            return 0L
        }

        var budget = if (fillCompletely) {
            AudioCacheStore.prefetchBytesPerTrack().coerceAtMost(32L * 1024L * 1024L)
        } else {
            AudioCacheStore.prefetchBytesPerTrack()
        }
        var warmed = 0
        var writtenTotal = 0L
        for (segmentUrl in segmentUrls) {
            if (budget <= 0) break
            coroutineContext.ensureActive()
            try {
                val wrote = withContext(Dispatchers.IO) {
                    AudioCacheKeys.withTrack(trackId) {
                        var written = 0L
                        val dataSource = factory.createDataSource()
                        val spec = DataSpec.Builder()
                            .setUri(segmentUrl)
                            // Full segment; CacheWriter skips already-cached ranges.
                            .build()
                        CacheWriter(dataSource, spec, null) { _, _, newBytesCached ->
                            written += newBytesCached
                        }.cache()
                        written
                    }
                }
                if (wrote > 0) {
                    budget -= wrote
                    writtenTotal += wrote
                    warmed++
                    delay(50)
                }
            } catch (_: Exception) {
                break
            }
        }
        if (warmed > 0 || writtenTotal > 0) {
            Log.i(
                TAG,
                "HLS warm $trackId full=$fillCompletely segs=$warmed bytes=$writtenTotal " +
                    "keys=${AudioCacheStore.keyCount()}"
            )
        }
        return writtenTotal
    }

    private fun downloadText(url: String, maxBytes: Long = 512L * 1024L): String? {
        val client = okHttpClient ?: mediaHttpClient().also { okHttpClient = it }
        val ua = streamUserAgents[url] ?: userAgentForStreamUrl(url)
        return runCatching {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", ua)
                    .get()
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return null
                val source = response.body?.source() ?: return null
                source.request(maxBytes + 1)
                val buf = source.buffer
                if (buf.size > maxBytes) {
                    Log.w(TAG, "downloadText truncated ${buf.size} > $maxBytes for $url")
                }
                buf.readUtf8(minOf(buf.size, maxBytes))
            }
        }.getOrNull()
    }

    /** True only for HLS *playlists*, not googlevideo media segments that mention hls in the query. */
    private fun isHlsPlaylistUrl(url: String): Boolean {
        val uri = android.net.Uri.parse(url)
        val host = uri.host.orEmpty().lowercase()
        val path = uri.encodedPath.orEmpty().lowercase()
        if (path.contains("videoplayback")) return false
        if (host.contains("googlevideo") && !path.contains(".m3u8") && !path.contains("manifest")) {
            return false
        }
        return path.endsWith(".m3u8") ||
            path.contains("/manifest/hls") ||
            path.contains("hls_playlist") && !path.contains("videoplayback")
    }

    private fun parseM3u8MediaUrls(
        body: String,
        playlistUrl: String,
        depth: Int = 0
    ): List<String> {
        if (depth > 2) return emptyList()
        val base = android.net.Uri.parse(playlistUrl)
        val out = ArrayList<String>()
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val absolute = when {
                line.startsWith("http://") || line.startsWith("https://") -> line
                line.startsWith("/") -> "${base.scheme}://${base.host}$line"
                else -> {
                    val path = base.encodedPath.orEmpty()
                    val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
                    val prefix = if (parent.isEmpty()) "" else "$parent/"
                    "${base.scheme}://${base.host}$prefix$line"
                }
            }
            if (isHlsPlaylistUrl(absolute)) {
                val nested = downloadText(absolute) ?: continue
                out += parseM3u8MediaUrls(nested, absolute, depth + 1)
            } else {
                out += absolute
            }
            if (out.size >= 48) break
        }
        return out
    }

    private fun isHlsUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("mpegurl") ||
            u.contains("m3u8") ||
            u.contains("hls_playlist") ||
            u.contains("/manifest/hls") ||
            u.contains("hls_variant")
    }

    private fun buildCacheDataSourceFactory(mediaCallFactory: Call.Factory): CacheDataSource.Factory {
        val upstreamFactory = OkHttpDataSource.Factory(mediaCallFactory)
            .setUserAgent(ANDROID_UA)
        return CacheDataSource.Factory()
            .setCache(AudioCacheStore.get(this))
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setCacheKeyFactory { dataSpec -> AudioCacheKeys.keyFor(dataSpec) }
    }

    private class SwappableDataSourceFactory(
        @Volatile var delegate: DataSource.Factory
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = delegate.createDataSource()
    }

    private fun cacheKeepIndexes(centerIndex: Int, count: Int): List<Int> {
        if (count <= 0) return emptyList()
        val settings = CacheSettingsStore.current()
        val center = centerIndex.coerceIn(0, count - 1)
        val keep = LinkedHashSet<Int>()
        keep += center
        when (settings.prefetchOrder) {
            CachePrefetchOrder.AHEAD,
            CachePrefetchOrder.CURRENT_THEN_AHEAD -> {
                for (offset in 1..settings.prefetchAhead) {
                    val i = center + offset
                    if (i < count) keep += i
                }
            }
            CachePrefetchOrder.AROUND -> {
                for (offset in 1..settings.prefetchBehind) {
                    val i = center - offset
                    if (i >= 0) keep += i
                }
                for (offset in 1..settings.prefetchAhead) {
                    val i = center + offset
                    if (i < count) keep += i
                }
            }
        }
        return keep.toList()
    }

    private fun prefetchIndexes(
        centerIndex: Int,
        count: Int,
        settings: CacheSettings
    ): List<Int> {
        if (count <= 0) return emptyList()
        val center = centerIndex.coerceIn(0, count - 1)
        return when (settings.prefetchOrder) {
            CachePrefetchOrder.AHEAD -> {
                (1..settings.prefetchAhead).map { center + it }.filter { it < count }
            }
            CachePrefetchOrder.CURRENT_THEN_AHEAD -> {
                buildList {
                    add(center)
                    for (offset in 1..settings.prefetchAhead) {
                        val i = center + offset
                        if (i < count) add(i)
                    }
                }
            }
            CachePrefetchOrder.AROUND -> {
                buildList {
                    add(center)
                    for (offset in 1..settings.prefetchAhead) {
                        val i = center + offset
                        if (i < count) add(i)
                    }
                    for (offset in 1..settings.prefetchBehind) {
                        val i = center - offset
                        if (i >= 0) add(i)
                    }
                }
            }
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
                        buildResolvedMediaItem(
                            track,
                            resolved.streamUrl,
                            resolved.userAgent,
                            resolved.mimeType
                        )
                    )
                    if (sameItem) {
                        exo.prepare()
                        if (position > 0) exo.seekTo(index, position)
                        if (wasPlaying || exo.playWhenReady) exo.play()
                    }
                }
                // Playing item is cached by ExoPlayer; extra HLS walk OOM'd on youtube URLs.
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

    private fun buildResolvedMediaItem(
        track: Track,
        streamUrl: String,
        userAgent: String?,
        mimeType: String?
    ): MediaItem {
        userAgent?.takeIf { it.isNotBlank() }?.let { streamUserAgents[streamUrl] = it }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setArtworkUri(track.thumbnailUrl?.let { android.net.Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()

        val builder = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(streamUrl)
            .setCustomCacheKey(track.id)
            .setMediaMetadata(metadata)

        // YouTube HLS manifests often omit .m3u8; without an explicit MIME,
        // Media3 treats them as progressive and fails with UnrecognizedInputFormatException.
        val normalized = mimeType?.lowercase().orEmpty()
        val isHls = normalized.contains("mpegurl") ||
            normalized.contains("m3u8") ||
            normalized.contains("hls") ||
            streamUrl.contains("hls_playlist", ignoreCase = true) ||
            streamUrl.contains("/manifest/hls", ignoreCase = true) ||
            streamUrl.contains(".m3u8", ignoreCase = true)
        if (isHls) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        } else if (!mimeType.isNullOrBlank()) {
            builder.setMimeType(mimeType)
        }

        return builder.build()
    }

    companion object {
        private const val TAG = "PlaybackService"
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

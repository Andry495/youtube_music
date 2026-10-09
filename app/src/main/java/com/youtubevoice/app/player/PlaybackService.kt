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
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
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
import android.content.pm.ApplicationInfo
import com.youtubevoice.app.MainActivity
import com.youtubevoice.app.YoutubeVoiceApp
import com.youtubevoice.app.data.ResolvedAudio
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val debuggable =
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && intent?.action == ACTION_DEBUG_SEEK) {
            val exo = player
            if (exo != null) {
                val requested = intent.getLongExtra(EXTRA_SEEK_MS, -1L)
                val dur = exo.duration
                val pos = when {
                    requested >= 0L -> requested
                    // -1 → jump near end (60s before finish) for fill tests
                    dur > 0L -> (dur - 60_000L).coerceAtLeast(0L)
                    else -> -1L
                }
                if (pos >= 0L) {
                    Log.i(TAG, "DEBUG_SEEK to ${pos}ms (dur=${dur}ms)")
                    exo.seekTo(pos)
                    restartPrefetch(exo.currentMediaItemIndex)
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        val mediaCallFactory = Call.Factory { request ->
            val client = okHttpClient ?: mediaHttpClient().also { okHttpClient = it }
            val url = request.url.toString()
            val ua = streamUserAgents[url] ?: userAgentForStreamUrl(url)
            val withUa = if (request.header("User-Agent").isNullOrBlank()) {
                request.newBuilder().header("User-Agent", ua).build()
            } else {
                request
            }
            client.newCall(withUa)
        }
        okHttpClient = mediaHttpClient()

        val cacheFactory = buildCacheDataSourceFactory(mediaCallFactory)
        cacheDataSourceFactory = cacheFactory
        // DefaultDataSource: file:// offline playlists; http/ytvcache → cache factory.
        val playbackFactory = DefaultDataSource.Factory(this, cacheFactory)
        swappableDataSourceFactory = SwappableDataSourceFactory(playbackFactory)

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
            // Incomplete offline excerpts must not auto-skip to the next track.
            .setPauseAtEndOfMediaItems(true)
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
                serviceScope.launch { ensureResolved(mediaItem, index) }
                // Free outside window, then fill remaining for the new window.
                rotateCacheForCenter(index)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        errorRecoverAttempts.set(0)
                        prefetchAround(exoPlayer.currentMediaItemIndex)
                    }
                    Player.STATE_ENDED -> handleMediaEnded()
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // pauseAtEndOfMediaItems → false + END_OF_MEDIA_ITEM (may arrive with/before STATE_ENDED).
                if (!playWhenReady &&
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
                ) {
                    handleMediaEnded()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    errorRecoverAttempts.set(0)
                    prefetchAround(exoPlayer.currentMediaItemIndex)
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason != Player.DISCONTINUITY_REASON_SEEK &&
                    reason != Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                ) {
                    return
                }
                val jump = kotlin.math.abs(newPosition.positionMs - oldPosition.positionMs)
                // Big scrub: drop in-flight warm of the old timeline, refill from new playhead.
                if (jump >= PLAYHEAD_BACK_BUFFER_MS) {
                    Log.i(
                        TAG,
                        "Seek discontinuity ${oldPosition.positionMs}→${newPosition.positionMs}ms " +
                            "jump=${jump}ms — restart prefetch"
                    )
                    restartPrefetch(exoPlayer.currentMediaItemIndex)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Player error: ${error.errorCodeName}", error)
                // Offline: recover from disk only — do not invalidate / wait on YouTube.
                recoverCurrentTrack(
                    reason = "player_error:${error.errorCodeName}",
                    forceInvalidate = isNetworkUsable(),
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
            // Offline-first: disk bytes are enough to play — URL is only for filling cache.
            val offline = if (startTrack != null) {
                withContext(Dispatchers.IO) {
                    OfflinePlayback.buildMediaItem(this@PlaybackService, startTrack)
                }
            } else {
                null
            }
            val staged = if (offline == null && startTrack != null) {
                resolveForPlayback(startTrack, allowStale = true)
            } else {
                null
            }
            if (offline != null && startTrack != null && exo.mediaItemCount > safeStart) {
                Log.i(
                    TAG,
                    "Play ${startTrack.id} from disk cache " +
                        "(${AudioCacheStore.cachedBytesForTrack(startTrack.id)}B)"
                )
                exo.replaceMediaItem(safeStart, offline)
            } else if (staged != null && startTrack != null && exo.mediaItemCount > safeStart) {
                exo.replaceMediaItem(safeStart, buildResolvedMediaItem(startTrack, staged))
            }

            exo.prepare()
            if (startPos != C.TIME_UNSET) {
                exo.seekTo(safeStart, startPositionMs)
            }
            if (autoPlay) {
                when {
                    offline != null || staged != null -> exo.play()
                    startTrack != null -> {
                        ensureResolved(exo.getMediaItemAt(safeStart), safeStart)
                        exo.playWhenReady = true
                    }
                }
            } else if (offline == null && staged == null && startTrack != null) {
                ensureResolved(exo.getMediaItemAt(safeStart), safeStart)
            }
            // Resolve URL for cache fill; upgrade incomplete excerpt only when online.
            if (startTrack != null && isNetworkUsable()) {
                serviceScope.launch {
                    val url = resolveForPlayback(startTrack, allowStale = true) ?: return@launch
                    persistResolved(url)
                    if (offline != null && OfflinePlayback.isIncomplete(offline)) {
                        upgradeOfflineToStream(startTrack, safeStart, url)
                    } else if (offline == null &&
                        staged != null &&
                        staged.expiresAtMs <= System.currentTimeMillis() + 60_000
                    ) {
                        refreshResolvedInBackground(startTrack, safeStart)
                    }
                }
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
            if (exo.mediaItemCount > index) {
                exo.seekToDefaultPosition(index)
                exo.prepare()
                exo.play()
            }
            prefetchAround(index)
        }
    }

    @Volatile
    private var handlingMediaEnded: Boolean = false

    /**
     * End of current MediaItem (pauseAtEndOfMediaItems).
     * Advance to next unless this is an explicitly incomplete offline excerpt.
     */
    private fun handleMediaEnded() {
        val exo = player ?: return
        if (handlingMediaEnded) return
        val index = exo.currentMediaItemIndex
        if (index < 0 || index >= exo.mediaItemCount) return
        val item = exo.currentMediaItem ?: return
        val track = trackIndex[item.mediaId]
        val knownMs = (track?.durationSeconds ?: 0L) * 1000L
        val mediaDur = exo.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        val pos = maxOf(exo.currentPosition, mediaDur).coerceAtLeast(0L)
        val uri = item.localConfiguration?.uri
        val fromOfflineFile = uri?.scheme == "file" || uri?.scheme == OfflinePlayback.SCHEME
        val tag = OfflinePlayback.tagOf(item)

        // Only block auto-next for tagged incomplete offline (or short file:// vs catalog).
        // A finished https/HLS MediaItem always advances — catalog duration can disagree.
        val incompleteExcerpt = track != null && (
            tag?.incomplete == true ||
                (fromOfflineFile && knownMs > 0L && mediaDur > 0L &&
                    mediaDur + 30_000L < knownMs)
            )

        if (incompleteExcerpt) {
            Log.i(
                TAG,
                "End of excerpt for ${track!!.id} pos=${pos}ms media=${mediaDur}ms " +
                    "track=${knownMs}ms — continue, do not skip"
            )
            handlingMediaEnded = true
            serviceScope.launch {
                try {
                    val resolved = resolveForPlayback(track, allowStale = true)
                    if (resolved != null && exo.mediaItemCount > index &&
                        exo.currentMediaItem?.mediaId == track.id
                    ) {
                        upgradeOfflineToStream(track, index, resolved, seekMs = pos)
                        return@launch
                    }
                    val rebuilt = withContext(Dispatchers.IO) {
                        OfflinePlayback.buildMediaItem(this@PlaybackService, track)
                    }
                    val rebuiltTag = OfflinePlayback.tagOf(rebuilt)
                    if (rebuilt != null && rebuiltTag != null &&
                        rebuiltTag.approxDurationMs > pos + OfflinePlayback.SEG_MS &&
                        exo.mediaItemCount > index &&
                        exo.currentMediaItem?.mediaId == track.id
                    ) {
                        withContext(Dispatchers.Main.immediate) {
                            exo.replaceMediaItem(index, rebuilt)
                            exo.seekTo(index, pos)
                            exo.prepare()
                            exo.play()
                        }
                        prefetchAround(index)
                    } else {
                        Log.i(
                            TAG,
                            "Waiting for more cache/network on ${track.id} (paused at excerpt end)"
                        )
                        prefetchAround(index)
                    }
                } finally {
                    handlingMediaEnded = false
                }
            }
            return
        }

        if (!exo.hasNextMediaItem()) {
            Log.i(TAG, "Track ended — no next item (index=$index)")
            return
        }
        handlingMediaEnded = true
        Log.i(
            TAG,
            "Track fully played — next item (id=${item.mediaId} pos=${pos}ms media=${mediaDur}ms)"
        )
        try {
            exo.seekToNextMediaItem()
            exo.prepare()
            exo.play()
        } finally {
            // Allow a later END on the next item; clear after transition settles.
            serviceScope.launch {
                delay(500)
                handlingMediaEnded = false
            }
        }
    }

    /** Swap incomplete file:// offline playlist for real HLS at [seekMs] (cache still used). */
    private suspend fun upgradeOfflineToStream(
        track: Track,
        index: Int,
        resolved: ResolvedAudio,
        seekMs: Long? = null
    ) {
        val exo = player ?: return
        if (index < 0 || index >= exo.mediaItemCount) return
        if (exo.currentMediaItem?.mediaId != track.id) return
        val pos = seekMs ?: exo.currentPosition.coerceAtLeast(0L)
        Log.i(TAG, "Upgrade ${track.id} offline→stream at ${pos}ms")
        withContext(Dispatchers.Main.immediate) {
            if (exo.currentMediaItem?.mediaId != track.id) return@withContext
            exo.replaceMediaItem(index, buildResolvedMediaItem(track, resolved))
            exo.seekTo(index, pos)
            exo.prepare()
            if (exo.playWhenReady || exo.isPlaying) exo.play()
        }
        prefetchAround(index)
    }

    /** Resume current item after pause without seeking to start. */
    fun resumeCurrent() {
        val exo = player ?: return
        val index = exo.currentMediaItemIndex
        if (index < 0 || index >= exo.mediaItemCount) return
        val item = exo.getMediaItemAt(index)
        val uri = item.localConfiguration?.uri
        when (uri?.scheme) {
            "youtubevoice" -> {
                serviceScope.launch {
                    val position = exo.currentPosition.coerceAtLeast(0L)
                    ensureResolved(item, index)
                    if (exo.mediaItemCount <= index) return@launch
                    val after = exo.getMediaItemAt(index).localConfiguration?.uri?.scheme
                    if (after == "youtubevoice") {
                        Log.w(TAG, "resumeCurrent: still placeholder, no disk/URL for ${item.mediaId}")
                        return@launch
                    }
                    if (position > 0) exo.seekTo(index, position)
                    exo.prepare()
                    exo.play()
                }
            }
            "http", "https" -> {
                // Prefer disk when offline so Play does not hang on dead CDN URLs.
                if (!isNetworkUsable()) {
                    serviceScope.launch {
                        fallbackToOfflineCache("resume_offline")
                        val now = player ?: return@launch
                        val scheme = now.currentMediaItem?.localConfiguration?.uri?.scheme
                        if (scheme == "file" || scheme == OfflinePlayback.SCHEME) {
                            now.play()
                        } else {
                            Log.w(TAG, "resumeCurrent offline: no playable disk cache")
                        }
                    }
                } else {
                    exo.play()
                }
            }
            else -> exo.play() // file / ytvcache — already playable
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                networkLost.set(true)
                Log.i(TAG, "Network lost — fallback to disk cache if possible")
                serviceScope.launch { fallbackToOfflineCache("network_lost") }
            }

            override fun onAvailable(network: Network) {
                // Connectivity callbacks run off the main thread — never touch ExoPlayer here.
                networkLost.set(false)
                serviceScope.launch { onNetworkRestored("available") }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                networkLost.set(false)
                serviceScope.launch { onNetworkRestored("validated") }
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
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
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

    /**
     * Network is back: recover hard errors, upgrade incomplete offline→HLS in background,
     * resume cache fill. Playback on a complete disk playlist is left alone.
     */
    private suspend fun onNetworkRestored(reason: String) {
        if (shouldForceRecover()) {
            Log.i(TAG, "Network $reason — recovering playback error")
            scheduleNetworkRecover()
        }
        upgradeIncompleteOfflineInBackground()
        val exo = player ?: return
        if (exo.mediaItemCount > 0) {
            restartPrefetch(exo.currentMediaItemIndex)
        }
    }

    /** Incomplete file:// / ytvcache excerpt → real HLS once online (keeps position). */
    private suspend fun upgradeIncompleteOfflineInBackground() {
        if (!isNetworkUsable()) return
        val exo = player ?: return
        val index = exo.currentMediaItemIndex
        if (index < 0 || index >= exo.mediaItemCount) return
        val item = exo.getMediaItemAt(index)
        val scheme = item.localConfiguration?.uri?.scheme
        if (scheme != "file" && scheme != OfflinePlayback.SCHEME) return
        if (!OfflinePlayback.isIncomplete(item)) return
        val track = trackIndex[item.mediaId] ?: return
        Log.i(TAG, "Network back — upgrade incomplete offline ${track.id} → stream")
        val resolved = withTimeoutOrNull(20_000L) {
            resolveForPlayback(track, allowStale = true)
        } ?: run {
            Log.w(TAG, "Upgrade resolve timed out for ${track.id}")
            return
        }
        persistResolved(resolved)
        upgradeOfflineToStream(track, index, resolved)
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
                val diskBytes = withContext(Dispatchers.IO) {
                    AudioCacheStore.cachedBytesForTrack(track.id)
                }
                val offlineOk = !isNetworkUsable() || attempts <= 2
                // Offline-first: play disk whenever we can (always when network is down).
                if (diskBytes > 0L && offlineOk) {
                    val offline = withContext(Dispatchers.IO) {
                        OfflinePlayback.buildMediaItem(this@PlaybackService, track)
                    }
                    if (offline != null) {
                        Log.i(
                            TAG,
                            "Recovering ${track.id} from disk cache (${diskBytes}B, $reason)"
                        )
                        if (exo.mediaItemCount <= index) return@launch
                        exo.replaceMediaItem(index, offline)
                        exo.prepare()
                        if (position > 0) exo.seekTo(index, position)
                        if (resumePlay) exo.play()
                        if (isNetworkUsable()) {
                            serviceScope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        repository.resolveAudio(track.id, track.watchUrl)
                                    }
                                }.onSuccess { resolved ->
                                    persistResolved(resolved)
                                    Log.i(TAG, "Fill URL refreshed for ${track.id} after offline recover")
                                }
                                prefetchAround(index)
                            }
                        }
                        prefetchAround(index)
                        return@launch
                    }
                    // Saved HTTPS URL only helps when the network can reach the CDN.
                    val saved = StreamUrlStore.peek(track.id)
                    if (saved != null && isNetworkUsable()) {
                        Log.i(
                            TAG,
                            "Recovering ${track.id} from saved URL (disk=${diskBytes}B, $reason)"
                        )
                        if (exo.mediaItemCount <= index) return@launch
                        exo.replaceMediaItem(index, buildResolvedMediaItem(track, saved))
                        exo.prepare()
                        if (position > 0) exo.seekTo(index, position)
                        if (resumePlay) exo.play()
                        prefetchAround(index)
                        refreshResolvedInBackground(track, index)
                        return@launch
                    }
                }
                if (!isNetworkUsable()) {
                    Log.w(TAG, "Recovery aborted offline for ${track.id} ($reason)")
                    return@launch
                }
                if (forceInvalidate) {
                    repository.invalidate(track.id)
                }
                Log.i(TAG, "Recovering ${track.id} at ${position}ms ($reason, try=$attempts)")
                val resolved = withContext(Dispatchers.IO) {
                    repository.resolveAudio(track.id, track.watchUrl)
                }
                if (exo.mediaItemCount <= index) return@launch
                exo.replaceMediaItem(index, buildResolvedMediaItem(track, resolved))
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
                if (attempts < MAX_ERROR_RECOVERIES && isNetworkUsable()) {
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

    /** Switch current https/hls item to a local offline playlist when cache exists. */
    private suspend fun fallbackToOfflineCache(reason: String) {
        val exo = player ?: return
        val index = exo.currentMediaItemIndex
        if (index < 0 || index >= exo.mediaItemCount) return
        val item = exo.getMediaItemAt(index)
        val scheme = item.localConfiguration?.uri?.scheme
        if (scheme == "file" || scheme == OfflinePlayback.SCHEME) return
        val track = trackIndex[item.mediaId] ?: return
        val offline = withContext(Dispatchers.IO) {
            OfflinePlayback.buildMediaItem(this@PlaybackService, track)
        } ?: return
        val position = exo.currentPosition.coerceAtLeast(0L)
        val wasPlaying = exo.isPlaying || exo.playWhenReady
        Log.i(TAG, "fallbackToOfflineCache ${track.id} ($reason) pos=${position}ms")
        exo.replaceMediaItem(index, offline)
        exo.prepare()
        if (position > 0) exo.seekTo(index, position)
        if (wasPlaying) exo.play()
    }

    private fun isNetworkUsable(): Boolean {
        if (networkLost.get()) return false
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun reloadCacheSettings() {
        serviceScope.launch(Dispatchers.IO) {
            AudioCacheStore.reloadIfNeeded(this@PlaybackService)
            withContext(Dispatchers.Main) {
                val mediaCallFactory = Call.Factory { request ->
                    val client = okHttpClient ?: mediaHttpClient().also { okHttpClient = it }
                    val url = request.url.toString()
                    val ua = streamUserAgents[url] ?: userAgentForStreamUrl(url)
                    val withUa = if (request.header("User-Agent").isNullOrBlank()) {
                        request.newBuilder().header("User-Agent", ua).build()
                    } else {
                        request
                    }
                    client.newCall(withUa)
                }
                val cacheFactory = buildCacheDataSourceFactory(mediaCallFactory)
                cacheDataSourceFactory = cacheFactory
                if (::swappableDataSourceFactory.isInitialized) {
                    swappableDataSourceFactory.delegate =
                        DefaultDataSource.Factory(this@PlaybackService, cacheFactory)
                }
                val exo = player
                Log.i(
                    TAG,
                    "Cache settings reloaded maxMb=${CacheSettingsStore.current().maxCacheMb} " +
                        "keys=${AudioCacheStore.keyCount()} bytes=${AudioCacheStore.cachedBytes()}"
                )
                // New room under a larger ceiling — resume fill immediately (no seek needed).
                if (exo != null && exo.mediaItemCount > 0) {
                    restartPrefetch(exo.currentMediaItemIndex)
                }
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

    /** Cancel in-flight fill and start again from the current playhead. */
    private fun restartPrefetch(centerIndex: Int) {
        prefetchJob?.cancel()
        prefetchCenter = -1
        prefetchAround(centerIndex)
    }

    private fun prefetchAround(centerIndex: Int) {
        // Already filling this center — keep the running job (background continuous fill).
        if (prefetchJob?.isActive == true && prefetchCenter == centerIndex) return
        prefetchJob?.cancel()
        prefetchCenter = centerIndex
        prefetchJob = serviceScope.launch {
            try {
                // Fill while a playlist is loaded — pause must NOT stop disk докачка.
                while (true) {
                    ensureActive()
                    val exo = player ?: break
                    if (exo.mediaItemCount <= 0) break
                    if (exo.currentMediaItemIndex != centerIndex) break
                    val madeProgress = prefetchMutex.withLock {
                        runPrefetchPass(centerIndex)
                    }
                    if (!madeProgress) {
                        delay(8_000)
                        val still = player ?: break
                        if (still.mediaItemCount <= 0) break
                        if (still.currentMediaItemIndex != centerIndex) break
                    } else {
                        // Brief yield so playback network isn't starved while playing.
                        delay(if (exo.isPlaying) 500L else 200L)
                    }
                }
            } finally {
                if (prefetchCenter == centerIndex) prefetchCenter = -1
            }
        }
    }

    /**
     * One pass over the play-ahead window. Returns true if any bytes were written.
     *
     * Anchor = playback position (play or pause). Already-played audio is ignored except a
     * small back-buffer. Download what still needs to play: remainder of current, then next
     * tracks in order, until [CacheSettings.maxCacheMb] is full or ahead-track count ends.
     */
    private suspend fun runPrefetchPass(centerIndex: Int): Boolean {
        val exo = player ?: return false
        val last = exo.mediaItemCount - 1
        if (last < 0) return false
        retainCacheWindow(centerIndex)
        val settings = CacheSettingsStore.current()
        // Fill only current + ahead (never spend budget on playlist-behind / already played).
        val order = prefetchIndexes(centerIndex, exo.mediaItemCount, settings)
        var wroteAny = false

        val positionMs = if (exo.currentMediaItemIndex == centerIndex) {
            exo.currentPosition.coerceAtLeast(0L)
        } else {
            0L
        }

        for (index in order) {
            coroutineContext.ensureActive()
            if (index < 0 || index > last) continue
            val item = exo.getMediaItemAt(index)
            val track = trackIndex[item.mediaId] ?: continue
            val uri = item.localConfiguration?.uri
            val isCurrent = index == centerIndex && exo.currentMediaItemIndex == centerIndex

            val room = withContext(Dispatchers.IO) {
                settings.maxCacheBytes - AudioCacheStore.cachedBytes()
            }
            if (room < 256L * 1024L) {
                Log.i(TAG, "Prefetch stop: cache full room=${room}B max=${settings.maxCacheMb}MB")
                break
            }

            val progress = withContext(Dispatchers.IO) {
                TrackCacheProgress.snapshot(track.id, track.durationSeconds)
            }
            // Current: only need playhead→end. Next tracks: full file still to play.
            val done = if (isCurrent) {
                progress.upcomingComplete(positionMs)
            } else {
                progress.isComplete
            }
            if (done) continue

            Log.i(
                TAG,
                "Prefetch fill ${track.id} current=$isCurrent fromPos=${positionMs}ms " +
                    "toward maxCache=${settings.maxCacheMb}MB room=${room / (1024 * 1024)}MB"
            )

            try {
                val resolved = when (uri?.scheme) {
                    "http", "https" -> null
                    else -> {
                        if (isCurrent) {
                            resolveForPlayback(track, allowStale = true)
                        } else {
                            withTimeoutOrNull(12_000L) {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        repository.resolveAudio(track.id, track.watchUrl)
                                    }.getOrNull()
                                }
                            }?.also { persistResolved(it) }
                                ?: resolveForPlayback(track, allowStale = true)
                        }
                    }
                }
                val streamUrl = when {
                    uri?.scheme == "http" || uri?.scheme == "https" -> uri.toString()
                    resolved != null -> {
                        if (!isCurrent &&
                            uri?.scheme == "youtubevoice" &&
                            exo.mediaItemCount > index &&
                            trackIndex[track.id]?.id == track.id
                        ) {
                            exo.replaceMediaItem(index, buildResolvedMediaItem(track, resolved))
                        }
                        resolved.streamUrl
                    }
                    else -> StreamUrlStore.peek(track.id)?.streamUrl
                }
                if (streamUrl.isNullOrBlank()) {
                    Log.w(TAG, "Prefetch skip ${track.id}: no stream URL to fill cache")
                    continue
                }

                // Current: from playhead with a small back-buffer; next tracks from the start.
                val fromMs = if (isCurrent) {
                    (positionMs - PLAYHEAD_BACK_BUFFER_MS).coerceAtLeast(0L)
                } else {
                    0L
                }
                val beforeBytes = withContext(Dispatchers.IO) {
                    AudioCacheStore.cachedBytesForTrack(track.id)
                }
                var wrote = 0L
                var timedOut = false
                try {
                    withTimeout(90_000L) {
                        wrote = withContext(Dispatchers.IO) {
                            warmCache(
                                trackId = track.id,
                                streamUrl = streamUrl,
                                fillCompletely = true,
                                fromPositionMs = fromMs,
                                trackDurationMs = track.durationSeconds.coerceAtLeast(0L) * 1000L,
                            )
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    timedOut = true
                    val afterBytes = withContext(Dispatchers.IO) {
                        AudioCacheStore.cachedBytesForTrack(track.id)
                    }
                    wrote = (afterBytes - beforeBytes).coerceAtLeast(0L)
                    Log.w(TAG, "Prefetch warm timed out for ${track.id} delta=${wrote}B")
                }
                if (wrote <= 0L && !timedOut) {
                    Log.i(TAG, "Prefetch refresh URL for ${track.id} after empty warm")
                    withContext(Dispatchers.IO) {
                        repository.invalidate(track.id)
                    }
                    val fresh = withTimeoutOrNull(20_000L) {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                repository.resolveAudio(track.id, track.watchUrl)
                            }.getOrNull()
                        }
                    }
                    if (fresh != null) {
                        persistResolved(fresh)
                        val freshUrl = fresh.streamUrl
                        if (exo.mediaItemCount > index &&
                            exo.currentMediaItem?.mediaId == track.id
                        ) {
                            val pos = exo.currentPosition.coerceAtLeast(0L)
                            exo.replaceMediaItem(index, buildResolvedMediaItem(track, fresh))
                            if (pos > 0) exo.seekTo(index, pos)
                            exo.prepare()
                            if (exo.playWhenReady) exo.play()
                        }
                        wrote = try {
                            withTimeout(90_000L) {
                                withContext(Dispatchers.IO) {
                                    warmCache(
                                        trackId = track.id,
                                        streamUrl = freshUrl,
                                        fillCompletely = true,
                                        fromPositionMs = fromMs,
                                        trackDurationMs = track.durationSeconds.coerceAtLeast(0L) * 1000L,
                                    )
                                }
                            }
                        } catch (_: TimeoutCancellationException) {
                            val afterBytes = withContext(Dispatchers.IO) {
                                AudioCacheStore.cachedBytesForTrack(track.id)
                            }
                            (afterBytes - beforeBytes).coerceAtLeast(0L)
                        }
                    } else {
                        Log.w(TAG, "Prefetch refresh failed for ${track.id} — will retry later")
                    }
                }
                if (wrote > 0) {
                    wroteAny = true
                    // One writing target per pass; next loop continues the same or next track.
                    break
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TAG, "Prefetch failed for ${track.id}", t)
            }
        }
        return wroteAny
    }

    /**
     * @param fillCompletely current track: keep filling remaining duration from [fromPositionMs].
     * @return bytes newly written to disk cache this call.
     */
    private suspend fun warmCache(
        trackId: String,
        streamUrl: String,
        fillCompletely: Boolean = false,
        fromPositionMs: Long = 0L,
        trackDurationMs: Long = 0L,
    ): Long {
        val factory = cacheDataSourceFactory ?: return 0L
        coroutineContext.ensureActive()
        return try {
            if (isHlsUrl(streamUrl)) {
                warmHls(
                    trackId, streamUrl, factory, fillCompletely,
                    fromPositionMs, trackDurationMs
                )
            } else {
                Log.i(TAG, "Progressive warm $trackId full=$fillCompletely")
                AudioCacheKeys.withTrack(trackId) {
                    warmProgressive(trackId, streamUrl, factory, fillCompletely)
                }
            }
        } catch (t: java.util.concurrent.CancellationException) {
            throw t
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Exception) {
            Log.w(TAG, "warmCache fail $trackId: ${t.message}")
            0L
        }
    }

    private fun warmProgressive(
        trackId: String,
        streamUrl: String,
        factory: CacheDataSource.Factory,
        fillCompletely: Boolean
    ): Long {
        val already = AudioCacheStore.cachedBytesForTrack(trackId)
        val room = (CacheSettingsStore.current().maxCacheBytes - AudioCacheStore.cachedBytes())
            .coerceAtLeast(0L)
        val budget = minOf(64L * 1024L * 1024L, room.coerceAtLeast(1L * 1024L * 1024L))
        if (budget <= 0L || room <= 0L) return 0L
        var written = 0L
        val dataSource = factory.createDataSource()
        val dataSpec = DataSpec.Builder()
            .setUri(streamUrl)
            .setKey(trackId)
            .setPosition(already)
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
        fillCompletely: Boolean,
        fromPositionMs: Long = 0L,
        trackDurationMs: Long = 0L,
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

        val expectedSegs = if (trackDurationMs > 0L) {
            ((trackDurationMs + OfflinePlayback.SEG_MS - 1) / OfflinePlayback.SEG_MS).toInt()
        } else {
            0
        }
        val startGosq = (fromPositionMs / OfflinePlayback.SEG_MS).coerceAtLeast(0L)
        // Prefer clips from playback position forward; keep one seg of back-buffer.
        val fromGosq = (startGosq - 1).coerceAtLeast(0L)
        // Collect segs STARTING at playhead — do not burn maxOut on gosq 0…N prefix.
        val remainingSegs = if (expectedSegs > 0) {
            (expectedSegs - fromGosq.toInt()).coerceAtLeast(64)
        } else {
            480
        }
        val playlistCap = if (fillCompletely) 6L * 1024L * 1024L else 768L * 1024L
        val maxSegs = when {
            fillCompletely -> remainingSegs.coerceIn(64, 900)
            else -> 48
        }
        Log.i(
            TAG,
            "HLS warm start $trackId full=$fillCompletely fromGosq=$fromGosq " +
                "maxSegs=$maxSegs budgetPass=towardMaxCache"
        )
        val playlistBody = withContext(Dispatchers.IO) {
            downloadText(playlistUrl, maxBytes = playlistCap)
        }
        if (playlistBody.isNullOrBlank()) {
            Log.w(TAG, "HLS warm: playlist download failed for $trackId")
            return 0L
        }
        val segmentUrls = withContext(Dispatchers.IO) {
            parseM3u8MediaUrls(
                playlistBody,
                playlistUrl,
                maxOut = maxSegs,
                minGosq = fromGosq,
            )
        }
        if (segmentUrls.isEmpty()) {
            // Fallback: take a window by index when gosq tags are missing/mismatched.
            val all = withContext(Dispatchers.IO) {
                parseM3u8MediaUrls(playlistBody, playlistUrl, maxOut = maxSegs + fromGosq.toInt() + 16)
            }
            val sliced = if (fromGosq > 0 && all.size > fromGosq.toInt()) {
                all.drop(fromGosq.toInt().coerceAtMost(all.lastIndex))
            } else {
                all
            }.take(maxSegs)
            if (sliced.isEmpty()) {
                Log.w(
                    TAG,
                    "HLS warm: no segments for $trackId fromGosq=$fromGosq " +
                        "body=${playlistBody.length}B all=${all.size}"
                )
                return 0L
            }
            Log.i(TAG, "HLS warm fallback index-slice ${sliced.size}/${all.size} for $trackId")
            return warmHlsSegments(
                trackId, sliced, factory, fillCompletely, fromGosq, fromPositionMs, trackDurationMs
            )
        }

        val prioritized = segmentUrls
            .map { url -> url to (TrackCacheProgress.gosqOf(url) ?: Long.MAX_VALUE) }
            .sortedBy { it.second }
            .map { it.first }
        return warmHlsSegments(
            trackId, prioritized, factory, fillCompletely, fromGosq, fromPositionMs, trackDurationMs
        )
    }

    private suspend fun warmHlsSegments(
        trackId: String,
        prioritized: List<String>,
        factory: CacheDataSource.Factory,
        fillCompletely: Boolean,
        fromGosq: Long,
        fromPositionMs: Long,
        trackDurationMs: Long,
    ): Long {
        // Always fill toward remaining maxCache room (large passes).
        val room = (CacheSettingsStore.current().maxCacheBytes - AudioCacheStore.cachedBytes())
            .coerceAtLeast(0L)
        var budget = minOf(64L * 1024L * 1024L, room.coerceAtLeast(if (fillCompletely) 1L * 1024L * 1024L else 0L))
        var warmed = 0
        var skippedCached = 0
        var writtenTotal = 0L
        if (budget <= 0L) {
            Log.i(TAG, "HLS warm $trackId skip: budget=0 room=${room}B")
            return 0L
        }
        for (segmentUrl in prioritized) {
            if (budget <= 0) break
            coroutineContext.ensureActive()
            try {
                val wrote = withContext(Dispatchers.IO) {
                    AudioCacheKeys.withTrack(trackId) {
                        var written = 0L
                        val dataSource = factory.createDataSource()
                        val uri = android.net.Uri.parse(segmentUrl)
                        val cacheKey = AudioCacheKeys.keyForTrackUri(trackId, uri)
                        val spec = DataSpec.Builder()
                            .setUri(uri)
                            .setKey(cacheKey)
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
                    delay(if (fillCompletely) 20L else 40L)
                } else {
                    skippedCached++
                }
            } catch (t: Exception) {
                if (t is java.util.concurrent.CancellationException ||
                    t is kotlinx.coroutines.CancellationException
                ) {
                    throw t
                }
                Log.w(TAG, "HLS segment warm fail $trackId: ${t.message}")
                delay(100)
            }
        }
        AudioCacheStore.dedupeTrackSegments(trackId)
        val progress = TrackCacheProgress.snapshot(
            trackId,
            if (trackDurationMs > 0) trackDurationMs / 1000L else 0L
        )
        val aheadMs = progress.cachedAheadOf(fromPositionMs.coerceAtLeast(0L))
        Log.i(
            TAG,
            "HLS warm $trackId full=$fillCompletely fromGosq=$fromGosq " +
                "segs=$warmed cachedSkip=$skippedCached bytes=$writtenTotal " +
                "listed=${prioritized.size} " +
                "cached=${progress.uniqueSegments}/${progress.expectedSegments} " +
                "aheadMs=$aheadMs complete=${progress.isComplete} " +
                "keys=${AudioCacheStore.keyCount()}"
        )
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
                if (!response.isSuccessful) {
                    Log.w(TAG, "downloadText HTTP ${response.code} for $url")
                    return null
                }
                val source = response.body?.source() ?: return null
                source.request(maxBytes + 1)
                val buf = source.buffer
                if (buf.size > maxBytes) {
                    Log.w(TAG, "downloadText truncated ${buf.size} > $maxBytes for $url")
                }
                buf.readUtf8(minOf(buf.size, maxBytes))
            }
        }.onFailure {
            Log.w(TAG, "downloadText error: ${it.message}")
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

    /**
     * @param minGosq skip media segments before this sequence (playhead). maxOut applies
     * only to kept segments so mid-track fill is not stuck on the playlist prefix.
     */
    private fun parseM3u8MediaUrls(
        body: String,
        playlistUrl: String,
        depth: Int = 0,
        maxOut: Int = 48,
        minGosq: Long = 0L,
    ): List<String> {
        if (depth > 2 || maxOut <= 0) return emptyList()
        val base = android.net.Uri.parse(playlistUrl)
        val out = ArrayList<String>()
        val nestedPlaylists = ArrayList<String>()
        var skippedPrefix = 0
        var unknownIndex = 0L

        fun resolveLine(line: String): String = when {
            line.startsWith("http://") || line.startsWith("https://") -> line
            line.startsWith("/") -> "${base.scheme}://${base.host}$line"
            else -> {
                val path = base.encodedPath.orEmpty()
                val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
                val prefix = if (parent.isEmpty()) "" else "$parent/"
                "${base.scheme}://${base.host}$prefix$line"
            }
        }

        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val absolute = resolveLine(line)
            if (isHlsPlaylistUrl(absolute)) {
                nestedPlaylists += absolute
                continue
            }
            val gosq = TrackCacheProgress.gosqOf(absolute)
            when {
                gosq != null && gosq < minGosq -> {
                    skippedPrefix++
                    continue
                }
                gosq == null && minGosq > 0L -> {
                    // Ordered playlist without gosq: skip first minGosq media lines.
                    if (unknownIndex < minGosq) {
                        unknownIndex++
                        skippedPrefix++
                        continue
                    }
                }
            }
            out += absolute
            if (out.size >= maxOut) break
        }

        if (out.isNotEmpty()) {
            if (skippedPrefix > 0) {
                Log.i(
                    TAG,
                    "HLS parse kept=${out.size} skippedPrefix=$skippedPrefix minGosq=$minGosq"
                )
            }
            return out
        }

        // Master playlist: prefer audio renditions (233/234/139…) over video.
        val ordered = nestedPlaylists.sortedByDescending { audioPlaylistScore(it) }
        for (nestedUrl in ordered) {
            if (out.size >= maxOut) break
            val nested = downloadText(nestedUrl, maxBytes = 6L * 1024L * 1024L) ?: continue
            out += parseM3u8MediaUrls(
                nested,
                nestedUrl,
                depth + 1,
                maxOut = maxOut - out.size,
                minGosq = minGosq,
            )
        }
        return out
    }

    /** Higher = more likely audio-only YouTube HLS. */
    private fun audioPlaylistScore(url: String): Int {
        val u = url.lowercase()
        val itag = Regex("itag[=/](\\d+)").find(u)?.groupValues?.get(1)?.toIntOrNull()
        return when (itag) {
            233, 234 -> 100
            139, 140, 141, 249, 250, 251, 599, 600 -> 90
            230, 231, 232 -> 10 // video HLS
            in 133..137, in 160..199, in 298..304 -> 5
            else -> when {
                u.contains("audio") || u.contains("maudio") -> 80
                u.contains("video") -> 5
                else -> 20
            }
        }
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
        val httpFactory = OkHttpDataSource.Factory(mediaCallFactory)
            .setUserAgent(ANDROID_UA)
        // ytvcache:// must never hit the network — only SimpleCache spans.
        val upstreamFactory = DataSource.Factory {
            object : DataSource {
                private var http: DataSource? = null
                private var openedUri: android.net.Uri? = null
                override fun open(dataSpec: DataSpec): Long {
                    openedUri = dataSpec.uri
                    if (dataSpec.uri.scheme == OfflinePlayback.SCHEME) {
                        throw IOException("cache-only miss for ${dataSpec.uri}")
                    }
                    val ds = httpFactory.createDataSource().also { http = it }
                    return ds.open(dataSpec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    http?.read(buffer, offset, length) ?: -1
                override fun getUri(): android.net.Uri? = http?.uri ?: openedUri
                override fun close() {
                    http?.close()
                    http = null
                }
                override fun addTransferListener(
                    transferListener: androidx.media3.datasource.TransferListener
                ) {
                    // OkHttp factory attaches listeners on create; no-op here.
                }
            }
        }
        return CacheDataSource.Factory()
            .setCache(AudioCacheStore.get(this))
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setCacheKeyFactory { dataSpec ->
                OfflinePlayback.cacheKeyFromUri(dataSpec.uri)
                    ?: AudioCacheKeys.keyFor(dataSpec)
            }
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
                // Still warm current toward maxCache — "ahead" only affects neighbor order after.
                buildList {
                    add(center)
                    for (offset in 1..settings.prefetchAhead) {
                        val i = center + offset
                        if (i < count) add(i)
                    }
                }
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
            // Behind stays in retain window only — do not download already-played playlist items.
            CachePrefetchOrder.AROUND -> {
                buildList {
                    add(center)
                    for (offset in 1..settings.prefetchAhead) {
                        val i = center + offset
                        if (i < count) add(i)
                    }
                }
            }
        }
    }

    /**
     * Pick a playable [ResolvedAudio]: memory/repository first, then persisted URL
     * when disk cache exists (or URL still fresh). Network resolve is last.
     */
    private suspend fun resolveForPlayback(
        track: Track,
        allowStale: Boolean
    ): ResolvedAudio? {
        withContext(Dispatchers.IO) {
            StreamUrlStore.ensureHydrated(this@PlaybackService)
        }
        val diskBytes = withContext(Dispatchers.IO) {
            AudioCacheStore.cachedBytesForTrack(track.id)
        }
        val saved = StreamUrlStore.peek(track.id)
        val now = System.currentTimeMillis()
        if (saved != null) {
            val fresh = saved.expiresAtMs > now + 60_000
            // Stale URL is fine for disk fill / offline recovery — keep until refresh succeeds.
            if (fresh || allowStale) {
                Log.i(
                    TAG,
                    "Using saved stream for ${track.id} fresh=$fresh disk=${diskBytes}B"
                )
                return saved
            }
        }
        Log.i(
            TAG,
            "Resolving ${track.id} allowStale=$allowStale disk=${diskBytes}B " +
                "saved=${saved != null}"
        )
        // Don't await a stuck OkHttp call — abandon after timeout so UI can move on.
        val deferred = kotlinx.coroutines.CompletableDeferred<ResolvedAudio?>()
        val resolveJob = serviceScope.launch(Dispatchers.IO) {
            val result = runCatching {
                repository.resolveAudio(track.id, track.watchUrl)
            }.onFailure {
                Log.e(TAG, "Failed to resolve ${track.id}", it)
            }.getOrNull()
            deferred.complete(result)
        }
        val fresh = withTimeoutOrNull(12_000L) { deferred.await() }
        if (fresh == null) {
            resolveJob.cancel()
            Log.w(
                TAG,
                "Resolve timeout for ${track.id} (disk=${diskBytes}B saved=${saved != null})"
            )
        } else {
            Log.i(TAG, "Resolved ${track.id} via network")
            return fresh
        }
        // Last resort: stale saved URL even without disk bytes.
        return if (allowStale) saved else null
    }

    private fun refreshResolvedInBackground(track: Track, index: Int) {
        serviceScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                runCatching {
                    repository.resolveAudio(track.id, track.watchUrl)
                }.getOrNull()
            } ?: return@launch
            val exo = player ?: return@launch
            if (exo.mediaItemCount <= index) return@launch
            if (exo.getMediaItemAt(index).mediaId != track.id) return@launch
            val currentUri = exo.getMediaItemAt(index).localConfiguration?.uri?.toString()
            if (currentUri == resolved.streamUrl) {
                persistResolved(resolved)
                return@launch
            }
            // Only swap when idle/buffering on the same item — avoid mid-play jump.
            val sameItem = exo.currentMediaItemIndex == index
            val position = if (sameItem) exo.currentPosition.coerceAtLeast(0L) else 0L
            val wasPlaying = exo.isPlaying || exo.playWhenReady
            if (sameItem && exo.playbackState == Player.STATE_READY && exo.isPlaying) {
                persistResolved(resolved)
                return@launch
            }
            exo.replaceMediaItem(index, buildResolvedMediaItem(track, resolved))
            if (sameItem) {
                exo.prepare()
                if (position > 0) exo.seekTo(index, position)
                if (wasPlaying) exo.play()
            }
        }
    }

    private fun persistResolved(resolved: ResolvedAudio) {
        serviceScope.launch(Dispatchers.IO) {
            runCatching { StreamUrlStore.save(this@PlaybackService, resolved) }
        }
    }

    /**
     * Swap [youtubevoice] placeholder for disk playlist (preferred) or stream URL.
     * Suspends until the swap finishes so callers can play without racing the network.
     */
    private suspend fun ensureResolved(mediaItem: MediaItem, index: Int) {
        val uri = mediaItem.localConfiguration?.uri
        if (uri?.scheme != "youtubevoice") return
        val track = trackIndex[mediaItem.mediaId] ?: return
        if (!resolvingIds.add(track.id)) return

        try {
            val exo = player ?: return
            val wasPlaying = exo.isPlaying
            val sameItem = exo.currentMediaItemIndex == index
            val position = if (sameItem) exo.currentPosition.coerceAtLeast(0L) else 0L

            // Disk first — never wait on YouTube just to start cached audio.
            val offline = withContext(Dispatchers.IO) {
                OfflinePlayback.buildMediaItem(this@PlaybackService, track)
            }
            if (offline != null && exo.mediaItemCount > index) {
                Log.i(
                    TAG,
                    "ensureResolved ${track.id} → disk cache " +
                        "(${AudioCacheStore.cachedBytesForTrack(track.id)}B)"
                )
                exo.replaceMediaItem(index, offline)
                if (sameItem) {
                    exo.prepare()
                    if (position > 0) exo.seekTo(index, position)
                    if (wasPlaying || exo.playWhenReady) exo.play()
                }
                // Upgrade incomplete excerpt only when online — offline must keep disk.
                if (isNetworkUsable()) {
                    serviceScope.launch {
                        val resolved = resolveForPlayback(track, allowStale = true) ?: return@launch
                        persistResolved(resolved)
                        if (OfflinePlayback.isIncomplete(offline)) {
                            upgradeOfflineToStream(track, index, resolved)
                        }
                    }
                }
                return
            }

            if (!isNetworkUsable()) {
                Log.w(TAG, "ensureResolved ${track.id}: no disk and offline — stay placeholder")
                return
            }

            val resolved = resolveForPlayback(track, allowStale = true) ?: return
            if (exo.mediaItemCount > index) {
                exo.replaceMediaItem(index, buildResolvedMediaItem(track, resolved))
                if (sameItem) {
                    exo.prepare()
                    if (position > 0) exo.seekTo(index, position)
                    if (wasPlaying || exo.playWhenReady) exo.play()
                }
                if (resolved.expiresAtMs <= System.currentTimeMillis() + 60_000) {
                    refreshResolvedInBackground(track, index)
                }
            }
        } catch (_: Exception) {
            // keep placeholder; error surfaces if user tries to play
        } finally {
            resolvingIds.remove(track.id)
        }
    }

    private fun buildPlaceholderMediaItem(track: Track): MediaItem {
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri("youtubevoice://track/${track.id}")
            .setCustomCacheKey(track.id)
            .setMediaMetadata(OfflinePlayback.mediaMetadata(track))
            .build()
    }

    private fun buildResolvedMediaItem(track: Track, resolved: ResolvedAudio): MediaItem {
        val streamUrl = resolved.streamUrl
        val userAgent = resolved.userAgent
        val mimeType = resolved.mimeType
        userAgent?.takeIf { it.isNotBlank() }?.let { streamUserAgents[streamUrl] = it }
        persistResolved(resolved.copy(trackId = track.id))

        val builder = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(streamUrl)
            .setCustomCacheKey(track.id)
            .setMediaMetadata(OfflinePlayback.mediaMetadata(track))

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
        const val ACTION_DEBUG_SEEK = "com.youtubevoice.app.action.DEBUG_SEEK"
        const val EXTRA_SEEK_MS = "seek_ms"
        /** Keep a little already-played audio; download window starts just before playhead. */
        private const val PLAYHEAD_BACK_BUFFER_MS = 30_000L
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

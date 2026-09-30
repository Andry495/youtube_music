package com.youtubevoice.app.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.youtubevoice.app.YoutubeVoiceApp
import com.youtubevoice.app.auth.DeviceGoogleAccount
import com.youtubevoice.app.data.ChannelBrowseTab
import com.youtubevoice.app.data.ChannelPage
import com.youtubevoice.app.data.LibraryPlaylist
import com.youtubevoice.app.data.LibraryTab
import com.youtubevoice.app.data.MainTab
import com.youtubevoice.app.data.PlaylistInfo
import com.youtubevoice.app.data.SearchHit
import com.youtubevoice.app.data.Subscription
import com.youtubevoice.app.data.Track
import com.youtubevoice.app.data.VideoRating
import com.youtubevoice.app.player.PlaybackService
import com.youtubevoice.app.youtube.NewPipeDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerUiState(
    val urlInput: String = "",
    val searchQuery: String = "",
    val searchResults: List<SearchHit> = emptyList(),
    val channelPage: ChannelPage? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val infoMessage: String? = null,
    val playlist: PlaylistInfo? = null,
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val isSignedIn: Boolean = false,
    val accountEmail: String? = null,
    val deviceAccounts: List<DeviceGoogleAccount> = emptyList(),
    val libraryPlaylists: List<LibraryPlaylist> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val mainTab: MainTab = MainTab.PLAYER,
    val libraryTab: LibraryTab = LibraryTab.PLAYLISTS,
    val currentRating: VideoRating = VideoRating.UNSPECIFIED,
    val isSubscribedToCurrent: Boolean = false,
    val currentSubscriptionId: String? = null,
    val showAddToPlaylist: Boolean = false,
    val showCreatePlaylist: Boolean = false
)

class PlayerViewModel : ViewModel() {
    private val repository get() = YoutubeVoiceApp.instance.youtubeRepository
    private val auth get() = YoutubeVoiceApp.instance.googleAuth
    private val api get() = YoutubeVoiceApp.instance.youtubeLibraryApi

    private var controller: MediaController? = null
    private var positionJob: Job? = null
    private var trackMetaJob: Job? = null

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromPlayer(player)
        }
    }

    init {
        refreshAccountUi()
        if (auth.isSignedIn) {
            NewPipeDownloader.setAuthCookie(auth.session?.cookie)
        }
    }

    fun attachController(mediaController: MediaController) {
        controller?.removeListener(playerListener)
        controller = mediaController
        mediaController.addListener(playerListener)
        syncFromPlayer(mediaController)
        startPositionUpdates()
    }

    fun detachController() {
        positionJob?.cancel()
        trackMetaJob?.cancel()
        controller?.removeListener(playerListener)
        controller = null
    }

    fun onUrlChange(value: String) {
        _uiState.update { it.copy(urlInput = value, error = null) }
    }

    fun onSearchQueryChange(value: String) {
        _uiState.update { it.copy(searchQuery = value, error = null) }
    }

    fun search() {
        val query = _uiState.value.searchQuery.trim()
        if (query.isEmpty()) {
            _uiState.update { it.copy(error = "Введите запрос: канал, плейлист или трек") }
            return
        }
        // Direct channel / playlist / video links from the search box
        if (query.contains("youtube.com") || query.contains("youtu.be") || query.startsWith("@")) {
            openFromQuery(query)
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, error = null, channelPage = null, mainTab = MainTab.SEARCH)
            }
            try {
                val results = runCatching { repository.search(query) }
                    .getOrElse {
                        val cookie = auth.session?.cookie
                        api.search(cookie, query)
                    }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        searchResults = results,
                        infoMessage = if (results.isEmpty()) "Ничего не найдено" else null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Ошибка поиска")
                }
            }
        }
    }

    fun openSearchHit(hit: SearchHit) {
        when (hit) {
            is SearchHit.Channel -> openChannel(hit.handle ?: hit.channelId)
            is SearchHit.Playlist -> openLibraryPlaylist(hit.playlistId)
            is SearchHit.Video -> {
                viewModelScope.launch {
                    _uiState.update { it.copy(isLoading = true, error = null) }
                    try {
                        applyPlaylist(repository.loadFromUrl(hit.watchUrl))
                    } catch (e: Exception) {
                        _uiState.update {
                            it.copy(isLoading = false, error = e.message ?: "Не удалось открыть видео")
                        }
                    }
                }
            }
        }
    }

    fun closeChannelPage() {
        _uiState.update { it.copy(channelPage = null) }
    }

    fun selectChannelTab(tab: ChannelBrowseTab) {
        val page = _uiState.value.channelPage ?: return
        if (page.tab == tab) {
            val needsLoad = when (tab) {
                ChannelBrowseTab.PLAYLISTS -> page.playlists.isEmpty()
                ChannelBrowseTab.VIDEOS -> page.videos.isEmpty()
            }
            if (!needsLoad) return
        }
        openChannel(page.handle ?: page.channelId, tab)
    }

    fun playChannelVideo(track: Track) {
        val videos = _uiState.value.channelPage?.videos.orEmpty()
        if (videos.isEmpty()) return
        val index = videos.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        val page = _uiState.value.channelPage
        applyPlaylist(
            PlaylistInfo(
                id = page?.channelId.orEmpty(),
                title = page?.title.orEmpty(),
                uploader = page?.title.orEmpty(),
                thumbnailUrl = page?.thumbnailUrl,
                tracks = videos
            )
        )
        waitForServiceAndPlay(videos, index)
    }

    private fun openFromQuery(query: String) {
        val q = query.trim()
        when {
            repository.isChannelUrl(q) || q.startsWith("@") -> openChannel(q)
            q.contains("list=") -> {
                val id = q.substringAfter("list=").substringBefore("&")
                openLibraryPlaylist(id)
            }
            else -> {
                viewModelScope.launch {
                    _uiState.update { it.copy(isLoading = true, error = null) }
                    try {
                        applyPlaylist(repository.loadFromUrl(q))
                    } catch (e: Exception) {
                        val msg = e.message.orEmpty()
                        if (msg.startsWith("CHANNEL:")) {
                            openChannel(msg.removePrefix("CHANNEL:"))
                        } else {
                            _uiState.update {
                                it.copy(isLoading = false, error = e.message ?: "Не удалось открыть")
                            }
                        }
                    }
                }
            }
        }
    }

    fun accountChooserIntent(): Intent = auth.accountChooserIntent()

    fun emailFromChooser(data: Intent?): String? = auth.emailFromChooserResult(data)

    fun loginIntent(email: String?): Intent = auth.loginIntent(email)

    fun onLoginSuccess(email: String?, cookie: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                require(!cookie.isNullOrBlank()) { "Не получена сессия YouTube" }
                val session = auth.persistSession(email, cookie)
                NewPipeDownloader.setAuthCookie(session.cookie)
                onSignedIn(session.email)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Не удалось сохранить вход"
                    )
                }
            }
        }
    }

    fun onAuthorizationCancelled() {
        _uiState.update {
            it.copy(isLoading = false, error = "Вход отменён")
        }
    }

    fun signOut() {
        viewModelScope.launch {
            auth.signOut()
            NewPipeDownloader.setAuthCookie(null)
            _uiState.update {
                it.copy(
                    isSignedIn = false,
                    accountEmail = null,
                    libraryPlaylists = emptyList(),
                    subscriptions = emptyList(),
                    mainTab = MainTab.PLAYER,
                    currentRating = VideoRating.UNSPECIFIED,
                    isSubscribedToCurrent = false,
                    currentSubscriptionId = null,
                    error = null,
                    infoMessage = null
                )
            }
            refreshAccountUi()
        }
    }

    fun selectMainTab(tab: MainTab) {
        _uiState.update { it.copy(mainTab = tab, error = null) }
        if (tab == MainTab.LIBRARY && _uiState.value.isSignedIn) {
            when (_uiState.value.libraryTab) {
                LibraryTab.PLAYLISTS -> refreshLibrary()
                LibraryTab.SUBSCRIPTIONS -> if (_uiState.value.subscriptions.isEmpty()) {
                    refreshSubscriptions()
                }
            }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(error = null, infoMessage = null) }
    }

    fun selectLibraryTab(tab: LibraryTab) {
        _uiState.update { it.copy(libraryTab = tab) }
        if (tab == LibraryTab.SUBSCRIPTIONS && _uiState.value.subscriptions.isEmpty()) {
            refreshSubscriptions()
        }
        if (tab == LibraryTab.PLAYLISTS) {
            refreshLibrary()
        }
    }

    fun refreshLibrary() {
        val cookie = requireCookie() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val playlists = api.listMyPlaylists(cookie)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        libraryPlaylists = playlists,
                        mainTab = MainTab.LIBRARY,
                        libraryTab = LibraryTab.PLAYLISTS
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Не удалось загрузить плейлисты")
                }
            }
        }
    }

    fun refreshSubscriptions() {
        val cookie = requireCookie() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val subs = api.listMySubscriptions(cookie)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        subscriptions = subs,
                        libraryTab = LibraryTab.SUBSCRIPTIONS,
                        mainTab = MainTab.LIBRARY
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Не удалось загрузить подписки")
                }
            }
        }
    }

    fun openLibraryPlaylist(playlistId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val id = playlistId.trim()
                    .removePrefix("VL")
                    .substringAfter("list=")
                    .substringBefore("&")
                    .substringBefore("?")
                require(id.isNotBlank()) { "Некорректный плейлист" }

                val playlist = withContext(Dispatchers.IO) {
                    val fromNewPipe = runCatching {
                        repository.loadPlaylistById(id, maxTracks = MAX_PLAYLIST_TRACKS)
                    }.getOrNull()
                    if (fromNewPipe != null && fromNewPipe.tracks.isNotEmpty()) {
                        return@withContext fromNewPipe
                    }
                    // NewPipe often fails on channel playlists; InnerTube /next works as guest
                    val fromInner = api.loadPlaylistTracks(id, auth.session?.cookie)
                    if (fromInner.tracks.size > MAX_PLAYLIST_TRACKS) {
                        fromInner.copy(tracks = fromInner.tracks.take(MAX_PLAYLIST_TRACKS))
                    } else {
                        fromInner
                    }
                }
                if (playlist.tracks.isEmpty()) {
                    _uiState.update {
                        it.copy(isLoading = false, error = "Не удалось загрузить треки плейлиста")
                    }
                    return@launch
                }
                applyPlaylist(playlist, loadMeta = false)
            } catch (t: Throwable) {
                android.util.Log.e("YoutubeVoice", "openLibraryPlaylist failed", t)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Не удалось загрузить треки плейлиста"
                    )
                }
            }
        }
    }

    fun openChannel(
        channelRef: String,
        tab: ChannelBrowseTab = ChannelBrowseTab.PLAYLISTS
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val page = repository.loadChannelPage(channelRef, tab)
                val enriched = if (
                    tab == ChannelBrowseTab.PLAYLISTS &&
                    page.playlists.isEmpty()
                ) {
                    val cookie = auth.session?.cookie
                    if (!cookie.isNullOrBlank()) {
                        val playlists = runCatching {
                            api.loadChannelPlaylists(cookie, page.channelId)
                        }.getOrDefault(emptyList())
                        page.copy(playlists = playlists)
                    } else page
                } else if (
                    tab == ChannelBrowseTab.VIDEOS &&
                    page.videos.isEmpty()
                ) {
                    val cookie = auth.session?.cookie
                    if (!cookie.isNullOrBlank()) {
                        val videos = runCatching {
                            api.loadChannelVideos(cookie, page.channelId).tracks
                        }.getOrDefault(emptyList())
                        page.copy(videos = videos)
                    } else page
                } else page

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        channelPage = enriched,
                        mainTab = MainTab.SEARCH,
                        error = when {
                            enriched.tab == ChannelBrowseTab.PLAYLISTS &&
                                enriched.playlists.isEmpty() ->
                                "Плейлисты канала не найдены"
                            enriched.tab == ChannelBrowseTab.VIDEOS &&
                                enriched.videos.isEmpty() ->
                                "Видео канала не найдены"
                            else -> null
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Не удалось открыть канал")
                }
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        val cookie = requireCookie() ?: return
        viewModelScope.launch {
            try {
                api.deletePlaylist(cookie, playlistId)
                _uiState.update {
                    it.copy(
                        libraryPlaylists = it.libraryPlaylists.filterNot { p -> p.id == playlistId },
                        infoMessage = "Плейлист удалён"
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Не удалось удалить плейлист") }
            }
        }
    }

    fun showCreatePlaylist(show: Boolean) {
        _uiState.update { it.copy(showCreatePlaylist = show) }
    }

    fun createPlaylist(title: String) {
        val cookie = requireCookie() ?: return
        val name = title.trim()
        if (name.isEmpty()) {
            _uiState.update { it.copy(error = "Введите название плейлиста") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val created = api.createPlaylist(cookie, name)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        showCreatePlaylist = false,
                        libraryPlaylists = listOf(created) + it.libraryPlaylists,
                        libraryTab = LibraryTab.PLAYLISTS,
                        mainTab = MainTab.LIBRARY,
                        infoMessage = "Плейлист «${created.title}» создан"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Не удалось создать плейлист")
                }
            }
        }
    }

    fun showAddToPlaylist(show: Boolean) {
        if (show && _uiState.value.libraryPlaylists.isEmpty()) {
            refreshLibrary()
        }
        _uiState.update { it.copy(showAddToPlaylist = show) }
    }

    fun addCurrentToPlaylist(playlistId: String) {
        val cookie = requireCookie() ?: return
        val videoId = _uiState.value.currentTrack?.id ?: return
        viewModelScope.launch {
            try {
                api.addVideoToPlaylist(cookie, playlistId, videoId)
                _uiState.update {
                    it.copy(
                        showAddToPlaylist = false,
                        infoMessage = "Добавлено в плейлист"
                    )
                }
                refreshLibrary()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message ?: "Не удалось добавить в плейлист")
                }
            }
        }
    }

    fun removeCurrentFromOpenPlaylist() {
        val cookie = requireCookie() ?: return
        val itemId = _uiState.value.currentTrack?.playlistItemId
        val playlistId = _uiState.value.playlist?.id
        if (itemId.isNullOrBlank() || playlistId.isNullOrBlank()) {
            _uiState.update { it.copy(error = "Трек открыт не из вашего плейлиста") }
            return
        }
        viewModelScope.launch {
            try {
                api.removePlaylistItem(cookie, "$playlistId|$itemId")
                val trackId = _uiState.value.currentTrack?.id
                _uiState.update { state ->
                    val updatedTracks = state.playlist?.tracks?.filterNot { it.id == trackId }
                    state.copy(
                        playlist = state.playlist?.copy(tracks = updatedTracks.orEmpty()),
                        infoMessage = "Удалено из плейлиста"
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Не удалось удалить из плейлиста") }
            }
        }
    }

    fun rateCurrent(rating: VideoRating) {
        val cookie = requireCookie() ?: return
        val videoId = _uiState.value.currentTrack?.id ?: return
        val next = if (_uiState.value.currentRating == rating) VideoRating.NONE else rating
        viewModelScope.launch {
            try {
                api.setRating(cookie, videoId, next)
                _uiState.update {
                    it.copy(
                        currentRating = next,
                        infoMessage = when (next) {
                            VideoRating.LIKE -> "Нравится"
                            VideoRating.DISLIKE -> "Не нравится"
                            else -> "Оценка снята"
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Не удалось поставить оценку") }
            }
        }
    }

    fun toggleSubscribeCurrent() {
        val cookie = requireCookie() ?: return
        viewModelScope.launch {
            try {
                var channelId = _uiState.value.currentTrack?.channelId
                val videoId = _uiState.value.currentTrack?.id
                if (channelId.isNullOrBlank() && videoId != null) {
                    channelId = api.resolveVideoChannelId(cookie, videoId)
                    if (channelId != null) {
                        _uiState.update { state ->
                            val track = state.currentTrack?.copy(channelId = channelId)
                            val tracks = state.playlist?.tracks?.map {
                                if (it.id == videoId) it.copy(channelId = channelId) else it
                            }
                            state.copy(
                                currentTrack = track,
                                playlist = state.playlist?.copy(tracks = tracks.orEmpty())
                            )
                        }
                    }
                }
                if (channelId.isNullOrBlank()) {
                    _uiState.update { it.copy(error = "Не удалось определить канал") }
                    return@launch
                }

                if (_uiState.value.isSubscribedToCurrent) {
                    val subId = _uiState.value.currentSubscriptionId
                        ?: api.findSubscriptionId(cookie, channelId)
                    if (subId != null) {
                        api.unsubscribe(cookie, subId)
                    }
                    _uiState.update {
                        it.copy(
                            isSubscribedToCurrent = false,
                            currentSubscriptionId = null,
                            subscriptions = it.subscriptions.filterNot { s -> s.channelId == channelId },
                            infoMessage = "Подписка отменена"
                        )
                    }
                } else {
                    val subId = api.subscribe(cookie, channelId)
                    _uiState.update {
                        it.copy(
                            isSubscribedToCurrent = true,
                            currentSubscriptionId = subId,
                            infoMessage = "Подписка оформлена"
                        )
                    }
                    refreshSubscriptions()
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Ошибка подписки") }
            }
        }
    }

    fun unsubscribe(subscriptionId: String) {
        val cookie = requireCookie() ?: return
        viewModelScope.launch {
            try {
                api.unsubscribe(cookie, subscriptionId)
                _uiState.update {
                    it.copy(
                        subscriptions = it.subscriptions.filterNot { s -> s.id == subscriptionId },
                        infoMessage = "Подписка удалена",
                        isSubscribedToCurrent = if (it.currentSubscriptionId == subscriptionId) {
                            false
                        } else {
                            it.isSubscribedToCurrent
                        },
                        currentSubscriptionId = if (it.currentSubscriptionId == subscriptionId) {
                            null
                        } else {
                            it.currentSubscriptionId
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Не удалось отписаться") }
            }
        }
    }

    fun loadUrl() {
        val url = _uiState.value.urlInput.trim()
        if (url.isEmpty()) {
            _uiState.update { it.copy(error = "Вставьте ссылку на видео, плейлист или канал") }
            return
        }
        openFromQuery(url)
    }

    fun playTrackAt(index: Int) {
        val tracks = _uiState.value.playlist?.tracks ?: return
        if (index !in tracks.indices) return
        val service = PlaybackService.instance
        if (service != null && controller?.mediaItemCount == tracks.size) {
            service.playTrackAt(index)
        } else {
            waitForServiceAndPlay(tracks, index)
        }
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
            return
        }
        // Placeholder youtubevoice:// URIs can't play — resolve via service first
        val service = PlaybackService.instance
        val index = c.currentMediaItemIndex
        if (service != null && index >= 0) {
            service.playTrackAt(index)
        } else {
            c.play()
        }
    }

    fun skipNext() = controller?.seekToNextMediaItem()
    fun skipPrevious() = controller?.seekToPreviousMediaItem()
    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
        _uiState.update { it.copy(shuffle = c.shuffleModeEnabled) }
    }

    fun cycleRepeat() {
        val c = controller ?: return
        val next = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        c.repeatMode = next
        _uiState.update { it.copy(repeatMode = next) }
    }

    private fun applyPlaylist(playlist: PlaylistInfo, loadMeta: Boolean = true) {
        val capped = if (playlist.tracks.size > MAX_PLAYLIST_TRACKS) {
            playlist.copy(tracks = playlist.tracks.take(MAX_PLAYLIST_TRACKS))
        } else {
            playlist
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                playlist = capped,
                currentTrack = capped.tracks.firstOrNull(),
                mainTab = MainTab.PLAYER,
                channelPage = null,
                error = if (capped.tracks.isEmpty()) "В плейлисте нет треков" else null
            )
        }
        if (capped.tracks.isNotEmpty()) {
            waitForServiceAndPlay(capped.tracks, 0)
            if (loadMeta) {
                refreshTrackMeta(capped.tracks.first())
            }
        }
    }

    private fun refreshTrackMeta(track: Track?) {
        trackMetaJob?.cancel()
        if (track == null || !auth.isSignedIn) {
            _uiState.update {
                it.copy(
                    currentRating = VideoRating.UNSPECIFIED,
                    isSubscribedToCurrent = false,
                    currentSubscriptionId = null
                )
            }
            return
        }
        val cookie = auth.session?.cookie ?: return
        // Use already-loaded subscriptions instead of re-fetching the whole guide (OOM/crash)
        val knownSub = _uiState.value.subscriptions.firstOrNull { it.channelId == track.channelId }
        trackMetaJob = viewModelScope.launch {
            try {
                val rating = runCatching { api.getRating(cookie, track.id) }
                    .getOrDefault(VideoRating.UNSPECIFIED)
                var channelId = track.channelId
                if (channelId.isNullOrBlank()) {
                    channelId = runCatching { api.resolveVideoChannelId(cookie, track.id) }
                        .getOrNull()
                }
                val subId = when {
                    knownSub != null -> knownSub.id
                    channelId != null ->
                        _uiState.value.subscriptions.firstOrNull { it.channelId == channelId }?.id
                    else -> null
                }
                _uiState.update { state ->
                    val updatedTrack = if (channelId != null && state.currentTrack?.id == track.id) {
                        state.currentTrack.copy(channelId = channelId)
                    } else {
                        state.currentTrack
                    }
                    state.copy(
                        currentTrack = updatedTrack,
                        currentRating = rating,
                        isSubscribedToCurrent = subId != null,
                        currentSubscriptionId = subId
                    )
                }
            } catch (t: Throwable) {
                android.util.Log.w("YoutubeVoice", "refreshTrackMeta failed", t)
            }
        }
    }

    private suspend fun onSignedIn(email: String?) {
        _uiState.update {
            it.copy(isLoading = false, isSignedIn = true, accountEmail = email, error = null)
        }
        refreshAccountUi()
        refreshLibrary()
        _uiState.value.currentTrack?.let { refreshTrackMeta(it) }
    }

    private fun refreshAccountUi() {
        val session = auth.session
        _uiState.update {
            it.copy(
                isSignedIn = session != null,
                accountEmail = session?.email,
                deviceAccounts = auth.listDeviceGoogleAccounts()
            )
        }
    }

    private fun requireCookie(): String? {
        val cookie = auth.session?.cookie
        if (cookie == null) {
            _uiState.update { it.copy(error = "Сначала войдите через Google-аккаунт") }
        }
        return cookie
    }

    private fun waitForServiceAndPlay(tracks: List<Track>, startIndex: Int) {
        viewModelScope.launch {
            repeat(40) {
                val service = PlaybackService.instance
                if (service != null) {
                    service.setPlaylist(tracks, startIndex, autoPlay = true)
                    return@launch
                }
                delay(100)
            }
            _uiState.update {
                it.copy(error = "Сервис воспроизведения не запустился. Попробуйте ещё раз.")
            }
        }
    }

    private fun syncFromPlayer(player: Player) {
        val mediaId = player.currentMediaItem?.mediaId
        val track = _uiState.value.playlist?.tracks?.firstOrNull { it.id == mediaId }
        val previousId = _uiState.value.currentTrack?.id
        _uiState.update {
            it.copy(
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.takeIf { d -> d > 0 } ?: 0L,
                currentTrack = track ?: it.currentTrack,
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode
            )
        }
        val newId = track?.id
        if (newId != null && newId != previousId) {
            refreshTrackMeta(track)
        }
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (isActive) {
                controller?.let { syncFromPlayer(it) }
                delay(500)
            }
        }
    }

    override fun onCleared() {
        detachController()
        super.onCleared()
    }

    companion object {
        private const val MAX_PLAYLIST_TRACKS = 40
    }
}

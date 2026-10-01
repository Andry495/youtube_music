package com.youtubevoice.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.PrimaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.youtubevoice.app.data.ChannelBrowseTab
import com.youtubevoice.app.data.ChannelPage
import com.youtubevoice.app.data.LibraryPlaylist
import com.youtubevoice.app.data.LibraryTab
import com.youtubevoice.app.data.MainTab
import com.youtubevoice.app.data.SearchHit
import com.youtubevoice.app.data.Subscription
import com.youtubevoice.app.data.Track
import com.youtubevoice.app.data.VideoRating
import com.youtubevoice.app.dpi.DpiStatus
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    onUrlChange: (String) -> Unit,
    onLoad: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onTrackClick: (Int) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSelectMainTab: (MainTab) -> Unit,
    onRefreshLibrary: () -> Unit,
    onRefreshSubscriptions: () -> Unit,
    onSelectLibraryTab: (LibraryTab) -> Unit,
    onOpenLibraryPlaylist: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onShowCreatePlaylist: (Boolean) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onShowAddToPlaylist: (Boolean) -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onRemoveFromPlaylist: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onToggleSubscribe: () -> Unit,
    onUnsubscribe: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenSearchHit: (SearchHit) -> Unit,
    onSelectChannelTab: (ChannelBrowseTab) -> Unit,
    onCloseChannel: () -> Unit,
    onPlayChannelVideo: (Track) -> Unit,
    onConsumeMessage: () -> Unit,
    onShowSettings: (Boolean) -> Unit,
    onDpiEnabledChange: (Boolean) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(state.error, state.infoMessage) {
        val message = state.error ?: state.infoMessage
        if (!message.isNullOrBlank()) {
            snackbar.showSnackbar(message)
            onConsumeMessage()
        }
    }

    if (state.showCreatePlaylist) {
        CreatePlaylistDialog(
            onDismiss = { onShowCreatePlaylist(false) },
            onConfirm = onCreatePlaylist
        )
    }
    if (state.showSettings) {
        SettingsSheet(
            dpiEnabled = state.dpiEnabledPreference,
            dpiStatus = state.dpiStatus,
            onDpiEnabledChange = onDpiEnabledChange,
            onDismiss = { onShowSettings(false) }
        )
    }
    if (state.showAddToPlaylist) {
        AddToPlaylistSheet(
            playlists = state.libraryPlaylists,
            onDismiss = { onShowAddToPlaylist(false) },
            onPick = onAddToPlaylist,
            onCreateNew = {
                onShowAddToPlaylist(false)
                onShowCreatePlaylist(true)
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = when (state.mainTab) {
                                MainTab.PLAYER -> "Слушать"
                                MainTab.SEARCH -> if (state.channelPage != null) {
                                    state.channelPage.title
                                } else {
                                    "Поиск"
                                }
                                MainTab.LIBRARY -> "Библиотека"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (state.isSignedIn && !state.accountEmail.isNullOrBlank()) {
                            Text(
                                text = state.accountEmail,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { onShowSettings(true) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                    if (state.isSignedIn) {
                        IconButton(onClick = onSignOut) {
                            Icon(Icons.Default.Logout, contentDescription = "Выйти")
                        }
                    } else {
                        FilledTonalButton(
                            onClick = onSignIn,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.AccountCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Аккаунт")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            Column {
                if (state.currentTrack != null) {
                    MiniPlayerBar(
                        state = state,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                        compact = state.mainTab != MainTab.PLAYER
                    )
                }
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = state.mainTab == MainTab.PLAYER,
                        onClick = { onSelectMainTab(MainTab.PLAYER) },
                        icon = {
                            Icon(
                                if (state.mainTab == MainTab.PLAYER) Icons.Default.Headphones
                                else Icons.Outlined.MusicNote,
                                contentDescription = null
                            )
                        },
                        label = { Text("Плеер") }
                    )
                    NavigationBarItem(
                        selected = state.mainTab == MainTab.SEARCH,
                        onClick = { onSelectMainTab(MainTab.SEARCH) },
                        icon = {
                            Icon(
                                if (state.mainTab == MainTab.SEARCH) Icons.Default.Search
                                else Icons.Outlined.Search,
                                contentDescription = null
                            )
                        },
                        label = { Text("Поиск") }
                    )
                    NavigationBarItem(
                        selected = state.mainTab == MainTab.LIBRARY,
                        onClick = { onSelectMainTab(MainTab.LIBRARY) },
                        icon = {
                            Icon(
                                if (state.mainTab == MainTab.LIBRARY) Icons.Default.LibraryMusic
                                else Icons.Outlined.LibraryMusic,
                                contentDescription = null
                            )
                        },
                        label = { Text("Медиатека") }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        AnimatedContent(
            targetState = state.mainTab,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            label = "main_tab"
        ) { tab ->
            when (tab) {
                MainTab.PLAYER -> PlayerHome(
                    state = state,
                    onUrlChange = onUrlChange,
                    onLoad = {
                        focusManager.clearFocus()
                        onLoad()
                    },
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onSeek = onSeek,
                    onTrackClick = onTrackClick,
                    onShuffle = onShuffle,
                    onRepeat = onRepeat,
                    onLike = onLike,
                    onDislike = onDislike,
                    onToggleSubscribe = onToggleSubscribe,
                    onAddToPlaylist = { onShowAddToPlaylist(true) },
                    onRemoveFromPlaylist = onRemoveFromPlaylist,
                    onSignIn = onSignIn
                )
                MainTab.SEARCH -> SearchHome(
                    state = state,
                    onQueryChange = onSearchQueryChange,
                    onSearch = {
                        focusManager.clearFocus()
                        onSearch()
                    },
                    onOpenHit = onOpenSearchHit,
                    onSelectChannelTab = onSelectChannelTab,
                    onCloseChannel = onCloseChannel,
                    onOpenPlaylist = onOpenLibraryPlaylist,
                    onPlayChannelVideo = onPlayChannelVideo
                )
                MainTab.LIBRARY -> LibraryHome(
                    state = state,
                    onSelectLibraryTab = onSelectLibraryTab,
                    onOpenPlaylist = onOpenLibraryPlaylist,
                    onDeletePlaylist = onDeletePlaylist,
                    onUnsubscribe = onUnsubscribe,
                    onOpenChannel = onOpenChannel,
                    onCreatePlaylist = { onShowCreatePlaylist(true) },
                    onRefreshLibrary = onRefreshLibrary,
                    onRefreshSubscriptions = onRefreshSubscriptions,
                    onSignIn = onSignIn
                )
            }
        }
    }
}

@Composable
private fun PlayerHome(
    state: PlayerUiState,
    onUrlChange: (String) -> Unit,
    onLoad: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onTrackClick: (Int) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onToggleSubscribe: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: () -> Unit,
    onSignIn: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SearchRow(
                value = state.urlInput,
                loading = state.isLoading,
                onValueChange = onUrlChange,
                onSubmit = onLoad
            )
        }

        val playlist = state.playlist
        val hasQueue = playlist != null && playlist.tracks.isNotEmpty()

        item {
            NowPlayingPanel(
                state = state,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPrevious = onPrevious,
                onSeek = onSeek,
                onShuffle = onShuffle,
                onRepeat = onRepeat,
                onLike = onLike,
                onDislike = onDislike,
                onToggleSubscribe = onToggleSubscribe,
                onAddToPlaylist = onAddToPlaylist,
                onRemoveFromPlaylist = onRemoveFromPlaylist,
                onSignIn = onSignIn,
                compactCover = hasQueue
            )
        }

        if (hasQueue) {
            item {
                Text(
                    text = "Очередь",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = "${playlist!!.title} · ${playlist.tracks.size} треков",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            itemsIndexed(
                playlist.tracks,
                key = { index, t -> "${t.id}_$index" }
            ) { index, track ->
                TrackListItem(
                    index = index,
                    track = track,
                    selected = track.id == state.currentTrack?.id,
                    onClick = { onTrackClick(index) }
                )
            }
        } else {
            item { EmptyPlayerHint() }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun SearchRow(
    value: String,
    loading: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Очистить")
                    }
                }
            },
            placeholder = { Text("Ссылка, @канал или поиск") },
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit() })
        )
        FilledIconButton(
            onClick = onSubmit,
            enabled = !loading && value.isNotBlank(),
            modifier = Modifier.size(52.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = "Слушать")
            }
        }
    }
}

@Composable
private fun NowPlayingPanel(
    state: PlayerUiState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onToggleSubscribe: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: () -> Unit,
    onSignIn: () -> Unit,
    compactCover: Boolean = false
) {
    val track = state.currentTrack
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (compactCover) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = track?.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track?.title ?: "Выберите что послушать",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track?.artist ?: "Вставьте ссылку YouTube выше",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    AsyncImage(
                        model = track?.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                                )
                            )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = track?.title ?: "Выберите что послушать",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track?.artist ?: "Вставьте ссылку YouTube выше",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (state.isSignedIn && track != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ActionIcon(
                        selected = state.currentRating == VideoRating.LIKE,
                        onClick = onLike,
                        image = Icons.Default.ThumbUp,
                        label = "Нравится"
                    )
                    ActionIcon(
                        selected = state.currentRating == VideoRating.DISLIKE,
                        onClick = onDislike,
                        image = Icons.Default.ThumbDown,
                        label = "Не нравится"
                    )
                    ActionIcon(
                        selected = state.isSubscribedToCurrent,
                        onClick = onToggleSubscribe,
                        image = if (state.isSubscribedToCurrent) {
                            Icons.Default.NotificationsOff
                        } else {
                            Icons.Default.Notifications
                        },
                        label = if (state.isSubscribedToCurrent) "Отписка" else "Подписка"
                    )
                    ActionIcon(
                        selected = false,
                        onClick = onAddToPlaylist,
                        image = Icons.Default.PlaylistAdd,
                        label = "В плейлист"
                    )
                    if (!track.playlistItemId.isNullOrBlank()) {
                        ActionIcon(
                            selected = false,
                            onClick = onRemoveFromPlaylist,
                            image = Icons.Default.PlaylistRemove,
                            label = "Убрать"
                        )
                    }
                }
            } else if (!state.isSignedIn) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text("Войти, чтобы лайкать и сохранять")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val duration = state.durationMs.coerceAtLeast(0L)
            val position = state.positionMs.coerceIn(0L, duration.takeIf { it > 0 } ?: state.positionMs)
            Slider(
                value = if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = { ratio ->
                    if (duration > 0) onSeek((ratio * duration).toLong())
                },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTime(position),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatTime(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onShuffle) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (state.shuffle) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous")
                }
                FilledIconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(32.dp)
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next")
                }
                IconButton(onClick = onRepeat) {
                    Icon(
                        when (state.repeatMode) {
                            Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                            Player.REPEAT_MODE_ALL -> Icons.Default.Repeat
                            else -> Icons.Default.Repeat
                        },
                        contentDescription = "Repeat",
                        tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionIcon(
    selected: Boolean,
    onClick: () -> Unit,
    image: androidx.compose.ui.graphics.vector.ImageVector,
    label: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Icon(image, contentDescription = label)
        }
    }
}

@Composable
private fun MiniPlayerBar(
    state: PlayerUiState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    compact: Boolean
) {
    val track = state.currentTrack ?: return
    val durationSafe = state.durationMs.coerceAtLeast(0L)
    val progress = if (durationSafe > 0) {
        state.positionMs.toFloat() / durationSafe
    } else {
        0f
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp
    ) {
        Column {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = track.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(if (compact) 40.dp else 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause"
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryHome(
    state: PlayerUiState,
    onSelectLibraryTab: (LibraryTab) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onUnsubscribe: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onRefreshSubscriptions: () -> Unit,
    onSignIn: () -> Unit
) {
    if (!state.isSignedIn) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    Icons.Outlined.LibraryMusic,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Войдите в аккаунт", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Выберите Google-аккаунт на телефоне — плейлисты и подписки подтянутся сами",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                FilledTonalButton(onClick = onSignIn) {
                    Text("Выбрать аккаунт")
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        YoutubeUnderlineTabs(
            selectedTabIndex = state.libraryTab.ordinal,
            titles = listOf("Плейлисты", "Подписки"),
            onSelect = { index ->
                if (index == 0) {
                    onSelectLibraryTab(LibraryTab.PLAYLISTS)
                    onRefreshLibrary()
                } else {
                    onSelectLibraryTab(LibraryTab.SUBSCRIPTIONS)
                    onRefreshSubscriptions()
                }
            }
        )

        when (state.libraryTab) {
            LibraryTab.PLAYLISTS -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${state.libraryPlaylists.size} плейлистов",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(onClick = onCreatePlaylist) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Создать")
                    }
                }
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.libraryPlaylists, key = { it.id }) { playlist ->
                        PlaylistRow(
                            playlist = playlist,
                            onOpen = { onOpenPlaylist(playlist.id) },
                            onDelete = { onDeletePlaylist(playlist.id) }
                        )
                    }
                }
            }
            LibraryTab.SUBSCRIPTIONS -> {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.subscriptions, key = { it.id }) { sub ->
                        SubscriptionRow(
                            sub = sub,
                            onOpen = { onOpenChannel(sub.channelId) },
                            onUnsubscribe = { onUnsubscribe(sub.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(
    playlist: LibraryPlaylist,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = playlist.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (playlist.itemCount > 0) "${playlist.itemCount} видео" else "Плейлист",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Удалить",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionRow(
    sub: Subscription,
    onOpen: () -> Unit,
    onUnsubscribe: () -> Unit
) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = sub.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sub.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = sub.description.ifBlank { "Канал YouTube" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onUnsubscribe) { Text("Отписка") }
        }
    }
}

@Composable
private fun TrackListItem(
    index: Int,
    track: Track,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${index + 1}",
                modifier = Modifier.width(28.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            AsyncImage(
                model = track.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (track.durationSeconds > 0) {
                Text(
                    text = formatTime(track.durationSeconds * 1000),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyPlayerHint() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Headphones,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text("Только звук, как у музыки", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Вставьте ссылку на клип или плейлист — приложение продолжит играть в фоне",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый плейлист") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                placeholder = { Text("Название") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title) }, enabled = title.isNotBlank()) {
                Text("Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToPlaylistSheet(
    playlists: List<LibraryPlaylist>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onCreateNew: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Text(
            text = "Добавить в плейлист",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        TextButton(
            onClick = onCreateNew,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Создать новый")
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(playlists, key = { it.id }) { playlist ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(playlist.id) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = playlist.title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${playlist.itemCount}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchHome(
    state: PlayerUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenHit: (SearchHit) -> Unit,
    onSelectChannelTab: (ChannelBrowseTab) -> Unit,
    onCloseChannel: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onPlayChannelVideo: (Track) -> Unit
) {
    val channel = state.channelPage
    if (channel != null) {
        ChannelBrowser(
            channel = channel,
            loading = state.isLoading,
            onBack = onCloseChannel,
            onSelectTab = onSelectChannelTab,
            onOpenPlaylist = onOpenPlaylist,
            onPlayVideo = onPlayChannelVideo
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchRow(
            value = state.searchQuery,
            loading = state.isLoading,
            onValueChange = onQueryChange,
            onSubmit = onSearch,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (state.searchResults.isEmpty() && !state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Поиск по YouTube", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Каналы, плейлисты и видео — или вставьте ссылку @kovalenkoaudio",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(state.searchResults, key = { it.id }) { hit ->
                    SearchHitRow(hit = hit, onClick = { onOpenHit(hit) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelBrowser(
    channel: ChannelPage,
    loading: Boolean,
    onBack: () -> Unit,
    onSelectTab: (ChannelBrowseTab) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onPlayVideo: (Track) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
            }
            AsyncImage(
                model = channel.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = channel.handle ?: "Канал YouTube",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        YoutubeUnderlineTabs(
            selectedTabIndex = channel.tab.ordinal,
            titles = listOf("Плейлисты", "Видео"),
            onSelect = { index ->
                onSelectTab(
                    if (index == 0) ChannelBrowseTab.PLAYLISTS
                    else ChannelBrowseTab.VIDEOS
                )
            }
        )

        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        when (channel.tab) {
            ChannelBrowseTab.PLAYLISTS -> {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (channel.playlists.isEmpty() && !loading) {
                        item {
                            Text(
                                text = "Плейлистов пока нет",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    }
                    items(channel.playlists, key = { it.id }) { playlist ->
                        PlaylistRow(
                            playlist = playlist,
                            onOpen = { onOpenPlaylist(playlist.id) }
                        )
                    }
                }
            }
            ChannelBrowseTab.VIDEOS -> {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (channel.videos.isEmpty() && !loading) {
                        item {
                            Text(
                                text = "Видео не найдены",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    }
                    items(channel.videos, key = { it.id }) { track ->
                        TrackListItem(
                            index = 0,
                            track = track,
                            selected = false,
                            onClick = { onPlayVideo(track) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHitRow(hit: SearchHit, onClick: () -> Unit) {
    val badge = when (hit) {
        is SearchHit.Channel -> "Канал"
        is SearchHit.Playlist -> "Плейлист"
        is SearchHit.Video -> "Видео"
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = hit.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(
                        if (hit is SearchHit.Channel) CircleShape
                        else RoundedCornerShape(12.dp)
                    )
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hit.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$badge · ${hit.subtitle}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YoutubeUnderlineTabs(
    selectedTabIndex: Int,
    titles: List<String>,
    onSelect: (Int) -> Unit
) {
    PrimaryTabRow(
        selectedTabIndex = selectedTabIndex,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        indicator = {
            PrimaryIndicator(
                modifier = Modifier.tabIndicatorOffset(
                    selectedTabIndex,
                    matchContentSize = true
                ),
                width = Dp.Unspecified,
                height = 2.dp,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        divider = {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    ) {
        titles.forEachIndexed { index, title ->
            Tab(
                selected = selectedTabIndex == index,
                onClick = { onSelect(index) },
                text = {
                    Text(
                        text = title,
                        fontWeight = if (selectedTabIndex == index) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        }
                    )
                },
                selectedContentColor = MaterialTheme.colorScheme.onSurface,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    dpiEnabled: Boolean,
    dpiStatus: DpiStatus,
    onDpiEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Сеть",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Локальный обход DPI только для YouTube Voice. Удалённый VPN-сервер не используется.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Обход DPI",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = when (dpiStatus) {
                            DpiStatus.Disconnected -> "Выключен"
                            DpiStatus.Connecting -> "Подключение…"
                            DpiStatus.Connected -> "Активен"
                            DpiStatus.Failed -> "Ошибка"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = dpiEnabled,
                    onCheckedChange = onDpiEnabledChange
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

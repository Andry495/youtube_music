package com.youtubevoice.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.youtubevoice.app.auth.YoutubeLoginActivity
import com.youtubevoice.app.dpi.DpiStatus
import com.youtubevoice.app.player.PlaybackService
import com.youtubevoice.app.ui.PlayerScreen
import com.youtubevoice.app.ui.PlayerViewModel
import com.youtubevoice.app.ui.theme.YoutubeVoiceTheme

@UnstableApi
class MainActivity : ComponentActivity() {
    private val viewModel: PlayerViewModel by viewModels()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var pendingLoginEmail: String? = null
    private var pendingAutoTune = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                viewModel.onVpnPermissionGranted()
            } else {
                viewModel.onVpnPermissionDenied()
            }
            if (pendingAutoTune) {
                pendingAutoTune = false
                viewModel.autoTuneDpi()
            }
        }

    private val accountChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) {
                viewModel.onAuthorizationCancelled()
                return@registerForActivityResult
            }
            val email = viewModel.emailFromChooser(result.data)
            pendingLoginEmail = email
            youtubeLoginLauncher.launch(viewModel.loginIntent(email))
        }

    private val youtubeLoginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val cookie = result.data?.getStringExtra(YoutubeLoginActivity.EXTRA_COOKIE)
                val email = result.data?.getStringExtra(YoutubeLoginActivity.EXTRA_EMAIL)
                    ?: pendingLoginEmail
                viewModel.onLoginSuccess(email, cookie)
            } else {
                viewModel.onAuthorizationCancelled()
            }
            pendingLoginEmail = null
        }

    private fun requestLocalDpiTunnel(thenAutoTune: Boolean = false) {
        if (!viewModel.setDpiEnabled(true)) return
        pendingAutoTune = thenAutoTune
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            vpnPermissionLauncher.launch(prepare)
        } else {
            viewModel.onVpnPermissionGranted()
            if (thenAutoTune) {
                pendingAutoTune = false
                viewModel.autoTuneDpi()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        handleIncomingIntent(intent)

        setContent {
            YoutubeVoiceTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()

                DisposableEffect(Unit) {
                    connectController()
                    onDispose { releaseController() }
                }

                LaunchedEffect(state.dpiEnabledPreference) {
                    if (state.dpiEnabledPreference) {
                        if (state.dpiStatus != DpiStatus.Connected &&
                            state.dpiStatus != DpiStatus.Connecting
                        ) {
                            // Prefer already-granted TUN; otherwise SOCKS without dialog spam.
                            viewModel.restoreDpiIfEnabled()
                        }
                    } else {
                        // Preference OFF — kill any TUN left from a prior cold-start race.
                        viewModel.ensureDpiStopped()
                    }
                }

                PlayerScreen(
                    state = state,
                    onUrlChange = viewModel::onUrlChange,
                    onLoad = viewModel::loadUrl,
                    onPlayPause = viewModel::togglePlayPause,
                    onNext = viewModel::skipNext,
                    onPrevious = viewModel::skipPrevious,
                    onSeek = viewModel::seekTo,
                    onTrackClick = viewModel::playTrackAt,
                    onShuffle = viewModel::toggleShuffle,
                    onRepeat = viewModel::cycleRepeat,
                    onSignIn = { accountChooserLauncher.launch(viewModel.accountChooserIntent()) },
                    onSignOut = viewModel::signOut,
                    onSelectMainTab = viewModel::selectMainTab,
                    onRefreshLibrary = viewModel::refreshLibrary,
                    onRefreshSubscriptions = viewModel::refreshSubscriptions,
                    onSelectLibraryTab = viewModel::selectLibraryTab,
                    onOpenLibraryPlaylist = viewModel::openLibraryPlaylist,
                    onDeletePlaylist = viewModel::deletePlaylist,
                    onShowCreatePlaylist = viewModel::showCreatePlaylist,
                    onCreatePlaylist = viewModel::createPlaylist,
                    onShowAddToPlaylist = viewModel::showAddToPlaylist,
                    onAddToPlaylist = viewModel::addCurrentToPlaylist,
                    onRemoveFromPlaylist = viewModel::removeCurrentFromOpenPlaylist,
                    onLike = { viewModel.rateCurrent(com.youtubevoice.app.data.VideoRating.LIKE) },
                    onDislike = { viewModel.rateCurrent(com.youtubevoice.app.data.VideoRating.DISLIKE) },
                    onToggleSubscribe = viewModel::toggleSubscribeCurrent,
                    onUnsubscribe = viewModel::unsubscribe,
                    onOpenChannel = viewModel::openChannel,
                    onSearchQueryChange = viewModel::onSearchQueryChange,
                    onSearch = viewModel::search,
                    onOpenSearchHit = viewModel::openSearchHit,
                    onSelectChannelTab = viewModel::selectChannelTab,
                    onCloseChannel = viewModel::closeChannelPage,
                    onPlayChannelVideo = viewModel::playChannelVideo,
                    onConsumeMessage = viewModel::consumeMessage,
                    onShowSettings = viewModel::showSettings,
                    onDpiEnabledChange = { enabled ->
                        if (enabled) {
                            requestLocalDpiTunnel(thenAutoTune = false)
                        } else {
                            viewModel.setDpiEnabled(false)
                        }
                    },
                    onDpiAutoTune = { requestLocalDpiTunnel(thenAutoTune = true) },
                    onCacheSettingsChange = viewModel::updateCacheSettings,
                    onClearAudioCache = viewModel::clearAudioCache,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.hasExtra(EXTRA_ENABLE_DPI)) {
            val enabled = intent.getBooleanExtra(EXTRA_ENABLE_DPI, true)
            if (enabled) {
                requestLocalDpiTunnel(
                    thenAutoTune = intent.getBooleanExtra(EXTRA_AUTO_TUNE_DPI, false)
                )
            } else {
                viewModel.setDpiEnabled(false)
            }
        } else if (intent.getBooleanExtra(EXTRA_AUTO_TUNE_DPI, false)) {
            requestLocalDpiTunnel(thenAutoTune = true)
        }
        intent.getStringExtra(EXTRA_DPI_PRESET)?.takeIf { it.isNotBlank() }?.let { id ->
            viewModel.setDpiPreset(id)
        }
        intent.getStringExtra(EXTRA_CACHE_EVICTION)?.takeIf { it.isNotBlank() }?.let { mode ->
            viewModel.updateCacheSettings { current ->
                val eviction = runCatching {
                    com.youtubevoice.app.player.CacheEvictionMode.valueOf(mode)
                }.getOrDefault(current.evictionMode)
                current.copy(evictionMode = eviction)
            }
        }
        val data = intent.data?.toString() ?: return
        if (data.contains("youtube.com") || data.contains("youtu.be")) {
            viewModel.onUrlChange(data)
            viewModel.loadUrl()
        }
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val controller = runCatching { future.get() }.getOrNull() ?: return@addListener
                viewModel.attachController(controller)
            },
            MoreExecutors.directExecutor()
        )
    }

    private fun releaseController() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        viewModel.detachController()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_ENABLE_DPI = "enable_dpi"
        const val EXTRA_AUTO_TUNE_DPI = "auto_tune_dpi"
        const val EXTRA_DPI_PRESET = "dpi_preset"
        /** Optional: WINDOW / LRU / WINDOW_AND_LRU */
        const val EXTRA_CACHE_EVICTION = "cache_eviction"
    }
}

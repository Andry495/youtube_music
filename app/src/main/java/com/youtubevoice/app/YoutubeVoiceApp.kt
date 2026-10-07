package com.youtubevoice.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.youtubevoice.app.auth.YoutubeAccountAuth
import com.youtubevoice.app.dpi.AppHttp
import com.youtubevoice.app.dpi.DpiFakeAssets
import com.youtubevoice.app.dpi.DpiLauncher
import com.youtubevoice.app.dpi.DpiSettingsStore
import com.youtubevoice.app.player.CacheSettingsStore
import com.youtubevoice.app.player.StreamUrlStore
import com.youtubevoice.app.youtube.NewPipeDownloader
import com.youtubevoice.app.youtube.YoutubeLibraryApi
import com.youtubevoice.app.youtube.YoutubeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class YoutubeVoiceApp : Application(), ImageLoaderFactory {
    val youtubeLibraryApi by lazy { YoutubeLibraryApi() }
    val youtubeRepository by lazy { YoutubeRepository(youtubeLibraryApi) }
    val googleAuth by lazy { YoutubeAccountAuth(this) }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashLogger()
        appScope.launch {
            runCatching { DpiFakeAssets.ensure(this@YoutubeVoiceApp) }
            runCatching {
                DpiSettingsStore.hydrate(this@YoutubeVoiceApp)
                // If user left DPI off, make sure no sticky TUN survives process restart.
                if (!DpiSettingsStore.isEnabled()) {
                    DpiLauncher.stop(this@YoutubeVoiceApp)
                }
            }
            runCatching { CacheSettingsStore.hydrate(this@YoutubeVoiceApp) }
            runCatching { StreamUrlStore.hydrate(this@YoutubeVoiceApp) }
        }
        NewPipe.init(NewPipeDownloader, Localization.DEFAULT)
        org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
            .setFetchIosClient(true)
        NewPipeDownloader.setAuthCookie(googleAuth.session?.cookie)
        createNotificationChannel()
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { AppHttp.client() }
            .crossfade(true)
            .build()

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val text = "Thread=${thread.name}\n$sw"
                Log.e(TAG, "Uncaught exception", error)
                File(filesDir, "crash.log").writeText(text)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "youtube_voice_playback"
        private const val TAG = "YoutubeVoice"

        @Volatile
        lateinit var instance: YoutubeVoiceApp
            private set
    }
}

package com.youtubevoice.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.youtubevoice.app.auth.YoutubeAccountAuth
import com.youtubevoice.app.youtube.NewPipeDownloader
import com.youtubevoice.app.youtube.YoutubeLibraryApi
import com.youtubevoice.app.youtube.YoutubeRepository
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class YoutubeVoiceApp : Application() {
    val youtubeLibraryApi by lazy { YoutubeLibraryApi() }
    val youtubeRepository by lazy { YoutubeRepository(youtubeLibraryApi) }
    val googleAuth by lazy { YoutubeAccountAuth(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashLogger()
        NewPipe.init(NewPipeDownloader, Localization.DEFAULT)
        org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
            .setFetchIosClient(true)
        NewPipeDownloader.setAuthCookie(googleAuth.session?.cookie)
        createNotificationChannel()
    }

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

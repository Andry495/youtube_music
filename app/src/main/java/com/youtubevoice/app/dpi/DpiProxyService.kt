package com.youtubevoice.app.dpi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.youtubevoice.app.MainActivity
import com.youtubevoice.app.R
import com.youtubevoice.app.player.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Local ByeDPI SOCKS5 proxy (no system VPN / TUN).
 * App HTTP clients point at 127.0.0.1:[PROXY_PORT] while this service runs.
 */
class DpiProxyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var proxyJob: Job? = null
    private var proxyFd: Int = -1
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                scope.launch { startProxy() }
                return START_STICKY
            }
            ACTION_STOP -> {
                scope.launch { stopProxy() }
                return START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        scope.cancel()
        AppHttp.setDpiProxyEnabled(false)
        PlaybackService.instance?.reloadHttpClient()
        super.onDestroy()
    }

    private suspend fun startProxy() {
        if (DpiController.status.value == DpiStatus.Connected) {
            Log.w(TAG, "Already connected")
            return
        }
        DpiController.setStatus(DpiStatus.Connecting)
        try {
            mutex.withLock {
                ensureNotificationChannel()
                startForegroundCompat()
                startByeDpi()
                // Let the listen/event loop settle before clients connect
                delay(250)
                AppHttp.setDpiProxyEnabled(true)
                PlaybackService.instance?.reloadHttpClient()
            }
            DpiController.setStatus(DpiStatus.Connected)
            Log.i(TAG, "ByeDPI SOCKS proxy connected on 127.0.0.1:${DpiSettingsStore.PROXY_PORT}")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start ByeDPI proxy", t)
            AppHttp.setDpiProxyEnabled(false)
            PlaybackService.instance?.reloadHttpClient()
            DpiController.setStatus(DpiStatus.Failed)
            stopProxy()
        }
    }

    private suspend fun stopProxy() {
        mutex.withLock {
            stopping = true
            try {
                AppHttp.setDpiProxyEnabled(false)
                PlaybackService.instance?.reloadHttpClient()
                stopByeDpi()
            } catch (t: Throwable) {
                Log.e(TAG, "Error while stopping ByeDPI", t)
            } finally {
                stopping = false
            }
        }
        DpiController.setStatus(DpiStatus.Disconnected)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startByeDpi() {
        if (proxyJob != null) throw IllegalStateException("proxy already running")

        val args = DpiSettingsStore.DEFAULT_ARGS.toTypedArray()
        Log.i(TAG, "Starting ByeDPI: ${args.joinToString(" ")}")
        val fd = ByeDpiNative.jniCreateSocketWithCommandLine(args)
        if (fd < 0) throw IllegalStateException("ByeDPI listen failed: $fd")
        proxyFd = fd

        proxyJob = scope.launch(Dispatchers.IO) {
            val code = ByeDpiNative.jniStartProxy(fd)
            Log.i(TAG, "ByeDPI event loop exited code=$code")
            if (!stopping) {
                withContext(Dispatchers.Main) {
                    DpiController.setStatus(DpiStatus.Failed)
                    stopProxy()
                }
            }
        }
    }

    private suspend fun stopByeDpi() {
        val fd = proxyFd
        if (fd >= 0) {
            runCatching { ByeDpiNative.jniStopProxy(fd) }
            proxyFd = -1
        }
        proxyJob?.join()
        proxyJob = null
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.dpi_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.dpi_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, DpiProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.dpi_notification_title))
            .setContentText(getString(R.string.dpi_proxy_notification_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.dpi_notification_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "DpiProxyService"
        private const val CHANNEL_ID = "dpi_proxy"
        private const val NOTIFICATION_ID = 43

        const val ACTION_START = "com.youtubevoice.app.dpi.PROXY_START"
        const val ACTION_STOP = "com.youtubevoice.app.dpi.PROXY_STOP"

        fun start(context: Context) {
            val intent = Intent(context, DpiProxyService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DpiProxyService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}

package com.youtubevoice.app.dpi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.youtubevoice.app.MainActivity
import com.youtubevoice.app.R
import com.youtubevoice.app.player.PlaybackService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local packet-level DPI path (no remote VPN server):
 * app traffic → Android TUN → hev-socks5-tunnel → ByeDPI → internet.
 *
 * ByeDPI outbound sockets are excluded from the TUN via `--protect-path`.
 * OkHttp SOCKS is left OFF so probes/player use the TUN path (stronger desync).
 */
class DpiVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var proxyJob: Job? = null
    private var protectJob: Job? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var proxyFd: Int = -1
    private var protectSocketPath: String? = null
    private var protectReady: CompletableDeferred<Unit>? = null
    private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                scope.launch { startDpi(forceRestart = false) }
                return START_STICKY
            }
            ACTION_RESTART -> {
                scope.launch { startDpi(forceRestart = true) }
                return START_STICKY
            }
            ACTION_STOP -> {
                scope.launch {
                    // Notification "Выключить" must persist OFF, otherwise UI restore races back on.
                    runCatching { DpiSettingsStore.setEnabled(applicationContext, false) }
                    stopDpi()
                }
                return START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "Local DPI TUN revoked by system")
        scope.launch { stopDpi() }
    }

    override fun onDestroy() {
        scope.cancel()
        AppHttp.setDpiModuleOff()
        PlaybackService.instance?.reloadHttpClient()
        super.onDestroy()
    }

    private suspend fun startDpi(forceRestart: Boolean) {
        if (!forceRestart && DpiController.status.value == DpiStatus.Connected) {
            Log.w(TAG, "Already connected")
            return
        }
        DpiController.setStatus(DpiStatus.Connecting)
        try {
            mutex.withLock {
                ensureNotificationChannel()
                startForegroundCompat()
                if (forceRestart || proxyJob != null || tunFd != null) {
                    stopping = true
                    try {
                        stopTun2Socks()
                        stopByeDpi()
                        stopProtectWorker()
                    } finally {
                        stopping = false
                    }
                    delay(200)
                }
                // No OkHttp SOCKS — traffic hits TUN → hev → ByeDPI.
                AppHttp.setDpiTunMode()
                PlaybackService.instance?.reloadHttpClient()

                startProtectWorker()
                protectReady?.await()
                startByeDpi()
                delay(250)
                startTun2Socks()
            }
            DpiController.setStatus(DpiStatus.Connected)
            Log.i(TAG, "Local DPI TUN connected (hev → ByeDPI :${DpiSettingsStore.PROXY_PORT})")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start local DPI TUN", t)
            AppHttp.setDpiModuleOff()
            PlaybackService.instance?.reloadHttpClient()
            DpiController.setStatus(DpiStatus.Failed)
            scope.launch { stopDpi() }
        }
    }

    private suspend fun stopDpi() {
        mutex.withLock {
            stopping = true
            try {
                AppHttp.setDpiModuleOff()
                PlaybackService.instance?.reloadHttpClient()
                stopTun2Socks()
                stopByeDpi()
                stopProtectWorker()
            } catch (t: Throwable) {
                Log.e(TAG, "Error while stopping DPI TUN", t)
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
        val protectPath = protectSocketPath
            ?: throw IllegalStateException("protect path missing")

        DpiFakeAssets.ensure(this)
        val args = DpiFakeAssets.resolveArgs(
            DpiSettingsStore.currentArgs() + listOf("--protect-path", protectPath)
        ).toTypedArray()
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
                    stopDpi()
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

    private fun startTun2Socks() {
        if (tunFd != null) throw IllegalStateException("TUN already open")

        val config = """
            |tunnel:
            |  name: yv-dpi
            |  mtu: 8500
            |  ipv4: 198.18.0.1
            |  ipv6: 'fc00::1'
            |  icmp: 'off'
            |socks5:
            |  port: ${DpiSettingsStore.PROXY_PORT}
            |  address: 127.0.0.1
            |  udp: 'udp'
            |misc:
            |  task-stack-size: 81920
            |  log-level: warn
        """.trimMargin("|")

        val configFile = File(cacheDir, "hev-socks5.yml").apply { writeText(config) }

        val builder = Builder()
            .setSession(getString(R.string.dpi_vpn_session))
            .setConfigureIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .addAddress("198.18.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // Only this app — not a device-wide remote VPN.
        builder.addAllowedApplication(packageName)

        val fd = builder.establish()
            ?: throw IllegalStateException("VpnService.Builder.establish() returned null")
        tunFd = fd

        val ok = TProxyNative.TProxyStartService(configFile.absolutePath, fd.fd)
        if (!ok) {
            fd.close()
            tunFd = null
            throw IllegalStateException("hev-socks5-tunnel failed to start")
        }
    }

    private fun stopTun2Socks() {
        runCatching { TProxyNative.TProxyStopService() }
        tunFd?.close()
        tunFd = null
        runCatching { File(cacheDir, "hev-socks5.yml").delete() }
    }

    private fun startProtectWorker() {
        val sockFile = File(noBackupFilesDir, "dpi_protect.sock")
        runCatching { sockFile.delete() }
        protectSocketPath = sockFile.absolutePath
        val ready = CompletableDeferred<Unit>()
        protectReady = ready

        protectJob = scope.launch(Dispatchers.IO) {
            try {
                // Ensure byedpi.so (and protect symbols) are loaded.
                ByeDpiNative
                val ok = DpiProtectNative.nativeStart(this@DpiVpnService, sockFile.absolutePath)
                if (!ok) throw IllegalStateException("DpiProtectNative.nativeStart failed")
                ready.complete(Unit)
                // Keep job alive until cancelled; native thread accepts clients.
                while (isActive && !stopping) {
                    delay(500)
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    if (!ready.isCompleted) ready.complete(Unit)
                    throw t
                }
                Log.e(TAG, "protect worker failed", t)
                if (!ready.isCompleted) ready.completeExceptionally(t)
            } finally {
                if (!stopping) {
                    // Only stop native server when not mid-restart; stopProtectWorker also calls nativeStop.
                }
                runCatching { sockFile.delete() }
            }
        }
    }

    private fun stopProtectWorker() {
        protectJob?.cancel()
        runCatching { DpiProtectNative.nativeStop() }
        protectJob = null
        protectSocketPath?.let { runCatching { File(it).delete() } }
        protectSocketPath = null
        protectReady = null
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
            Intent(this, DpiVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.dpi_notification_title))
            .setContentText(getString(R.string.dpi_notification_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.dpi_notification_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "DpiVpnService"
        private const val CHANNEL_ID = "dpi_vpn"
        private const val NOTIFICATION_ID = 42

        const val ACTION_START = "com.youtubevoice.app.dpi.VPN_START"
        const val ACTION_RESTART = "com.youtubevoice.app.dpi.VPN_RESTART"
        const val ACTION_STOP = "com.youtubevoice.app.dpi.VPN_STOP"

        fun start(context: Context) {
            val intent = Intent(context, DpiVpnService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun restart(context: Context) {
            val intent = Intent(context, DpiVpnService::class.java).setAction(ACTION_RESTART)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DpiVpnService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}

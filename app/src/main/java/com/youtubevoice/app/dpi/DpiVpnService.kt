package com.youtubevoice.app.dpi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.youtubevoice.app.MainActivity
import com.youtubevoice.app.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import java.lang.reflect.Field

/**
 * App-only local DPI: TUN → hev-socks5-tunnel → ByeDPI SOCKS5 → internet.
 * Outbound ByeDPI sockets are excluded from the TUN via --protect-path.
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
                scope.launch { startDpi() }
                return START_STICKY
            }
            ACTION_STOP -> {
                scope.launch { stopDpi() }
                return START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked")
        scope.launch { stopDpi() }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startDpi() {
        if (DpiController.status.value == DpiStatus.Connected) {
            Log.w(TAG, "Already connected")
            return
        }
        DpiController.setStatus(DpiStatus.Connecting)
        try {
            mutex.withLock {
                ensureNotificationChannel()
                startForegroundCompat()
                startProtectWorker()
                protectReady?.await()
                startByeDpi()
                startTun2Socks()
            }
            DpiController.setStatus(DpiStatus.Connected)
            Log.i(TAG, "DPI VPN connected (app-only)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start DPI VPN", t)
            DpiController.setStatus(DpiStatus.Failed)
            stopDpi()
        }
    }

    private suspend fun stopDpi() {
        mutex.withLock {
            stopping = true
            try {
                stopTun2Socks()
                stopByeDpi()
                stopProtectWorker()
            } catch (t: Throwable) {
                Log.e(TAG, "Error while stopping DPI VPN", t)
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

        val args = (DpiSettingsStore.DEFAULT_ARGS + listOf("--protect-path", protectPath))
            .toTypedArray()

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
            |misc:
            |  task-stack-size: 81920
            |socks5:
            |  mtu: 8500
            |  address: 127.0.0.1
            |  port: ${DpiSettingsStore.PROXY_PORT}
            |  udp: udp
        """.trimMargin("|")

        val configFile = File(cacheDir, "hev-socks5.yml").apply {
            writeText(config)
        }

        val builder = Builder()
            .setSession("YouTube Voice DPI")
            .setConfigureIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .addAddress("10.10.10.10", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // Only this app's traffic goes through the local DPI tunnel.
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
            val server = LocalSocket()
            try {
                server.bind(
                    LocalSocketAddress(
                        sockFile.absolutePath,
                        LocalSocketAddress.Namespace.FILESYSTEM
                    )
                )
                // listen/accept are public in AOSP but missing from some SDK stubs
                LocalSocket::class.java.getMethod("listen", Int::class.javaPrimitiveType)
                    .invoke(server, 50)
                ready.complete(Unit)
                val acceptMethod = LocalSocket::class.java.getMethod("accept")
                while (isActive && !stopping) {
                    val client = try {
                        acceptMethod.invoke(server) as LocalSocket
                    } catch (_: Exception) {
                        break
                    }
                    handleProtectClient(client)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "protect worker failed", t)
                if (!ready.isCompleted) ready.completeExceptionally(t)
            } finally {
                runCatching { server.close() }
                runCatching { sockFile.delete() }
            }
        }
    }

    private fun stopProtectWorker() {
        protectJob?.cancel()
        protectJob = null
        protectSocketPath?.let { runCatching { File(it).delete() } }
        protectSocketPath = null
    }

    private fun handleProtectClient(socket: LocalSocket) {
        socket.use { client ->
            try {
                // ByeDPI sends SCM_RIGHTS with the outbound FD; trigger ancillary read.
                val ignored = ByteArray(1)
                client.inputStream.read(ignored)
                val fds = client.ancillaryFileDescriptors
                val raw = fds?.firstOrNull()
                val success = if (raw != null) {
                    protect(getIntFd(raw))
                } else {
                    false
                }
                client.outputStream.write(if (success) 0 else 1)
            } catch (t: Throwable) {
                Log.w(TAG, "protect client failed", t)
            }
        }
    }

    private fun getIntFd(fd: FileDescriptor): Int {
        return try {
            val field: Field = FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            field.getInt(fd)
        } catch (_: Throwable) {
            // Fallback API 28+
            ParcelFileDescriptor.dup(fd).use { it.fd }
        }
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

        const val ACTION_START = "com.youtubevoice.app.dpi.START"
        const val ACTION_STOP = "com.youtubevoice.app.dpi.STOP"

        fun start(context: android.content.Context) {
            val intent = Intent(context, DpiVpnService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: android.content.Context) {
            val intent = Intent(context, DpiVpnService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}

package com.youtubevoice.app.dpi

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

/**
 * Optional in-app DPI module (independent from the phone system VPN).
 *
 * Preferred: local TUN (VpnService → hev → ByeDPI).
 * Fallback: ByeDPI SOCKS for OkHttp only.
 *
 * Never runs both — they share ByeDPI port 1080.
 * Starting TUN replaces any active system VPN.
 */
object DpiLauncher {
    private const val TAG = "DpiLauncher"

    @Volatile
    private var mode: Mode = Mode.None

    enum class Mode { None, Vpn, Socks }

    fun currentMode(): Mode = mode

    fun vpnPrepareIntent(context: Context): Intent? = VpnService.prepare(context)

    fun startPreferVpn(context: Context) {
        val app = context.applicationContext
        if (VpnService.prepare(app) == null) {
            startVpn(app)
        } else {
            Log.i(TAG, "VPN not prepared — SOCKS fallback")
            startSocks(app)
        }
    }

    fun startVpn(context: Context) {
        val app = context.applicationContext
        if (mode == Mode.Socks) {
            DpiProxyService.stop(app)
        }
        mode = Mode.Vpn
        Log.i(TAG, "Starting local TUN DpiVpnService")
        DpiVpnService.start(app)
    }

    fun startSocks(context: Context) {
        val app = context.applicationContext
        if (mode == Mode.Vpn) {
            DpiVpnService.stop(app)
        }
        mode = Mode.Socks
        Log.i(TAG, "Starting SOCKS DpiProxyService")
        DpiProxyService.start(app)
    }

    fun restart(context: Context) {
        val app = context.applicationContext
        when (mode) {
            Mode.Vpn -> {
                if (VpnService.prepare(app) == null) {
                    DpiVpnService.restart(app)
                } else {
                    startSocks(app)
                }
            }
            Mode.Socks -> DpiProxyService.restart(app)
            Mode.None -> startPreferVpn(app)
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        DpiVpnService.stop(app)
        DpiProxyService.stop(app)
        mode = Mode.None
        AppHttp.setDpiModuleOff()
    }
}

package com.youtubevoice.app.dpi

import android.net.VpnService

/**
 * Native Unix-socket server that receives ByeDPI SCM_RIGHTS FDs and calls [VpnService.protect].
 */
object DpiProtectNative {
    init {
        System.loadLibrary("byedpi")
    }

    @JvmStatic
    external fun nativeStart(service: VpnService, path: String): Boolean

    @JvmStatic
    external fun nativeStop()
}

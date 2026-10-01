package com.youtubevoice.app.dpi

/**
 * JNI bridge to hev-socks5-tunnel (RegisterNatives via PKGNAME/CLSNAME).
 */
object TProxyNative {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    @JvmStatic
    external fun TProxyStopService(): Boolean

    @JvmStatic
    external fun TProxyIsRunning(): Boolean

    @JvmStatic
    external fun TProxyGetStats(): LongArray
}

package com.youtubevoice.app.dpi

import android.util.Log
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared OkHttp clients. When DPI SOCKS is up, all app HTTP goes through ByeDPI on localhost.
 */
object AppHttp {
    private const val TAG = "AppHttp"
    private val dpiProxyOn = AtomicBoolean(false)

    @Volatile
    private var baseClient: OkHttpClient = buildClient(proxy = false)

    fun isDpiProxyEnabled(): Boolean = dpiProxyOn.get()

    fun client(): OkHttpClient = baseClient

    @Synchronized
    fun setDpiProxyEnabled(enabled: Boolean) {
        if (dpiProxyOn.get() == enabled && baseClientHasProxy(enabled)) {
            return
        }
        dpiProxyOn.set(enabled)
        baseClient = buildClient(proxy = enabled)
        Log.i(TAG, "OkHttp DPI SOCKS proxy ${if (enabled) "ON" else "OFF"}")
    }

    private fun baseClientHasProxy(enabled: Boolean): Boolean {
        val proxy = baseClient.proxy
        val hasProxy = proxy != null && proxy.type() == Proxy.Type.SOCKS
        return hasProxy == enabled
    }

    private fun buildClient(proxy: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        if (proxy) {
            builder.proxy(
                Proxy(
                    Proxy.Type.SOCKS,
                    InetSocketAddress("127.0.0.1", DpiSettingsStore.PROXY_PORT)
                )
            )
        }
        return builder.build()
    }
}

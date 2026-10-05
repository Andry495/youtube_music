package com.youtubevoice.app.dpi

import android.util.Log
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared OkHttp clients.
 *
 * In-app DPI is an **optional** module:
 * - OFF: plain system network (phone VPN / Wi‑Fi) — no SOCKS, no host rewrites.
 * - SOCKS: OkHttp → ByeDPI :1080 + TSPU workarounds.
 * - TUN: packets via VpnService; OkHttp stays without SOCKS, workarounds optional.
 */
object AppHttp {
    private const val TAG = "AppHttp"

    private val dpiProxyOn = AtomicBoolean(false)
    private val dpiBypassOn = AtomicBoolean(false)

    fun rewriteHost(host: String): String {
        if (!dpiBypassOn.get()) return host
        return when (host) {
            "www.youtube.com", "m.youtube.com" -> "youtube.com"
            else -> host
        }
    }

    fun rewriteUrl(url: HttpUrl): HttpUrl {
        if (!dpiBypassOn.get()) return url
        val host = url.host
        return when {
            host == "www.youtube.com" && url.encodedPath.startsWith("/youtubei/") ->
                url.newBuilder().host("youtubei.googleapis.com").build()
            host == "www.youtube.com" || host == "m.youtube.com" ->
                url.newBuilder().host("youtube.com").build()
            else -> url
        }
    }

    private val youtubeDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            Dns.SYSTEM.lookup(rewriteHost(hostname))
    }

    private val socksPassthroughDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val host = rewriteHost(hostname)
            return listOf(InetAddress.getByAddress(host, byteArrayOf(0, 0, 0, 0)))
        }
    }

    @Volatile
    private var baseClient: OkHttpClient = buildClient(proxy = false, bypass = false)

    fun isDpiProxyEnabled(): Boolean = dpiProxyOn.get()

    fun isDpiBypassEnabled(): Boolean = dpiBypassOn.get()

    fun client(): OkHttpClient = baseClient

    /** Full teardown when the DPI module is switched off. */
    @Synchronized
    fun setDpiModuleOff() {
        applyMode(socks = false, bypass = false)
    }

    /**
     * TUN mode: no OkHttp SOCKS (traffic hits system TUN → hev → ByeDPI).
     * Keep light bypass=false so phone-VPN-compatible stack isn't mutated; TUN handles packets.
     */
    @Synchronized
    fun setDpiTunMode() {
        applyMode(socks = false, bypass = false)
    }

    /** SOCKS fallback: OkHttp through ByeDPI. */
    @Synchronized
    fun setDpiSocksMode() {
        applyMode(socks = true, bypass = true)
    }

    /** @deprecated Prefer [setDpiModuleOff] / [setDpiTunMode] / [setDpiSocksMode]. */
    @Synchronized
    fun setDpiProxyEnabled(enabled: Boolean) {
        if (enabled) setDpiSocksMode() else setDpiModuleOff()
    }

    private fun applyMode(socks: Boolean, bypass: Boolean) {
        if (dpiProxyOn.get() == socks &&
            dpiBypassOn.get() == bypass &&
            baseClientMatches(socks, bypass)
        ) {
            return
        }
        dpiProxyOn.set(socks)
        dpiBypassOn.set(bypass)
        baseClient = buildClient(proxy = socks, bypass = bypass)
        Log.i(TAG, "OkHttp mode socks=$socks bypass=$bypass")
    }

    private fun baseClientMatches(socks: Boolean, bypass: Boolean): Boolean {
        val proxy = baseClient.proxy
        val hasProxy = proxy != null && proxy.type() == Proxy.Type.SOCKS
        val http11Only = baseClient.protocols == listOf(Protocol.HTTP_1_1)
        return hasProxy == socks && (http11Only == bypass || (!bypass && !http11Only))
    }

    private fun buildClient(proxy: Boolean, bypass: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .dns(Dns.SYSTEM)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (bypass) {
            builder.protocols(listOf(Protocol.HTTP_1_1))
            builder.dns(if (proxy) socksPassthroughDns else youtubeDns)
            builder.addInterceptor { chain ->
                val req = chain.request()
                val rewritten = rewriteUrl(req.url)
                if (rewritten == req.url) chain.proceed(req)
                else {
                    Log.d(TAG, "rewrite ${req.url.host} -> ${rewritten.host}")
                    chain.proceed(req.newBuilder().url(rewritten).build())
                }
            }
            builder.addNetworkInterceptor { chain ->
                val req = chain.request()
                val rewritten = rewriteUrl(req.url)
                if (rewritten == req.url) chain.proceed(req)
                else {
                    Log.d(TAG, "net-rewrite ${req.url.host} -> ${rewritten.host}")
                    chain.proceed(req.newBuilder().url(rewritten).build())
                }
            }
        }

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

package com.youtubevoice.app.dpi

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Protocol
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * On-device reachability checks through the current [AppHttp] client (SOCKS when DPI is on).
 */
object DpiProbe {
    private const val TAG = "DpiProbe"

    data class Result(
        val ok: Boolean,
        val label: String,
        val detail: String,
        val elapsedMs: Long,
    )

    suspend fun probeYouTube(timeoutSec: Long = 10L): Result = withContext(Dispatchers.IO) {
        // Must reach API + media hosts; ytimg alone is not enough for playback.
        val required = listOf(
            "https://youtubei.googleapis.com/" to "youtubei.googleapis.com",
            "https://manifest.googlevideo.com/" to "manifest.googlevideo.com",
        )
        val client = AppHttp.client().newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            // TSPU often resets HTTP/2 on googleapis; probe over h1 first.
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .callTimeout(timeoutSec + 2, TimeUnit.SECONDS)
            .build()

        fun tryUrl(url: String, label: String): Result? {
            val started = System.currentTimeMillis()
            return try {
                val response = client.newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", "YoutubeVoice-DpiProbe/1")
                        .get()
                        .build()
                ).execute()
                response.use {
                    val ms = System.currentTimeMillis() - started
                    val code = it.code
                    if (code in 200..499) {
                        Log.i(TAG, "OK $label HTTP $code in ${ms}ms")
                        Result(true, label, "HTTP $code", ms)
                    } else {
                        Log.w(TAG, "Fail $label HTTP $code (${ms}ms)")
                        null
                    }
                }
            } catch (t: Throwable) {
                val ms = System.currentTimeMillis() - started
                Log.w(TAG, "Fail $label (${ms}ms)", t)
                null
            }
        }

        val okRequired = mutableListOf<Result>()
        val failures = mutableListOf<String>()
        for ((url, label) in required) {
            val hit = tryUrl(url, label)
            if (hit != null) {
                okRequired += hit
            } else {
                failures += label
            }
        }
        if (okRequired.size == required.size) {
            val best = okRequired.maxBy { it.elapsedMs }
            return@withContext Result(
                true,
                okRequired.joinToString("+") { it.label },
                okRequired.joinToString(", ") { "${it.label} ${it.detail}" },
                best.elapsedMs,
            )
        }
        // Don't burn time on optional hosts during auto-tune failures.
        val okPart = okRequired.joinToString(", ") { it.label }
        val detail = buildString {
            append("Нет доступа: ${failures.joinToString(", ").ifBlank { "API/медиа" }}")
            if (okPart.isNotBlank()) append(" (ок: $okPart)")
        }
        Result(false, "youtube-api", detail, 0L)
    }
}

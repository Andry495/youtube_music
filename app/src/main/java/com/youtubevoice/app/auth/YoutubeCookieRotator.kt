package com.youtubevoice.app.auth

import android.util.Log
import com.youtubevoice.app.dpi.AppHttp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps Google session freshness cookies (`__Secure-1PSIDTS` / `__Secure-3PSIDTS`) alive.
 *
 * Without rotation they expire within ~10 minutes and InnerTube treats the session as anonymous.
 * Chrome uses `POST accounts.google.com/RotateCookies` for the same purpose.
 */
object YoutubeCookieRotator {
    private const val TAG = "YoutubeCookieRotator"
    private const val ROTATE_URL = "https://accounts.google.com/RotateCookies"
    private const val MIN_INTERVAL_MS = 60_000L
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val rotateBody = """[000,"-0000000000000000000"]""".toRequestBody(jsonMedia)
    private val lastAttemptMs = AtomicLong(0L)

    private val rotatableNames = setOf(
        "__Secure-1PSIDTS",
        "__Secure-3PSIDTS"
    )

    /**
     * @return updated cookie string when rotation changed SIDTS values; otherwise original cookie.
     */
    fun rotateIfNeeded(cookie: String): String {
        val cleaned = cookie.trim()
        if (cleaned.isBlank()) return cleaned
        if (!hasSapisid(cleaned)) return cleaned

        val now = System.currentTimeMillis()
        val last = lastAttemptMs.get()
        if (now - last < MIN_INTERVAL_MS) return cleaned
        if (!lastAttemptMs.compareAndSet(last, now)) return cleaned

        return runCatching { rotateOnce(cleaned) }.getOrElse { error ->
            Log.w(TAG, "RotateCookies failed", error)
            cleaned
        }
    }

    private fun rotateOnce(cookie: String): String {
        val request = Request.Builder()
            .url(ROTATE_URL)
            .header("Cookie", cookie)
            .header("Content-Type", "application/json")
            .header("Origin", "https://accounts.google.com")
            .header("Referer", "https://accounts.google.com/")
            .post(rotateBody)
            .build()

        AppHttp.client().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "RotateCookies HTTP ${response.code}")
                return cookie
            }
            val setCookies = response.headers("Set-Cookie")
            if (setCookies.isEmpty()) return cookie

            val updates = LinkedHashMap<String, String>()
            for (header in setCookies) {
                val pair = header.substringBefore(';').trim()
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val name = pair.substring(0, eq)
                val value = pair.substring(eq + 1)
                if (name in rotatableNames && value.isNotBlank()) {
                    updates[name] = value
                }
            }
            if (updates.isEmpty()) return cookie

            val merged = parseCookieMap(cookie).toMutableMap()
            var changed = false
            for ((name, value) in updates) {
                if (merged[name] != value) {
                    merged[name] = value
                    changed = true
                }
            }
            if (!changed) return cookie
            Log.i(TAG, "Rotated SIDTS cookies: ${updates.keys.joinToString()}")
            return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
    }

    private fun hasSapisid(cookie: String): Boolean =
        cookie.contains("SAPISID=") || cookie.contains("__Secure-3PAPISID=")

    private fun parseCookieMap(cookie: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        cookie.split(';').forEach { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                map[trimmed.substring(0, eq)] = trimmed.substring(eq + 1)
            }
        }
        return map
    }
}

package com.youtubevoice.app.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle

/**
 * Google / YouTube cookie login in WebView (no Cloud OAuth client).
 *
 * Stay inside WebView for the whole flow — opening Google/YouTube via
 * `intent://` sends confirmation to another app and our activity finishes
 * in the background, so Android returns to the launcher instead of MainActivity.
 */
class YoutubeLoginActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var finished = false
    private var pendingCookie: String? = null
    private var pollAttempts = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val accountEmail: String? by lazy {
        intent.getStringExtra(EXTRA_EMAIL)?.takeIf { it.isNotBlank() }
    }

    private val pollCookies = object : Runnable {
        override fun run() {
            if (finished || isFinishing) return
            if (tryCaptureCookies()) return
            pollAttempts++
            if (pollAttempts < MAX_POLL_ATTEMPTS) {
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    Log.d(TAG, "onPageStarted $url")
                    maybeScheduleCookiePoll(url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    CookieManager.getInstance().flush()
                    Log.d(TAG, "onPageFinished $url")
                    maybeScheduleCookiePoll(url)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase().orEmpty()
                    if (scheme == "http" || scheme == "https") {
                        maybeScheduleCookiePoll(uri.toString())
                        // Keep https inside WebView (do not hand off to YT Music / Chrome).
                        return false
                    }
                    if (scheme == "intent") {
                        // Never startActivity(intent://) during login — that minimizes us.
                        val fallback = fallbackUrlFromIntent(uri.toString())
                        Log.i(TAG, "Blocked external intent, fallback=$fallback")
                        if (!fallback.isNullOrBlank() && view != null) {
                            view.loadUrl(fallback)
                        }
                        return true
                    }
                    Log.i(TAG, "Ignored non-http scheme: $uri")
                    return true
                }
            }
        }

        val root = FrameLayout(this).apply {
            addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) webView.goBack() else {
                        setResult(RESULT_CANCELED)
                        finish()
                    }
                }
            }
        )

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.removeAllCookies {
            cookieManager.flush()
            if (!isFinishing) {
                webView.loadUrl(buildLoginUrl(accountEmail))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Finish only while foreground — otherwise Android shows the launcher.
        pendingCookie?.let { completeLogin(it) }
            ?: run {
                CookieManager.getInstance().flush()
                tryCaptureCookies()
            }
    }

    private fun maybeScheduleCookiePoll(url: String?) {
        if (finished || url.isNullOrBlank()) return
        if (!isYoutubeHost(url) || url.contains("accounts.google.com", ignoreCase = true)) return
        pollAttempts = 0
        mainHandler.removeCallbacks(pollCookies)
        mainHandler.postDelayed(pollCookies, POLL_INTERVAL_MS)
    }

    private fun tryCaptureCookies(): Boolean {
        if (finished) return true
        val cookie = collectYoutubeCookies()
        if (!isLoggedInCookie(cookie)) return false
        // Give Google a moment to attach SID / LOGIN_INFO after SAPISID appears.
        // Finishing too early → InnerTube "Bad Request" with a half-baked session.
        finished = true
        mainHandler.removeCallbacks(pollCookies)
        mainHandler.postDelayed({
            val settled = normalizeSessionCookie(collectYoutubeCookies())
            if (!isLoggedInCookie(settled)) {
                finished = false
                Log.w(TAG, "Cookies disappeared during settle — keep waiting")
                return@postDelayed
            }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                completeLogin(settled)
            } else {
                Log.i(TAG, "Cookies ready but paused — defer finish until onResume")
                pendingCookie = settled
            }
        }, SETTLE_MS)
        return true
    }

    private fun completeLogin(cookie: String) {
        if (isFinishing) return
        finished = true
        pendingCookie = null
        mainHandler.removeCallbacks(pollCookies)
        val normalized = normalizeSessionCookie(cookie)
        Log.i(
            TAG,
            "Login OK, cookieChars=${normalized.length} " +
                "hasSapi=${normalized.contains("SAPISID=")} " +
                "has3p=${normalized.contains("__Secure-3PAPISID=")} " +
                "hasSid=${normalized.contains("SID=") || normalized.contains("__Secure-3PSID=")}"
        )
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_COOKIE, normalized)
                .putExtra(EXTRA_EMAIL, accountEmail)
        )
        finish()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(pollCookies)
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_EMAIL = "email"
        const val EXTRA_COOKIE = "cookie"
        private const val TAG = "YoutubeLogin"

        private const val POLL_INTERVAL_MS = 400L
        private const val MAX_POLL_ATTEMPTS = 40
        private const val SETTLE_MS = 1_200L

        private val COOKIE_URLS = listOf(
            "https://www.youtube.com",
            "https://youtube.com",
            "https://music.youtube.com",
            "https://m.youtube.com"
        )

        fun createIntent(context: Context, email: String?): Intent =
            Intent(context, YoutubeLoginActivity::class.java)
                .putExtra(EXTRA_EMAIL, email)

        fun buildLoginUrl(email: String?): String {
            // Prefer www.youtube.com — music.youtube.com often deep-links into YT Music app.
            val continueUrl = "https://www.youtube.com/"
            return if (!email.isNullOrBlank()) {
                "https://accounts.google.com/AccountChooser?" +
                    "Email=${Uri.encode(email)}" +
                    "&continue=${Uri.encode(continueUrl)}" +
                    "&hl=ru"
            } else {
                "https://accounts.google.com/ServiceLogin?" +
                    "service=youtube" +
                    "&continue=${Uri.encode(continueUrl)}" +
                    "&hl=ru"
            }
        }

        fun isYoutubeHost(url: String): Boolean {
            val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull().orEmpty()
            return host == "youtube.com" ||
                host == "www.youtube.com" ||
                host == "m.youtube.com" ||
                host == "music.youtube.com"
        }

        fun collectYoutubeCookies(): String {
            val cm = CookieManager.getInstance()
            val merged = LinkedHashMap<String, String>()
            for (url in COOKIE_URLS) {
                val raw = cm.getCookie(url).orEmpty()
                if (raw.isBlank()) continue
                raw.split(';').forEach { part ->
                    val trimmed = part.trim()
                    val eq = trimmed.indexOf('=')
                    if (eq > 0) {
                        val name = trimmed.substring(0, eq)
                        val value = trimmed.substring(eq + 1)
                        if (name.isNotBlank() && value.isNotBlank()) {
                            merged[name] = value
                        }
                    }
                }
            }
            return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        fun isLoggedInCookie(cookie: String): Boolean =
            YoutubeSessionCookies.isComplete(cookie)

        /** Ensure plain SAPISID exists — InnerTube SAPISIDHASH expects it. */
        fun normalizeSessionCookie(cookie: String): String =
            YoutubeSessionCookies.normalize(cookie)

        /** Extract https fallback from intent://…;S.browser_fallback_url=…;end */
        fun fallbackUrlFromIntent(intentUri: String): String? {
            runCatching {
                val intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME)
                intent.getStringExtra("browser_fallback_url")
                    ?.takeIf { it.startsWith("http") }
                    ?.let { return it }
                intent.data?.takeIf {
                    it.scheme == "http" || it.scheme == "https"
                }?.toString()?.let { return it }
            }
            // Manual parse — some WebViews put extras only in the opaque string.
            val marker = "S.browser_fallback_url="
            val idx = intentUri.indexOf(marker)
            if (idx >= 0) {
                val raw = intentUri.substring(idx + marker.length)
                    .substringBefore(';')
                    .substringBefore('#')
                val decoded = Uri.decode(raw)
                if (decoded.startsWith("http")) return decoded
            }
            return null
        }
    }
}

package com.youtubevoice.app.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
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

/**
 * Device Google login for YouTube. Prefer the account chosen in the system picker;
 * Google SSO on the phone usually confirms with one tap.
 */
class YoutubeLoginActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var finished = false
    private val accountEmail: String? by lazy {
        intent.getStringExtra(EXTRA_EMAIL)?.takeIf { it.isNotBlank() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.removeAllCookies(null)
        cookieManager.flush()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.userAgentString = CHROME_MOBILE_UA
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    maybeFinishIfLoggedIn(url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    CookieManager.getInstance().flush()
                    maybeFinishIfLoggedIn(url)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    maybeFinishIfLoggedIn(request?.url?.toString())
                    return false
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

        webView.loadUrl(buildLoginUrl(accountEmail))
    }

    private fun maybeFinishIfLoggedIn(url: String?) {
        if (finished || url.isNullOrBlank()) return
        if (!url.contains("youtube.com") || url.contains("accounts.google.com")) return

        val cookie = CookieManager.getInstance().getCookie("https://www.youtube.com").orEmpty()
        if (!isLoggedInCookie(cookie)) return

        finished = true
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_COOKIE, cookie)
                .putExtra(EXTRA_EMAIL, accountEmail)
        )
        finish()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_EMAIL = "email"
        const val EXTRA_COOKIE = "cookie"

        private const val CHROME_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

        fun createIntent(context: Context, email: String?): Intent =
            Intent(context, YoutubeLoginActivity::class.java)
                .putExtra(EXTRA_EMAIL, email)

        fun buildLoginUrl(email: String?): String {
            val continueUrl = "https://www.youtube.com/"
            return if (!email.isNullOrBlank()) {
                "https://accounts.google.com/AccountChooser?" +
                    "Email=${android.net.Uri.encode(email)}" +
                    "&continue=${android.net.Uri.encode(continueUrl)}" +
                    "&hl=ru"
            } else {
                "https://accounts.google.com/ServiceLogin?" +
                    "service=youtube" +
                    "&continue=${android.net.Uri.encode(continueUrl)}" +
                    "&hl=ru"
            }
        }

        fun isLoggedInCookie(cookie: String): Boolean {
            if (cookie.isBlank()) return false
            val hasSapi = cookie.contains("SAPISID=") || cookie.contains("__Secure-3PAPISID=")
            val hasSid = cookie.contains("SID=") ||
                cookie.contains("__Secure-3PSID=") ||
                cookie.contains("LOGIN_INFO=")
            return hasSapi && hasSid
        }
    }
}

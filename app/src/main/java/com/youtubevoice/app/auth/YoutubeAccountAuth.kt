package com.youtubevoice.app.auth

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

private val Context.youtubeSessionStore by preferencesDataStore("youtube_session")

/**
 * System Google account picker + cookie session for YouTube (InnerTube).
 * End users only pick an account — no Google Cloud OAuth setup.
 */
class YoutubeAccountAuth(private val appContext: Context) {
    private val sessionRef = AtomicReference<YoutubeSession?>(null)

    val session: YoutubeSession? get() = sessionRef.get()
    val isSignedIn: Boolean get() = sessionRef.get() != null

    init {
        runBlocking {
            restoreSession()
        }
    }

    fun listDeviceGoogleAccounts(): List<DeviceGoogleAccount> {
        return try {
            AccountManager.get(appContext)
                .getAccountsByType(GOOGLE_ACCOUNT_TYPE)
                .map { DeviceGoogleAccount(name = null, email = it.name) }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    /** System account chooser — user picks which Google account to use. */
    @Suppress("DEPRECATION")
    fun accountChooserIntent(): Intent =
        AccountManager.newChooseAccountIntent(
            null,
            null,
            arrayOf(GOOGLE_ACCOUNT_TYPE),
            null,
            null,
            null,
            null
        )

    fun emailFromChooserResult(data: Intent?): String? =
        data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.takeIf { it.isNotBlank() }

    fun loginIntent(email: String?): Intent =
        YoutubeLoginActivity.createIntent(appContext, email)

    suspend fun persistSession(email: String?, cookie: String): YoutubeSession {
        val cleaned = cookie.trim()
        require(cleaned.contains("SAPISID") || cleaned.contains("__Secure-3PAPISID")) {
            "Сессия YouTube неполная — повторите вход"
        }
        val session = YoutubeSession(email = email, cookie = cleaned)
        sessionRef.set(session)
        appContext.youtubeSessionStore.edit { prefs ->
            if (email.isNullOrBlank()) prefs.remove(KEY_EMAIL) else prefs[KEY_EMAIL] = email
            prefs[KEY_COOKIE] = cleaned
        }
        return session
    }

    suspend fun signOut() {
        sessionRef.set(null)
        appContext.youtubeSessionStore.edit { it.clear() }
        runCatching {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        }
    }

    private suspend fun restoreSession() {
        val prefs = appContext.youtubeSessionStore.data.first()
        val cookie = prefs[KEY_COOKIE]?.takeIf { it.isNotBlank() } ?: return
        val email = prefs[KEY_EMAIL]
        sessionRef.set(YoutubeSession(email = email, cookie = cookie))
    }

    companion object {
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
        private val KEY_EMAIL = stringPreferencesKey("email")
        private val KEY_COOKIE = stringPreferencesKey("cookie")
    }
}

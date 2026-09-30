package com.youtubevoice.app.auth

/**
 * Device YouTube session via Google account cookies (no Cloud OAuth client).
 */
data class YoutubeSession(
    val email: String?,
    val cookie: String
)

data class DeviceGoogleAccount(
    val name: String?,
    val email: String
)

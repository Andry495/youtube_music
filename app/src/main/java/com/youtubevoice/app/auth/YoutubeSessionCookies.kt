package com.youtubevoice.app.auth

/**
 * Helpers for YouTube / Google identity cookies used by InnerTube.
 */
object YoutubeSessionCookies {
    fun parse(cookie: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        cookie.split(';').forEach { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                val name = trimmed.substring(0, eq)
                val value = trimmed.substring(eq + 1)
                if (name.isNotBlank() && value.isNotBlank()) {
                    map[name] = value
                }
            }
        }
        return map
    }

    fun format(map: Map<String, String>): String =
        map.entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun names(cookie: String): String =
        parse(cookie).keys.sorted().joinToString(",")

    /**
     * Ensure plain `SAPISID` exists (InnerTube SAPISIDHASH). Google often only
     * sets `__Secure-3PAPISID` in WebView.
     */
    fun normalize(cookie: String): String {
        val map = parse(cookie)
        if (map.isEmpty()) return cookie.trim()
        val secure3 = map["__Secure-3PAPISID"]
        if (!map.containsKey("SAPISID") && !secure3.isNullOrBlank()) {
            map["SAPISID"] = secure3
        }
        val secure1 = map["__Secure-1PAPISID"]
        if (!map.containsKey("APISID") && !secure1.isNullOrBlank()) {
            map["APISID"] = secure1
        }
        return format(map)
    }

    fun hasSapisid(cookie: String): Boolean {
        val map = parse(cookie)
        return map.containsKey("SAPISID") || map.containsKey("__Secure-3PAPISID")
    }

    fun hasSessionId(cookie: String): Boolean {
        val map = parse(cookie)
        return map.containsKey("SID") ||
            map.containsKey("__Secure-3PSID") ||
            map.containsKey("__Secure-1PSID") ||
            map.containsKey("LOGIN_INFO")
    }

    /** Full enough to call authenticated InnerTube without HTTP 400. */
    fun isComplete(cookie: String): Boolean =
        cookie.isNotBlank() && hasSapisid(cookie) && hasSessionId(cookie)
}

package com.youtubevoice.app.youtube

import com.youtubevoice.app.dpi.AppHttp
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.atomic.AtomicReference

object NewPipeDownloader : Downloader() {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val cookieRef = AtomicReference<String?>(null)

    fun setAuthCookie(cookie: String?) {
        cookieRef.set(cookie?.takeIf { it.isNotBlank() })
    }

    fun cookieOrNull(): String? = cookieRef.get()

    override fun execute(request: Request): Response {
        val rawUrl = request.url()
        val httpUrl = rawUrl.toHttpUrlOrNull()
            ?: error("Bad URL: $rawUrl")
        val url = AppHttp.rewriteUrl(httpUrl)
        val builder = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Host", url.host)

        request.headers().forEach { (key, values) ->
            if (key.equals("Host", ignoreCase = true) ||
                key.equals("User-Agent", ignoreCase = true)
            ) {
                return@forEach
            }
            values.forEach { value -> builder.addHeader(key, value) }
        }

        cookieRef.get()?.let { cookie ->
            val hasCookie = request.headers().keys.any { it.equals("Cookie", ignoreCase = true) }
            if (!hasCookie) {
                builder.header("Cookie", cookie)
            }
        }

        val method = request.httpMethod().uppercase()
        val bodyBytes = request.dataToSend()
        when {
            method == "GET" -> builder.get()
            method == "HEAD" -> builder.head()
            bodyBytes != null -> builder.method(method, bodyBytes.toRequestBody(null))
            else -> builder.method(method, ByteArray(0).toRequestBody(null))
        }

        val response = AppHttp.client().newCall(builder.build()).execute()
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", request.url())
        }

        val headers = LinkedHashMap<String, List<String>>()
        response.headers.forEach { (name, value) ->
            headers[name] = (headers[name] ?: emptyList()) + value
        }

        val body = response.body?.string().orEmpty()
        val latestUrl = response.request.url.toString()
        return Response(response.code, response.message, headers, body, latestUrl)
    }
}

package com.youtubevoice.app.youtube

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object NewPipeDownloader : Downloader() {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val cookieRef = AtomicReference<String?>(null)

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    fun setAuthCookie(cookie: String?) {
        cookieRef.set(cookie?.takeIf { it.isNotBlank() })
    }

    fun cookieOrNull(): String? = cookieRef.get()

    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .header("User-Agent", USER_AGENT)

        request.headers().forEach { (key, values) ->
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

        val response = client.newCall(builder.build()).execute()
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

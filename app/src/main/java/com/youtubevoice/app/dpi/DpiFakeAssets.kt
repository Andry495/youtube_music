package com.youtubevoice.app.dpi

import android.content.Context
import java.io.File

/**
 * Extracts Zapret-style fake TLS ClientHello blobs for ByeDPI `--fake-data`.
 * ALT11 / Flowseal strategies rely on a 681-byte www.google.com ClientHello.
 */
object DpiFakeAssets {
    const val PLACEHOLDER_GOOGLE = "@FAKE_TLS_GOOGLE@"
    const val PLACEHOLDER_MAX_RU = "@FAKE_TLS_MAX_RU@"

    private const val ASSET_DIR = "dpi"
    private const val GOOGLE = "tls_clienthello_www_google_com.bin"
    private const val MAX_RU = "tls_clienthello_max_ru.bin"

    @Volatile
    private var googlePath: String? = null

    @Volatile
    private var maxRuPath: String? = null

    fun ensure(context: Context) {
        val dir = File(context.applicationContext.filesDir, "dpi_fake").apply { mkdirs() }
        googlePath = extract(context, dir, GOOGLE)
        maxRuPath = extract(context, dir, MAX_RU)
    }

    fun resolveArgs(args: List<String>): List<String> {
        val out = ArrayList<String>(args.size)
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a == "--fake-data" && i + 1 < args.size) {
                val path = resolvePath(args[i + 1])
                if (path != null) {
                    out += a
                    out += path
                }
                i += 2
                continue
            }
            out += a
            i++
        }
        return out
    }

    private fun resolvePath(token: String): String? = when (token) {
        PLACEHOLDER_GOOGLE -> googlePath
        PLACEHOLDER_MAX_RU -> maxRuPath
        else -> token.takeIf { File(it).isFile }
    }

    private fun extract(context: Context, dir: File, name: String): String? {
        val out = File(dir, name)
        return try {
            context.assets.open("$ASSET_DIR/$name").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            if (out.length() > 32L) out.absolutePath else null
        } catch (_: Throwable) {
            if (out.isFile && out.length() > 32L) out.absolutePath else null
        }
    }
}

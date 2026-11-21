package com.gonzalowebdev.mediadownloader.net

import android.content.Context
import com.gonzalowebdev.mediadownloader.domain.ResolveException
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Envoltorio de yt-dlp (youtubedl-android): extracción de metadatos y descarga de video/audio. */
object YtDlp {
    @Volatile private var ready = false

    @Synchronized
    fun ensure(ctx: Context) {
        if (ready) return
        try {
            YoutubeDL.getInstance().init(ctx.applicationContext)
            try { FFmpeg.getInstance().init(ctx.applicationContext) } catch (e: Exception) { /* sin ffmpeg */ }
            ready = true
        } catch (e: Throwable) {
            throw ResolveException("No se pudo iniciar yt-dlp: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    suspend fun json(ctx: Context, url: String, noPlaylist: Boolean, cookies: java.io.File? = null): JsonObject = withContext(Dispatchers.IO) {
        ensure(ctx)
        val req = YoutubeDLRequest(url)
        req.addOption("--dump-single-json")
        req.addOption("--no-warnings")
        req.addOption("--socket-timeout", "25")
        if (noPlaylist) req.addOption("--no-playlist")
        if (cookies != null) req.addOption("--cookies", cookies.absolutePath)
        val out = try {
            YoutubeDL.getInstance().execute(req).out
        } catch (e: Exception) {
            throw ResolveException(friendly(e.message))
        }
        val line = out.lines().lastOrNull { it.trim().startsWith("{") }
            ?: throw ResolveException("yt-dlp no devolvió datos para este enlace.")
        try { JsonParser.parseString(line).asJsonObject } catch (e: Exception) {
            throw ResolveException("yt-dlp devolvió datos ilegibles.")
        }
    }

    fun version(ctx: Context): String {
        ensure(ctx)
        return YoutubeDL.getInstance().version(ctx.applicationContext) ?: "desconocida"
    }

    /** Descarga la última versión estable de yt-dlp (bloqueante). */
    fun update(ctx: Context): String {
        ensure(ctx)
        val st = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
        return st?.toString() ?: "sin cambios"
    }

    fun friendly(raw: String?): String {
        val msg = raw.orEmpty()
        val err = (msg.lines().lastOrNull { it.contains("ERROR", ignoreCase = true) }?.trim() ?: msg.trim().take(240))
            .substringBefore("; please report").substringBefore(", filling out")
        return when {
            err.contains("comfortable for some audiences", true) || err.contains("Log in for access", true) ->
                "TikTok exige iniciar sesión para este video: Ajustes → Cuentas conectadas → TikTok."
            err.contains("no video in this post", true) || err.contains("No video formats", true) ->
                "yt-dlp no encontró un video en este enlace (puede ser una publicación de imágenes)."
            err.contains("login required", true) || err.contains("rate-limit", true) || err.contains("not available", true) && err.contains("Instagram", true) ->
                "Instagram pide iniciar sesión (o limitó las consultas) para este contenido; sin cuenta no se puede descargar. ($err)"
            err.contains("Private", true) -> "El contenido es privado. ($err)"
            err.contains("Unable to download", true) || err.contains("urlopen", true) -> "No se pudo conectar. Revisa tu conexión. ($err)"
            err.isBlank() -> "yt-dlp falló sin dar detalles."
            else -> err.take(300)
        }
    }
}

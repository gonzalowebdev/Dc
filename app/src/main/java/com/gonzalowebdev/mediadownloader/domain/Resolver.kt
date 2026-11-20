package com.gonzalowebdev.mediadownloader.domain

import android.content.Context
import com.gonzalowebdev.mediadownloader.net.Sessions

class Resolver(private val ctx: Context, private val pinterest: PinterestResolver) {

    suspend fun resolve(link: ParsedLink, progress: (String) -> Unit = {}): Resolved = when (link.platform) {
        Platform.PINTEREST ->
            if (link.type == LinkType.PIN) firstSuccess(
                null,
                { pinterest.resolve(link, progress) },
                { progress("Probando con yt-dlp…"); YtDlpResolver.resolve(ctx, link) }
            ) else pinterest.resolve(link, progress)

        Platform.INSTAGRAM -> {
            val loggedIn = Sessions.has(Platform.INSTAGRAM)
            val hint = if (loggedIn) null else "Instagram casi siempre exige sesión: inicia sesión en Ajustes → Cuentas conectadas → Instagram."
            when {
                link.type == LinkType.STORY && !loggedIn ->
                    throw ResolveException("Las Stories requieren iniciar sesión: Ajustes → Cuentas conectadas → Instagram.")
                link.type == LinkType.STORY -> InstagramApi.resolve(link)
                loggedIn -> firstSuccess(
                    null,
                    { progress("Consultando Instagram…"); InstagramApi.resolve(link) },
                    { progress("Probando con yt-dlp…"); YtDlpResolver.resolve(ctx, link) }
                )
                else -> firstSuccess(
                    hint,
                    { progress("Consultando con yt-dlp…"); YtDlpResolver.resolve(ctx, link) },
                    { progress("Probando lectura directa…"); InstagramResolver.resolve(link) }
                )
            }
        }

        Platform.TIKTOK ->
            if (link.type == LinkType.PHOTO_POST) firstSuccess(
                null,
                { TikTokResolver.resolve(link) },
                { YtDlpResolver.resolve(ctx, link) }
            ) else firstSuccess(
                null,
                { progress("Consultando con yt-dlp…"); YtDlpResolver.resolve(ctx, link) },
                { progress("Probando lectura directa…"); TikTokResolver.resolve(link) }
            )
    }

    private suspend fun firstSuccess(hint: String?, vararg steps: suspend () -> Resolved): Resolved {
        val errors = mutableListOf<String>()
        for (s in steps) {
            try {
                return s()
            } catch (e: ResolveException) {
                e.message?.let { if (it !in errors) errors += it }
            }
        }
        throw ResolveException((errors + listOfNotNull(hint)).joinToString("\n\n"))
    }

    /** Resuelve URLs "lazy:" (por ejemplo, el mp4 de un Pin de video) justo antes de descargar. */
    suspend fun expandLazy(url: String): List<String> = when {
        url.startsWith("lazy:pinvideo:") -> PinterestScraper.videoUrls(url.removePrefix("lazy:pinvideo:"))
        else -> listOf(url)
    }
}

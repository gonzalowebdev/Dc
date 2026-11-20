package com.gonzalowebdev.mediadownloader.domain

import android.content.Context

class Resolver(private val ctx: Context, private val pinterest: PinterestResolver) {

    suspend fun resolve(link: ParsedLink, progress: (String) -> Unit = {}): Resolved = when (link.platform) {
        Platform.PINTEREST -> {
            if (link.type == LinkType.PIN) withFallback(
                { pinterest.resolve(link, progress) },
                { progress("Probando con yt-dlp…"); YtDlpResolver.resolve(ctx, link) }
            ) else pinterest.resolve(link, progress)
        }
        Platform.INSTAGRAM -> {
            if (link.type == LinkType.STORY) InstagramResolver.resolve(link) // lanza el aviso de que no está soportado
            else withFallback(
                { progress("Consultando con yt-dlp…"); YtDlpResolver.resolve(ctx, link) },
                { progress("Probando lectura directa…"); InstagramResolver.resolve(link) }
            )
        }
        Platform.TIKTOK -> {
            if (link.type == LinkType.PHOTO_POST) withFallback(
                { TikTokResolver.resolve(link) },
                { YtDlpResolver.resolve(ctx, link) }
            ) else withFallback(
                { progress("Consultando con yt-dlp…"); YtDlpResolver.resolve(ctx, link) },
                { progress("Probando lectura directa…"); TikTokResolver.resolve(link) }
            )
        }
    }

    private suspend fun withFallback(primary: suspend () -> Resolved, secondary: suspend () -> Resolved): Resolved {
        try {
            return primary()
        } catch (e: ResolveException) {
            try {
                return secondary()
            } catch (e2: ResolveException) {
                throw ResolveException("${e.message}\n\nSegundo intento: ${e2.message}")
            }
        }
    }

    /** Resuelve URLs "lazy:" (por ejemplo, el mp4 de un Pin de video) justo antes de descargar. */
    suspend fun expandLazy(url: String): List<String> = when {
        url.startsWith("lazy:pinvideo:") -> PinterestScraper.videoUrls(url.removePrefix("lazy:pinvideo:"))
        else -> listOf(url)
    }
}

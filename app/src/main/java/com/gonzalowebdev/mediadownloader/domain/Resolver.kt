package com.gonzalowebdev.mediadownloader.domain

class Resolver(private val pinterest: PinterestResolver) {

    suspend fun resolve(link: ParsedLink, progress: (String) -> Unit = {}): Resolved = when (link.platform) {
        Platform.PINTEREST -> pinterest.resolve(link, progress)
        Platform.INSTAGRAM -> InstagramResolver.resolve(link)
        Platform.TIKTOK -> TikTokResolver.resolve(link)
    }

    /** Resuelve URLs "lazy:" (por ejemplo, el mp4 de un Pin de video) justo antes de descargar. */
    suspend fun expandLazy(url: String): List<String> = when {
        url.startsWith("lazy:pinvideo:") -> PinterestScraper.videoUrls(url.removePrefix("lazy:pinvideo:"))
        else -> listOf(url)
    }
}

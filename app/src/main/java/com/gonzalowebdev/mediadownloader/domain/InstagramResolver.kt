package com.gonzalowebdev.mediadownloader.domain

import com.gonzalowebdev.mediadownloader.net.Net

/**
 * Instagram no ofrece API oficial para descargar contenido ajeno. Se intenta leer la página pública
 * (embed) de publicaciones y reels públicos. Sin sesión, muchas publicaciones no son accesibles.
 */
object InstagramResolver {

    suspend fun resolve(link: ParsedLink): Resolved {
        if (link.type == LinkType.STORY) {
            throw ResolveException(
                "Las Stories de Instagram requieren iniciar sesión y Instagram no ofrece OAuth para descargarlas. " +
                    "Esta función no está disponible (ver README → Limitaciones)."
            )
        }
        val code = link.ids.firstOrNull() ?: throw ResolveException("No pude identificar la publicación de Instagram.")
        val pages = listOf(
            "https://www.instagram.com/p/$code/embed/captioned/",
            "https://www.instagram.com/p/$code/"
        )
        var entries: List<MediaEntry> = emptyList()
        var title = "Instagram $code"
        for (u in pages) {
            val html = try { Net.getString(u) } catch (e: Exception) { continue }
            entries = parse(code, html, link.type)
            if (entries.isNotEmpty()) {
                Regex("<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                    ?.let { title = unescape(it).take(80) }
                break
            }
        }
        if (entries.isEmpty()) {
            throw ResolveException(
                "No pude obtener el contenido. Instagram exige iniciar sesión para casi todo y no tiene API pública de descarga: " +
                    "solo funcionan algunas publicaciones públicas."
            )
        }
        val preview = entries.firstOrNull { it.kind == MediaKind.IMAGE }?.urls?.firstOrNull()
        return Resolved(Platform.INSTAGRAM, link.type, link.url, title, entries, preview)
    }

    private fun unescape(s: String) = s
        .replace("\\\\u0026", "&").replace("\\u0026", "&")
        .replace("\\\\/", "/").replace("\\/", "/")
        .replace("\\\"", "\"").replace("&amp;", "&")

    private fun parse(code: String, html: String, type: LinkType): List<MediaEntry> {
        val t = unescape(html)
        val videos = (
            Regex("\"video_url\"\\s*:\\s*\"(https:[^\"]+)\"").findAll(t).map { it.groupValues[1] } +
                Regex("<video[^>]+src=\"([^\"]+)\"").findAll(t).map { it.groupValues[1] } +
                Regex("property=\"og:video\"[^>]+content=\"([^\"]+)\"").findAll(t).map { it.groupValues[1] }
            ).map { it.replace("&amp;", "&") }.distinct().toList()
        var images = (
            Regex("\"display_url\"\\s*:\\s*\"(https:[^\"]+)\"").findAll(t).map { it.groupValues[1] } +
                Regex("class=\"EmbeddedMediaImage\"[^>]+src=\"([^\"]+)\"").findAll(t).map { it.groupValues[1] }
            ).map { it.replace("&amp;", "&") }.distinct().toList()
        if (images.isEmpty() && videos.isEmpty()) {
            images = Regex("property=\"og:image\"[^>]+content=\"([^\"]+)\"").findAll(t).map { it.groupValues[1] }.distinct().toList()
        }
        val out = ArrayList<MediaEntry>()
        videos.forEach { out += entry(code, it, MediaKind.VIDEO) }
        val onlyVideos = videos.isNotEmpty() && (type == LinkType.REEL || images.size <= videos.size)
        if (!onlyVideos) images.forEach { out += entry(code, it, MediaKind.IMAGE) }
        return out.distinctBy { it.key }
    }

    private fun entry(code: String, url: String, kind: MediaKind): MediaEntry {
        val fallback = if (kind == MediaKind.VIDEO) "instagram_$code.mp4" else "instagram_$code.jpg"
        val name = fileNameFromUrl(url, fallback)
        return MediaEntry("$code#$name", name, listOf(url), kind, if (kind == MediaKind.IMAGE) url else null)
    }
}

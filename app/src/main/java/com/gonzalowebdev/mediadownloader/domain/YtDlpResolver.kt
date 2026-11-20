package com.gonzalowebdev.mediadownloader.domain

import android.content.Context
import com.gonzalowebdev.mediadownloader.net.YtDlp

/** Resuelve enlaces con yt-dlp. Los videos se descargan luego con yt-dlp ("ytdlp:<índice>:<url>"). */
object YtDlpResolver {
    private val imageExt = setOf("jpg", "jpeg", "png", "webp")

    suspend fun resolve(ctx: Context, link: ParsedLink): Resolved {
        val isPlaylistPlatform = link.platform == Platform.INSTAGRAM
        val root = YtDlp.json(ctx, link.url.substringBefore('#'), noPlaylist = !isPlaylistPlatform)
        val items: List<com.google.gson.JsonObject> =
            if (root.str("_type") == "playlist")
                root.arr("entries")?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject } ?: emptyList()
            else listOf(root)
        if (items.isEmpty()) throw ResolveException("yt-dlp no encontró contenido en ese enlace.")

        val baseId = root.str("id") ?: link.ids.firstOrNull() ?: "item"
        val prefix = when (link.platform) {
            Platform.INSTAGRAM -> "instagram"
            Platform.TIKTOK -> "tiktok"
            Platform.PINTEREST -> "pinterest"
        }
        val multi = items.size > 1
        val entries = items.mapIndexedNotNull { i, e ->
            val ext = (e.str("ext") ?: "mp4").lowercase()
            val n = if (multi) "_${i + 1}" else ""
            val key = if (multi) "$baseId#${i + 1}" else baseId
            if (ext in imageExt) {
                val u = e.str("url") ?: return@mapIndexedNotNull null
                MediaEntry(key, "${prefix}_$baseId$n.$ext", listOf(u), MediaKind.IMAGE, u)
            } else {
                MediaEntry(
                    key, "${prefix}_$baseId$n.mp4",
                    listOf("ytdlp:${if (multi) i + 1 else 0}:${link.url}"),
                    MediaKind.VIDEO, e.str("thumbnail")
                )
            }
        }
        if (entries.isEmpty()) throw ResolveException("No encontré archivos descargables en ese enlace.")
        val title = root.str("title")?.takeIf { it.isNotBlank() }
            ?: root.str("description")?.take(70)?.takeIf { it.isNotBlank() }
            ?: "${link.platform.label} $baseId"
        val preview = root.str("thumbnail") ?: entries.firstNotNullOfOrNull { it.previewUrl }
        val type = if (multi) LinkType.POST else link.type
        return Resolved(link.platform, type, link.url, title.take(90), entries, preview)
    }
}

package com.gonzalowebdev.mediadownloader.domain

import com.google.gson.JsonArray
import com.google.gson.JsonObject

enum class Platform(val label: String, val folder: String) {
    INSTAGRAM("Instagram", "Instagram"),
    TIKTOK("TikTok", "TikTok"),
    PINTEREST("Pinterest", "Pinterest")
}

enum class LinkType(val label: String) {
    PIN("Pin"),
    BOARD("Tablero"),
    BOARD_SECTION("Sección de tablero"),
    POST("Publicación"),
    REEL("Reel"),
    STORY("Story"),
    VIDEO("Video"),
    PHOTO_POST("Publicación de imágenes"),
    UNKNOWN("No soportado")
}

enum class MediaKind { IMAGE, VIDEO }

data class ParsedLink(
    val platform: Platform,
    val type: LinkType,
    val url: String,
    val ids: List<String> = emptyList()
)

/** Un archivo descargable. `urls` son candidatas en orden de preferencia; "lazy:" se resuelve al descargar. */
data class MediaEntry(
    val key: String,
    val fileName: String,
    val urls: List<String>,
    val kind: MediaKind,
    val previewUrl: String? = null
)

data class Resolved(
    val platform: Platform,
    val type: LinkType,
    val sourceUrl: String,
    val title: String,
    val entries: List<MediaEntry>,
    val previewUrl: String?,
    val notes: List<String> = emptyList()
)

class ResolveException(message: String) : Exception(message)

fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString
fun JsonObject.obj(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject
fun JsonObject.arr(k: String): JsonArray? = get(k)?.takeIf { it.isJsonArray }?.asJsonArray

fun sanitizeFileName(n: String): String = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120)

fun fileNameFromUrl(url: String, fallback: String): String {
    val n = url.substringBefore('?').substringAfterLast('/')
    return if (n.isBlank() || !n.contains('.')) fallback else sanitizeFileName(n)
}

object LinkParser {
    private val urlRegex = Regex("https?://[^\\s]+")
    private val reserved = setOf(
        "ideas", "search", "today", "business", "settings", "explore", "topics", "shop",
        "videos", "news_hub", "pin", "_saved", "_created", "login", "about", "help"
    )

    fun extractUrl(text: String): String? =
        urlRegex.find(text.trim())?.value?.trimEnd('.', ',', ')', '>', ']')

    fun parse(raw: String): ParsedLink? {
        val url = extractUrl(raw) ?: return null
        val uri = try { java.net.URI(url) } catch (e: Exception) { return null }
        val host = (uri.host ?: return null).lowercase().removePrefix("www.")
        val segs = (uri.path ?: "").split("/").filter { it.isNotEmpty() }
        return when {
            host == "instagram.com" || host.endsWith(".instagram.com") || host == "instagr.am" ->
                parseInstagram(url, segs)
            host.endsWith("tiktok.com") -> parseTikTok(url, host, segs)
            host == "pin.it" || host.contains("pinterest.") -> parsePinterest(url, host, segs)
            else -> null
        }
    }

    private fun parseInstagram(url: String, segs: List<String>): ParsedLink {
        val idx = segs.indexOfFirst { it == "p" || it == "reel" || it == "reels" || it == "tv" }
        if (idx >= 0 && idx + 1 < segs.size) {
            val type = if (segs[idx] == "p") LinkType.POST else LinkType.REEL
            return ParsedLink(Platform.INSTAGRAM, type, url, listOf(segs[idx + 1]))
        }
        if (segs.firstOrNull() == "stories") return ParsedLink(Platform.INSTAGRAM, LinkType.STORY, url, segs.drop(1))
        return ParsedLink(Platform.INSTAGRAM, LinkType.UNKNOWN, url)
    }

    private fun parseTikTok(url: String, host: String, segs: List<String>): ParsedLink {
        val vIdx = segs.indexOf("video")
        if (vIdx >= 0 && vIdx + 1 < segs.size) return ParsedLink(Platform.TIKTOK, LinkType.VIDEO, url, listOf(segs[vIdx + 1]))
        val pIdx = segs.indexOf("photo")
        if (pIdx >= 0 && pIdx + 1 < segs.size) return ParsedLink(Platform.TIKTOK, LinkType.PHOTO_POST, url, listOf(segs[pIdx + 1]))
        if (host.startsWith("vm.") || host.startsWith("vt.") || segs.firstOrNull() == "t")
            return ParsedLink(Platform.TIKTOK, LinkType.VIDEO, url)
        return ParsedLink(Platform.TIKTOK, LinkType.UNKNOWN, url)
    }

    private fun parsePinterest(url: String, host: String, segs: List<String>): ParsedLink {
        if (host == "pin.it") return ParsedLink(Platform.PINTEREST, LinkType.PIN, url)
        if (segs.size >= 2 && segs[0] == "pin") {
            val id = segs[1].substringAfterLast('-')
            return ParsedLink(Platform.PINTEREST, LinkType.PIN, url, listOf(id))
        }
        val first = segs.firstOrNull()?.lowercase()
        if (first == null || first in reserved) return ParsedLink(Platform.PINTEREST, LinkType.UNKNOWN, url)
        if (segs.size == 2 && !segs[1].startsWith("_")) return ParsedLink(Platform.PINTEREST, LinkType.BOARD, url, segs)
        if (segs.size >= 3 && !segs[1].startsWith("_")) return ParsedLink(Platform.PINTEREST, LinkType.BOARD_SECTION, url, segs.take(3))
        return ParsedLink(Platform.PINTEREST, LinkType.UNKNOWN, url)
    }
}

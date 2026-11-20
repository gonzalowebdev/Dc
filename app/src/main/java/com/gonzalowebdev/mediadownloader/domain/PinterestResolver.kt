package com.gonzalowebdev.mediadownloader.domain

import com.gonzalowebdev.mediadownloader.net.Net
import com.gonzalowebdev.mediadownloader.net.PinterestApi
import com.gonzalowebdev.mediadownloader.net.PinterestAuth
import com.google.gson.JsonObject
import java.net.URLDecoder
import java.text.Normalizer

class PinterestResolver(private val api: PinterestApi, private val auth: PinterestAuth) {

    suspend fun resolve(link0: ParsedLink, progress: (String) -> Unit): Resolved {
        var link = link0
        if (link.type == LinkType.PIN && link.ids.isEmpty()) {
            progress("Resolviendo enlace corto…")
            val finalUrl = Net.finalUrl(link.url)
            link = LinkParser.parse(finalUrl)
                ?: throw ResolveException("No pude resolver el enlace corto de Pinterest.")
        }
        return when (link.type) {
            LinkType.PIN -> resolvePin(link0, link, progress)
            LinkType.BOARD, LinkType.BOARD_SECTION -> resolveBoard(link0, link, progress)
            else -> throw ResolveException("Este enlace de Pinterest no está soportado. Usa el enlace de un Pin o de un tablero.")
        }
    }

    private suspend fun resolvePin(orig: ParsedLink, link: ParsedLink, progress: (String) -> Unit): Resolved {
        val id = link.ids.firstOrNull() ?: throw ResolveException("No pude identificar el Pin.")
        val notes = mutableListOf<String>()
        var entries: List<MediaEntry> = emptyList()
        var title = "Pin $id"
        if (auth.hasToken()) {
            progress("Consultando la API de Pinterest…")
            try {
                val pin = api.get("/v5/pins/$id")
                entries = pinToEntries(pin)
                pin.str("title")?.takeIf { it.isNotBlank() }?.let { title = it }
            } catch (e: ResolveException) {
                notes += "API: ${e.message} Se intentó la página pública."
            }
        }
        if (entries.isEmpty()) {
            progress("Leyendo la página pública del Pin…")
            entries = PinterestScraper.entriesFor(id)
        }
        return Resolved(Platform.PINTEREST, LinkType.PIN, orig.url, title, entries, entries.firstOrNull()?.previewUrl, notes)
    }

    private suspend fun resolveBoard(orig: ParsedLink, link: ParsedLink, progress: (String) -> Unit): Resolved {
        if (!auth.hasToken()) {
            throw ResolveException("Para descargar tableros debes conectar tu cuenta de Pinterest (Ajustes → Cuentas conectadas).")
        }
        val user = link.ids.getOrNull(0).orEmpty()
        val slug = URLDecoder.decode(link.ids.getOrNull(1).orEmpty(), "UTF-8")
        val notes = mutableListOf<String>()

        progress("Buscando el tablero entre tus tableros…")
        val boards = api.pagedItems("/v5/boards", "&privacy=ALL") { progress("Buscando el tablero… ($it tableros)") }
        val matches = boards.filter { norm(it.str("name").orEmpty()) == norm(slug) }
        val board = matches.firstOrNull { it.obj("owner")?.str("username").equals(user, ignoreCase = true) }
            ?: matches.firstOrNull()
            ?: throw ResolveException(
                "No encontré ese tablero entre los de tu cuenta conectada. La API oficial solo permite leer tableros propios (públicos o secretos)."
            )
        val boardId = board.str("id") ?: throw ResolveException("El tablero no devolvió un identificador.")
        val boardName = board.str("name") ?: slug
        val declared = board.get("pin_count")?.takeIf { it.isJsonPrimitive }?.asInt

        val pins = LinkedHashMap<String, JsonObject>()
        val sectionName = link.ids.getOrNull(2)?.let { URLDecoder.decode(it, "UTF-8") }

        if (link.type == LinkType.BOARD) {
            api.pagedItems("/v5/boards/$boardId/pins") { progress("Recuperando Pines… $it") }
                .forEach { p -> p.str("id")?.let { pins[it] = p } }
        }

        progress("Buscando secciones…")
        val sections = try {
            api.pagedItems("/v5/boards/$boardId/sections")
        } catch (e: ResolveException) {
            notes += "No pude leer las secciones: ${e.message}"
            emptyList()
        }
        val wanted = if (link.type == LinkType.BOARD_SECTION && sectionName != null)
            sections.filter { norm(it.str("name").orEmpty()) == norm(sectionName) }
        else sections
        if (link.type == LinkType.BOARD_SECTION && wanted.isEmpty())
            throw ResolveException("No encontré la sección «$sectionName» en ese tablero.")
        for (s in wanted) {
            val sid = s.str("id") ?: continue
            val sname = s.str("name").orEmpty()
            api.pagedItems("/v5/boards/$boardId/sections/$sid/pins") { progress("Sección «$sname»… ${pins.size + it} Pines") }
                .forEach { p -> p.str("id")?.let { pins[it] = p } }
        }
        if (sections.isNotEmpty() && link.type == LinkType.BOARD) notes += "El tablero tiene ${sections.size} secciones; sus Pines están incluidos."

        val entries = ArrayList<MediaEntry>()
        var withoutMedia = 0
        for (p in pins.values) {
            val e = pinToEntries(p)
            if (e.isEmpty()) withoutMedia++ else entries += e
        }
        if (withoutMedia > 0) notes += "$withoutMedia Pines no tienen imagen/video accesible por la API y no se incluyen."
        if (declared != null && link.type == LinkType.BOARD && declared != pins.size)
            notes += "Pinterest declara $declared Pines y se recuperaron ${pins.size}."
        if (entries.isEmpty()) throw ResolveException("El tablero no tiene archivos multimedia descargables.")

        val title = if (link.type == LinkType.BOARD_SECTION) "$boardName · $sectionName" else boardName
        return Resolved(Platform.PINTEREST, link.type, orig.url, title, entries.distinctBy { it.key }, entries.firstOrNull()?.previewUrl, notes)
    }

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace(Regex("[^a-z0-9]"), "")

    companion object {
        fun pinToEntries(pin: JsonObject): List<MediaEntry> {
            val id = pin.str("id") ?: return emptyList()
            val media = pin.obj("media") ?: return emptyList()
            return when (media.str("media_type")) {
                "video" -> listOf(videoEntry(id, media))
                "multiple_images", "multiple_mixed", "multiple_videos" -> {
                    val items = media.arr("items") ?: return emptyList()
                    items.mapIndexedNotNull { i, el ->
                        el.takeIf { it.isJsonObject }?.asJsonObject?.obj("images")?.let { imageEntry("${id}_${i + 1}", it) }
                    }
                }
                else -> media.obj("images")?.let { img -> imageEntry(id, img)?.let { listOf(it) } } ?: emptyList()
            }
        }

        private fun imageEntry(key: String, images: JsonObject): MediaEntry? {
            val urls = listOf("originals", "1200x", "600x", "400x300").mapNotNull { images.obj(it)?.str("url") }.distinct()
            if (urls.isEmpty()) return null
            val preview = images.obj("600x")?.str("url") ?: urls.last()
            return MediaEntry(key, fileNameFromUrl(urls.first(), "$key.jpg"), urls, MediaKind.IMAGE, preview)
        }

        private fun videoEntry(id: String, media: JsonObject): MediaEntry {
            val direct = media.str("video_url")
            val urls = listOfNotNull(direct, "lazy:pinvideo:$id")
            val preview = media.str("cover_image_url")
                ?: media.obj("images")?.let { it.obj("600x")?.str("url") ?: it.obj("originals")?.str("url") }
            val name = if (direct != null) fileNameFromUrl(direct, "$id.mp4") else "$id.mp4"
            return MediaEntry(id, name, urls, MediaKind.VIDEO, preview)
        }
    }
}

/** Respaldo para Pines públicos individuales cuando la API no expone el archivo. */
object PinterestScraper {
    private val videoRegex = Regex("https://v1\\.pinimg\\.com/videos/[^\"'\\\\\\s]+?\\.mp4")

    private suspend fun page(pinId: String): Pair<String, String> {
        val html = Net.getString("https://www.pinterest.com/pin/$pinId/")
        return html to html.replace("\\u002F", "/").replace("\\/", "/")
    }

    suspend fun videoUrls(pinId: String): List<String> {
        val (_, text) = page(pinId)
        val urls = videoRegex.findAll(text).map { it.value }.distinct().toList()
        return urls.sortedByDescending { score(it) }
    }

    private fun score(u: String) = when {
        u.contains("720p") -> 3
        u.contains("expMp4") -> 2
        u.contains("mp4") -> 1
        else -> 0
    }

    suspend fun entriesFor(pinId: String): List<MediaEntry> {
        val (html, text) = page(pinId)
        val og = Regex("<meta[^>]+property=\"og:image\"[^>]+content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        val videos = videoRegex.findAll(text).map { it.value }.distinct().toList().sortedByDescending { score(it) }
        if (videos.isNotEmpty()) {
            return listOf(MediaEntry(pinId, fileNameFromUrl(videos.first(), "$pinId.mp4"), videos, MediaKind.VIDEO, og))
        }
        val orig = Regex("\"orig\"\\s*:\\s*\\{[^{}]*?\"url\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        val urls = listOfNotNull(
            orig,
            og?.replace(Regex("/\\d+x\\d*/"), "/originals/"),
            og?.replace(Regex("/\\d+x\\d*/"), "/1200x/"),
            og
        ).distinct()
        if (urls.isEmpty()) {
            throw ResolveException("No pude obtener el archivo de este Pin: es privado, fue eliminado o solo está disponible como streaming HLS (no soportado).")
        }
        return listOf(MediaEntry(pinId, fileNameFromUrl(urls.first(), "$pinId.jpg"), urls, MediaKind.IMAGE, og ?: urls.first()))
    }
}

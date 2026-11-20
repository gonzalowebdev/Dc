package com.gonzalowebdev.mediadownloader.domain

import com.gonzalowebdev.mediadownloader.net.Net
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder

/**
 * Lectura de Pines y tableros PÚBLICOS de Pinterest sin cuenta ni token, usando los mismos
 * endpoints JSON que usa la web (/resource/...). Los tableros secretos requieren la cuenta conectada.
 */
object PinterestPublic {
    private const val BASE = "https://www.pinterest.com"
    @Volatile private var warmed = false

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun opts(vararg p: Pair<String, Any>): JsonObject = JsonObject().apply {
        p.forEach { (k, v) ->
            when (v) {
                is String -> addProperty(k, v)
                is Int -> addProperty(k, v)
                is Boolean -> addProperty(k, v)
            }
        }
    }

    private fun call(url: String, sourceUrl: String): Pair<Int, String> {
        val b = Request.Builder().url(url)
            .header("User-Agent", Net.UA_DESKTOP)
            .header("Accept", "application/json, text/javascript, */*, q=0.01")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("X-Pinterest-AppState", "active")
            .header("X-Pinterest-Source-Url", sourceUrl)
            .header("Referer", BASE + sourceUrl)
        Net.cookie(BASE, "csrftoken")?.let { b.header("X-CSRFToken", it) }
        return Net.client.newCall(b.build()).execute().use { r -> r.code to (r.body?.string().orEmpty()) }
    }

    private suspend fun resource(name: String, sourceUrl: String, options: JsonObject): JsonObject {
        if (!warmed) {
            try { Net.getString("$BASE/") } catch (e: Exception) { /* solo para obtener cookies */ }
            warmed = true
        }
        val data = JsonObject().apply { add("options", options); add("context", JsonObject()) }
        var attempt = 0
        while (true) {
            val url = "$BASE/resource/$name/get/?source_url=${enc(sourceUrl)}&data=${enc(data.toString())}&_=${System.currentTimeMillis()}"
            val (code, body) = try {
                withContext(Dispatchers.IO) { call(url, sourceUrl) }
            } catch (e: java.io.IOException) {
                throw ResolveException("Sin conexión con Pinterest: ${e.message}")
            }
            if (code == 429 && attempt < 5) { attempt++; delay(4_000L * attempt); continue }
            if (code == 404) throw ResolveException("Pinterest no encontró el recurso (HTTP 404): es privado o no existe.")
            if (code == 403 || code == 401) throw ResolveException("Pinterest bloqueó la lectura pública (HTTP $code).")
            if (code !in 200..299) throw ResolveException("Pinterest respondió HTTP $code.")
            val rr = try {
                JsonParser.parseString(body).asJsonObject.obj("resource_response")
            } catch (e: Exception) { null } ?: throw ResolveException("Pinterest devolvió una respuesta ilegible.")
            val err = rr.get("error")
            if (err != null && !err.isJsonNull) throw ResolveException("Pinterest no devolvió el contenido (¿privado o inexistente?).")
            return rr
        }
    }

    suspend fun pin(id: String): JsonObject {
        val rr = resource("PinResource", "/pin/$id/", opts("id" to id, "field_set_key" to "detail"))
        return rr.obj("data") ?: throw ResolveException("Pinterest no devolvió datos del Pin.")
    }

    suspend fun board(user: String, slug: String): JsonObject {
        val rr = resource("BoardResource", "/$user/$slug/", opts("slug" to slug, "username" to user, "field_set_key" to "detail"))
        return rr.obj("data")?.takeIf { it.str("id") != null }
            ?: throw ResolveException("No encontré ese tablero (es privado o no existe).")
    }

    private suspend fun paged(
        name: String, sourceUrl: String, base: JsonObject, onProgress: (Int) -> Unit
    ): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        var bookmark: String? = null
        var emptyPages = 0
        do {
            val o = base.deepCopy()
            o.addProperty("page_size", 250)
            if (bookmark != null) o.add("bookmarks", JsonArray().apply { add(bookmark) })
            val rr = resource(name, sourceUrl, o)
            val arr = rr.arr("data")
            var added = 0
            arr?.forEach { el ->
                if (el.isJsonObject && el.asJsonObject.str("id") != null) { out.add(el.asJsonObject); added++ }
            }
            emptyPages = if (added == 0) emptyPages + 1 else 0
            onProgress(out.size)
            bookmark = rr.str("bookmark")?.takeIf { it.isNotEmpty() && it != "-end-" }
            delay(400)
        } while (bookmark != null && emptyPages < 3)
        return out
    }

    suspend fun boardPins(user: String, slug: String, boardId: String, onProgress: (Int) -> Unit) =
        paged("BoardFeedResource", "/$user/$slug/", opts("board_id" to boardId, "field_set_key" to "react_grid_pin"), onProgress)

    suspend fun sections(user: String, slug: String, boardId: String) =
        paged("BoardSectionsResource", "/$user/$slug/", opts("board_id" to boardId), {})

    suspend fun sectionPins(user: String, slug: String, sectionId: String, onProgress: (Int) -> Unit) =
        paged("BoardSectionPinsResource", "/$user/$slug/", opts("section_id" to sectionId), onProgress)

    // ---------- Conversión de Pines a archivos ----------
    private fun imageUrl(images: JsonObject?): String? {
        if (images == null) return null
        for (k in listOf("orig", "originals", "736x", "474x")) {
            images.obj(k)?.str("url")?.let { return it }
        }
        return null
    }

    private fun bestMp4(videoList: JsonObject?): String? {
        if (videoList == null) return null
        return videoList.entrySet().mapNotNull { (_, v) ->
            val o = v.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val u = o.str("url") ?: return@mapNotNull null
            if (!u.substringBefore('?').endsWith(".mp4")) return@mapNotNull null
            u to (o.get("height")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0)
        }.maxByOrNull { it.second }?.first
    }

    fun pinToEntries(pin: JsonObject): List<MediaEntry> {
        val id = pin.str("id") ?: return emptyList()
        val cover = imageUrl(pin.obj("images"))
        val out = ArrayList<MediaEntry>()

        fun image(key: String, url: String) =
            MediaEntry(key, fileNameFromUrl(url, "$key.jpg"), listOf(url), MediaKind.IMAGE, url)

        fun video(key: String, mp4: String?): MediaEntry {
            val urls = listOfNotNull(mp4, "ytdlp:0:https://www.pinterest.com/pin/$id/")
            return MediaEntry(key, if (mp4 != null) fileNameFromUrl(mp4, "$key.mp4") else "$key.mp4", urls, MediaKind.VIDEO, cover)
        }

        // Pines de historia (idea pins): varias páginas con bloques de imagen/video
        val pages = pin.obj("story_pin_data")?.arr("pages")
        if (pages != null && pages.size() > 0) {
            var n = 0
            pages.forEach { pg ->
                pg.takeIf { it.isJsonObject }?.asJsonObject?.arr("blocks")?.forEach { bl ->
                    val b = bl.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                    val mp4 = bestMp4(b.obj("video")?.obj("video_list"))
                    val img = imageUrl(b.obj("image")?.obj("images"))
                    when {
                        b.obj("video") != null -> { n++; out += video("${id}_$n", mp4) }
                        img != null -> { n++; out += image("${id}_$n", img) }
                    }
                }
            }
            if (out.isNotEmpty()) return out
        }

        // Carrusel de imágenes
        val slots = pin.obj("carousel_data")?.arr("carousel_slots")
        if (slots != null && slots.size() > 0) {
            slots.forEachIndexed { i, s ->
                imageUrl(s.takeIf { it.isJsonObject }?.asJsonObject?.obj("images"))?.let { out += image("${id}_${i + 1}", it) }
            }
            if (out.isNotEmpty()) return out
        }

        // Video
        val videoList = pin.obj("videos")?.obj("video_list")
        if (videoList != null && videoList.size() > 0) return listOf(video(id, bestMp4(videoList)))

        // Imagen
        return cover?.let { listOf(image(id, it)) } ?: emptyList()
    }
}

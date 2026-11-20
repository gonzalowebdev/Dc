package com.gonzalowebdev.mediadownloader.domain

import com.gonzalowebdev.mediadownloader.net.Net
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.math.BigInteger

/** Publicaciones, carruseles, reels y Stories de Instagram usando la sesión iniciada por el usuario. */
object InstagramApi {
    private const val APP_ID = "936619743392459"
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private fun mediaId(code: String): String {
        var id = BigInteger.ZERO
        for (c in code.take(11)) {
            val i = ALPHABET.indexOf(c)
            if (i < 0) break
            id = id.multiply(BigInteger.valueOf(64)).add(BigInteger.valueOf(i.toLong()))
        }
        return id.toString()
    }

    private suspend fun getJson(url: String, referer: String): JsonObject = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url)
            .header("User-Agent", Net.UA_DESKTOP)
            .header("X-IG-App-ID", APP_ID)
            .header("X-ASBD-ID", "129477")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "*/*")
            .header("Referer", referer)
        Net.cookie("https://www.instagram.com", "csrftoken")?.let { b.header("X-CSRFToken", it) }
        Net.client.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403 || body.contains("login_required"))
                throw ResolveException("Instagram rechazó la sesión. Cierra sesión y vuelve a iniciarla en Ajustes → Cuentas conectadas.")
            if (r.code == 404) throw ResolveException("Instagram no encontró el contenido (borrado, o privado de una cuenta que no sigues).")
            if (r.code == 429) throw ResolveException("Instagram limitó las consultas (HTTP 429). Espera unos minutos e intenta de nuevo.")
            if (!r.isSuccessful) throw ResolveException("Instagram respondió HTTP ${r.code}.")
            try { JsonParser.parseString(body).asJsonObject } catch (e: Exception) {
                throw ResolveException("Instagram devolvió una respuesta ilegible.")
            }
        }
    }

    private fun firstObj(arr: com.google.gson.JsonArray?): JsonObject? =
        arr?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject

    private fun bestImage(m: JsonObject): String? = firstObj(m.obj("image_versions2")?.arr("candidates"))?.str("url")
    private fun bestVideo(m: JsonObject): String? = firstObj(m.arr("video_versions"))?.str("url")

    suspend fun resolve(link: ParsedLink): Resolved =
        if (link.type == LinkType.STORY) story(link) else post(link)

    private suspend fun post(link: ParsedLink): Resolved {
        val code = link.ids.firstOrNull() ?: throw ResolveException("No pude identificar la publicación.")
        val root = getJson("https://www.instagram.com/api/v1/media/${mediaId(code)}/info/", link.url)
        val item = firstObj(root.arr("items")) ?: throw ResolveException("Instagram no devolvió la publicación.")
        val nodes = item.arr("carousel_media")?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            ?.takeIf { it.isNotEmpty() } ?: listOf(item)
        val entries = nodes.mapIndexedNotNull { i, n ->
            val v = bestVideo(n)
            val img = bestImage(n)
            val suffix = if (nodes.size > 1) "_${i + 1}" else ""
            when {
                v != null -> MediaEntry("$code#${i + 1}", "instagram_$code$suffix.mp4", listOf(v), MediaKind.VIDEO, img)
                img != null -> MediaEntry("$code#${i + 1}", "instagram_$code$suffix.jpg", listOf(img), MediaKind.IMAGE, img)
                else -> null
            }
        }
        if (entries.isEmpty()) throw ResolveException("La publicación no tiene archivos descargables.")
        val user = item.obj("user")?.str("username")
        val caption = item.obj("caption")?.str("text")?.lineSequence()?.firstOrNull()?.take(70)?.takeIf { it.isNotBlank() }
        val title = caption ?: "Publicación de @${user ?: code}"
        val type = if (nodes.size > 1) LinkType.POST else link.type
        return Resolved(Platform.INSTAGRAM, type, link.url, title, entries, entries.firstNotNullOfOrNull { it.previewUrl })
    }

    private suspend fun story(link: ParsedLink): Resolved {
        val user = link.ids.getOrNull(0)
        if (user == null || user == "highlights")
            throw ResolveException("Las historias destacadas no están soportadas. Usa el enlace de una Story (instagram.com/stories/usuario/…).")
        val storyId = link.ids.getOrNull(1)
        val profile = getJson("https://www.instagram.com/api/v1/users/web_profile_info/?username=$user", "https://www.instagram.com/$user/")
        val uid = profile.obj("data")?.obj("user")?.str("id") ?: throw ResolveException("No encontré al usuario @$user.")
        val reels = getJson("https://www.instagram.com/api/v1/feed/reels_media/?reel_ids=$uid", "https://www.instagram.com/stories/$user/")
        val items = (firstObj(reels.arr("reels_media"))?.arr("items") ?: reels.obj("reels")?.obj(uid)?.arr("items"))
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject } ?: emptyList()
        if (items.isEmpty()) throw ResolveException("No hay Stories activas de @$user (duran 24 h) o no tienes acceso.")
        val picked = storyId?.let { id -> items.filter { it.str("pk") == id || it.str("id")?.startsWith("${id}_") == true } }
            ?.takeIf { it.isNotEmpty() } ?: items
        val entries = picked.mapNotNull { n ->
            val pk = n.str("pk") ?: return@mapNotNull null
            val v = bestVideo(n)
            val img = bestImage(n)
            when {
                v != null -> MediaEntry("story_$pk", "instagram_story_${user}_$pk.mp4", listOf(v), MediaKind.VIDEO, img)
                img != null -> MediaEntry("story_$pk", "instagram_story_${user}_$pk.jpg", listOf(img), MediaKind.IMAGE, img)
                else -> null
            }
        }
        if (entries.isEmpty()) throw ResolveException("Las Stories no tienen archivos descargables.")
        return Resolved(Platform.INSTAGRAM, LinkType.STORY, link.url, "Stories de @$user", entries, entries.firstNotNullOfOrNull { it.previewUrl })
    }
}

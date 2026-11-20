package com.gonzalowebdev.mediadownloader.domain

import com.gonzalowebdev.mediadownloader.net.Net
import com.google.gson.JsonParser

/**
 * TikTok no ofrece API oficial para descargar videos de terceros. Se lee la página pública del video
 * (datos embebidos) y se descarga con las mismas cookies/Referer. Solo contenido público.
 */
object TikTokResolver {

    suspend fun resolve(linkIn: ParsedLink): Resolved {
        var link = linkIn
        var url = link.url
        if (link.ids.isEmpty()) {
            url = Net.finalUrl(url)
            link = LinkParser.parse(url) ?: throw ResolveException("No pude resolver el enlace corto de TikTok.")
            if (link.ids.isEmpty()) throw ResolveException("No pude identificar el video en ese enlace de TikTok.")
        }
        val id = link.ids.first()
        val html = Net.getString(url.substringBefore('?'), mapOf("Referer" to "https://www.tiktok.com/"))
        val json = Regex("<script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            ?: throw ResolveException("TikTok no devolvió datos del video (puede ser privado, eliminado o pedir verificación).")
        val item = try {
            JsonParser.parseString(json).asJsonObject.obj("__DEFAULT_SCOPE__")?.obj("webapp.video-detail")
                ?.obj("itemInfo")?.obj("itemStruct")
        } catch (e: Exception) { null } ?: throw ResolveException("No pude leer los datos de este TikTok (¿contenido privado o con restricciones?).")

        val desc = item.str("desc").orEmpty()
        val title = desc.ifBlank { "TikTok $id" }.take(70)
        val notes = mutableListOf<String>()
        val entries = ArrayList<MediaEntry>()
        var preview: String? = null

        val images = item.obj("imagePost")?.arr("images")
        if (images != null) {
            images.forEachIndexed { i, el ->
                val u = el.asJsonObject.obj("imageURL")?.arr("urlList")?.firstOrNull()?.asString ?: return@forEachIndexed
                val ext = u.substringBefore('?').substringAfterLast('.', "jpeg").takeIf { it.length in 3..4 } ?: "jpeg"
                if (preview == null) preview = u
                entries += MediaEntry("${id}_${i + 1}", "tiktok_${id}_${i + 1}.$ext", listOf(u), MediaKind.IMAGE, u)
            }
            notes += "El audio de las publicaciones de imágenes no se descarga."
        } else {
            val video = item.obj("video") ?: throw ResolveException("Este TikTok no contiene un video descargable.")
            val urls = listOfNotNull(video.str("playAddr"), video.str("downloadAddr")).filter { it.isNotBlank() }.distinct()
            if (urls.isEmpty()) throw ResolveException("TikTok no expuso la dirección del video.")
            preview = video.str("cover") ?: video.str("originCover")
            entries += MediaEntry(id, "tiktok_$id.mp4", urls, MediaKind.VIDEO, preview)
        }
        if (entries.isEmpty()) throw ResolveException("No encontré archivos descargables en este TikTok.")
        return Resolved(Platform.TIKTOK, if (images != null) LinkType.PHOTO_POST else LinkType.VIDEO, linkIn.url, title, entries, preview, notes)
    }
}

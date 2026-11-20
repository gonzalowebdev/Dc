package com.gonzalowebdev.mediadownloader.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.gonzalowebdev.mediadownloader.domain.Platform
import com.gonzalowebdev.mediadownloader.domain.sanitizeFileName
import java.io.IOException
import java.io.OutputStream

/** Guarda en Download/Media Downloader/<Plataforma>/ (sin subcarpetas) usando MediaStore. */
class MediaStoreSaver(private val ctx: Context) {
    private val resolver get() = ctx.contentResolver

    fun relativePath(p: Platform) = "${Environment.DIRECTORY_DOWNLOADS}/Media Downloader/${p.folder}/"

    private fun exists(rel: String, name: String): Boolean {
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, rel),
            null
        )?.use { return it.count > 0 }
        return false
    }

    /** Nunca sobrescribe: agrega _1, _2… si el nombre ya existe. */
    fun uniqueName(p: Platform, desired: String): String {
        val rel = relativePath(p)
        val clean = sanitizeFileName(desired).ifBlank { "archivo" }
        if (!exists(rel, clean)) return clean
        val base = clean.substringBeforeLast('.', clean)
        val ext = if (clean.contains('.')) "." + clean.substringAfterLast('.') else ""
        var i = 1
        var c: String
        do {
            c = "${base}_$i$ext"
            i++
        } while (exists(rel, c))
        return c
    }

    fun create(p: Platform, name: String, mime: String): Uri {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(p))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: throw IOException("No se pudo crear el archivo en el almacenamiento")
    }

    fun output(uri: Uri): OutputStream =
        resolver.openOutputStream(uri) ?: throw IOException("No se pudo abrir el archivo para escribir")

    fun finish(uri: Uri) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    fun abort(uri: Uri) {
        try { resolver.delete(uri, null, null) } catch (e: Exception) { /* nada */ }
    }
}

fun guessMime(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

fun withExtension(name: String, mime: String): String {
    val ext = name.substringAfterLast('.', "")
    if (ext.length in 2..5 && ext.all { it.isLetterOrDigit() }) return name
    val e = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
    return "$name.$e"
}

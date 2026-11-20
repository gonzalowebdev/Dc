package com.gonzalowebdev.mediadownloader.net

import android.content.Context
import com.gonzalowebdev.mediadownloader.data.Prefs
import com.gonzalowebdev.mediadownloader.domain.Platform
import java.io.File

/**
 * Sesiones iniciadas por el usuario en la página real de cada red (WebView). No se guardan contraseñas:
 * solo las cookies de sesión, cifradas. Se usan en las consultas HTTP y como cookies.txt para yt-dlp.
 */
object Sessions {
    private lateinit var prefs: Prefs

    fun init(p: Prefs) {
        prefs = p
        Platform.values().forEach { seed(it) }
    }

    fun domainOf(p: Platform) = when (p) {
        Platform.INSTAGRAM -> "instagram.com"
        Platform.TIKTOK -> "tiktok.com"
        Platform.PINTEREST -> "pinterest.com"
    }

    fun has(p: Platform): Boolean = !prefs.getSession(p.name).isNullOrBlank()

    private fun parse(header: String): List<Pair<String, String>> = header.split(";").mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
    }

    fun save(p: Platform, cookieHeader: String) {
        prefs.setSession(p.name, cookieHeader)
        seed(p)
    }

    fun clear(p: Platform) {
        prefs.setSession(p.name, null)
        Net.removeDomain(domainOf(p))
    }

    private fun seed(p: Platform) {
        val h = prefs.getSession(p.name) ?: return
        Net.seedCookies(domainOf(p), parse(h))
    }

    /** Archivo cookies.txt (formato Netscape) temporal para yt-dlp; quien lo pide debe borrarlo. */
    fun cookieFile(ctx: Context, p: Platform): File? {
        val h = prefs.getSession(p.name)?.takeIf { it.isNotBlank() } ?: return null
        val exp = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        parse(h).forEach { (n, v) -> sb.append(".${domainOf(p)}\tTRUE\t/\tTRUE\t$exp\t$n\t$v\n") }
        val f = File(ctx.cacheDir, "cookies_${p.name}_${System.nanoTime()}.txt")
        f.writeText(sb.toString())
        return f
    }
}

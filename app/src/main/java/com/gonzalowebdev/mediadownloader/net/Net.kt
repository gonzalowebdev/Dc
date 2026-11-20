package com.gonzalowebdev.mediadownloader.net

import com.gonzalowebdev.mediadownloader.domain.ResolveException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

private class MemoryCookieJar : CookieJar {
    private val store = CopyOnWriteArrayList<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { c ->
            store.removeIf { it.name == c.name && it.domain == c.domain && it.path == c.path }
            store.add(c)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        store.removeIf { it.expiresAt < now }
        return store.filter { it.matches(url) }
    }
}

object Net {
    const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val jar = MemoryCookieJar()

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun cookie(url: String, name: String): String? =
        jar.loadForRequest(url.toHttpUrl()).firstOrNull { it.name == name }?.value

    suspend fun getString(url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(url)
                .header("User-Agent", UA_DESKTOP)
                .header("Accept-Language", "es-AR,es;q=0.9,en;q=0.8")
            headers.forEach { (k, v) -> b.header(k, v) }
            client.newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) throw ResolveException("HTTP ${r.code} al consultar ${r.request.url.host}")
                r.body?.string() ?: ""
            }
        }

    /** Sigue redirecciones (enlaces cortos) y devuelve la URL final. */
    suspend fun finalUrl(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", UA_DESKTOP).build()
        client.newCall(req).execute().use { it.request.url.toString() }
    }
}

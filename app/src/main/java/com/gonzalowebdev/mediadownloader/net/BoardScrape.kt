package com.gonzalowebdev.mediadownloader.net

import android.content.Context
import android.content.Intent
import com.gonzalowebdev.mediadownloader.ui.BoardScrapeActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class ScrapedPin(val id: String, val src: String, val video: Boolean)
data class ScrapeRequest(val boardUrl: String, val user: String, val slug: String, val sectionSlug: String?, val includeSections: Boolean)
data class ScrapeResult(val pins: List<ScrapedPin>, val declaredCount: Int?, val notes: List<String>)

/** Puente entre el resolvedor y la pantalla que lee el tablero desde la página (WebView visible, con tu sesión). */
object BoardScrape {
    @Volatile var request: ScrapeRequest? = null
    @Volatile var result: CompletableDeferred<ScrapeResult>? = null
    @Volatile var progress: ((String) -> Unit)? = null

    suspend fun run(ctx: Context, req: ScrapeRequest, onProgress: (String) -> Unit): ScrapeResult {
        val d = CompletableDeferred<ScrapeResult>()
        request = req
        result = d
        progress = onProgress
        try {
            withContext(Dispatchers.Main) {
                ctx.startActivity(Intent(ctx, BoardScrapeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return withTimeout(25 * 60_000L) { d.await() }
        } finally {
            request = null
            result = null
            progress = null
        }
    }
}

package com.gonzalowebdev.mediadownloader.download

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.gonzalowebdev.mediadownloader.App
import com.gonzalowebdev.mediadownloader.data.AppDao
import com.gonzalowebdev.mediadownloader.data.ItemEntity
import com.gonzalowebdev.mediadownloader.data.Status
import com.gonzalowebdev.mediadownloader.domain.Platform
import com.gonzalowebdev.mediadownloader.net.Net
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

private class PausedException : Exception()
private data class Probe(val ok: Boolean, val mime: String?, val error: String)

class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val app get() = applicationContext as App
    private val claimMutex = Mutex()
    private val doneRun = AtomicInteger(0)
    private val failRun = AtomicInteger(0)
    private var lastNotif = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo =
        Notifs.foreground(applicationContext, "Preparando descargas…", 0, 0)

    override suspend fun doWork(): Result {
        val dao = app.db.dao()
        dao.resetInterrupted()
        try { setForeground(Notifs.foreground(applicationContext, "Descargando…", 0, 0)) } catch (e: Exception) { /* sin servicio en primer plano */ }
        val workers = app.prefs.concurrency
        coroutineScope {
            repeat(workers) {
                launch(Dispatchers.IO) {
                    while (true) {
                        val item = claimNext(dao) ?: break
                        process(item, dao)
                    }
                }
            }
        }
        val d = doneRun.get()
        val f = failRun.get()
        if (d + f > 0) Notifs.finished(applicationContext, app.prefs, d, f)
        return Result.success()
    }

    private suspend fun claimNext(dao: AppDao): ItemEntity? = claimMutex.withLock {
        val next = dao.nextPending(1).firstOrNull() ?: return null
        dao.setStatus(next.id, Status.DOWNLOADING)
        next.copy(status = Status.DOWNLOADING)
    }

    private fun requestFor(url: String, platform: String): Request.Builder {
        val b = Request.Builder().url(url).header("User-Agent", Net.UA_DESKTOP)
        when (platform) {
            "TIKTOK" -> b.header("Referer", "https://www.tiktok.com/")
            "INSTAGRAM" -> b.header("Referer", "https://www.instagram.com/")
            "PINTEREST" -> b.header("Referer", "https://www.pinterest.com/")
        }
        return b
    }

    /** Verifica el recurso antes de descargarlo (HEAD, y si no se permite, GET con Range 0-0). */
    private fun probe(url: String, platform: String): Probe = try {
        val head = Net.client.newCall(requestFor(url, platform).head().build()).execute().use { r ->
            if (r.isSuccessful) Probe(true, r.header("Content-Type")?.substringBefore(';')?.trim(), "") else null
        }
        head ?: Net.client.newCall(requestFor(url, platform).header("Range", "bytes=0-0").build()).execute().use { r ->
            if (r.isSuccessful) Probe(true, r.header("Content-Type")?.substringBefore(';')?.trim(), "")
            else Probe(false, null, "HTTP ${r.code}")
        }
    } catch (e: IOException) {
        Probe(false, null, e.message ?: "error de red")
    }

    private suspend fun process(item: ItemEntity, dao: AppDao) {
        val saver = app.saver
        val platform = Platform.valueOf(item.platform)
        var uri: Uri? = null
        try {
            val urls = item.urls.split("\n").filter { it.isNotBlank() }
            var chosen: String? = null
            var chosenMime: String? = null
            var lastErr = "sin dirección"
            outer@ for (u in urls) {
                val cands = if (u.startsWith("lazy:")) {
                    try { app.resolver.expandLazy(u) } catch (e: Exception) { lastErr = e.message ?: "no se pudo resolver"; emptyList() }
                } else listOf(u)
                for (c in cands) {
                    currentCoroutineContext().ensureActive()
                    val p = probe(c, item.platform)
                    if (p.ok) { chosen = c; chosenMime = p.mime; break@outer } else lastErr = p.error
                }
            }
            if (chosen == null) { fail(item, dao, "Archivo no disponible ($lastErr)"); return }

            Net.client.newCall(requestFor(chosen, item.platform).build()).execute().use { resp ->
                if (!resp.isSuccessful) { fail(item, dao, "El servidor respondió HTTP ${resp.code}"); return }
                val body = resp.body ?: run { fail(item, dao, "Respuesta vacía"); return }
                val total = body.contentLength()
                val mime = listOf(resp.header("Content-Type")?.substringBefore(';')?.trim(), chosenMime)
                    .firstOrNull { !it.isNullOrBlank() && it.contains('/') && it != "application/octet-stream" }
                    ?: guessMime(item.fileName)
                val name = saver.uniqueName(platform, withExtension(item.fileName, mime))
                val target = saver.create(platform, name, mime)
                uri = target
                var written = 0L
                var lastCheck = 0L
                saver.output(target).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastCheck > 700) {
                                lastCheck = now
                                currentCoroutineContext().ensureActive()
                                dao.progress(item.id, written, total)
                                if (dao.statusOf(item.id) != Status.DOWNLOADING) throw PausedException()
                            }
                        }
                    }
                }
                if (written == 0L) throw IOException("El archivo descargado está vacío")
                if (total > 0 && written != total) throw IOException("Descarga incompleta ($written de $total bytes)")
                saver.finish(target)
                dao.markDone(item.id, target.toString(), name, written, System.currentTimeMillis())
                doneRun.incrementAndGet()
                notifyProgress(dao)
            }
        } catch (e: PausedException) {
            uri?.let { saver.abort(it) }
        } catch (e: CancellationException) {
            uri?.let { saver.abort(it) }
            withContext(NonCancellable) { dao.setStatus(item.id, Status.PENDING) }
            throw e
        } catch (e: Exception) {
            uri?.let { saver.abort(it) }
            fail(item, dao, humanError(e))
        }
    }

    private suspend fun fail(item: ItemEntity, dao: AppDao, msg: String) {
        dao.markFailed(item.id, msg, System.currentTimeMillis())
        failRun.incrementAndGet()
        notifyProgress(dao)
    }

    private fun humanError(e: Exception) = when (e) {
        is UnknownHostException -> "Sin conexión a internet"
        else -> e.message ?: e.javaClass.simpleName
    }

    private suspend fun notifyProgress(dao: AppDao) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotif < 800) return
        lastNotif = now
        val remaining = dao.openCount()
        val d = doneRun.get()
        val f = failRun.get()
        val text = "Descargados $d · Pendientes $remaining · Errores $f"
        try { setForeground(Notifs.foreground(applicationContext, text, d + f, d + f + remaining)) } catch (e: Exception) { /* nada */ }
    }
}

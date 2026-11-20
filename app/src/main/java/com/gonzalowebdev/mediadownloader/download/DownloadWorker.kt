package com.gonzalowebdev.mediadownloader.download

import android.content.Context
import com.gonzalowebdev.mediadownloader.net.YtDlp
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.runBlocking
import java.io.File
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
            val all = item.urls.split("\n").filter { it.isNotBlank() }
            val ytSpec = all.firstOrNull { it.startsWith("ytdlp:") }
            val urls = all.filter { !it.startsWith("ytdlp:") }
            if (urls.isEmpty() && ytSpec != null) { processYtDlp(item, dao, ytSpec); return }
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
            if (chosen == null) {
                if (ytSpec != null) { processYtDlp(item, dao, ytSpec); return }
                fail(item, dao, "Archivo no disponible ($lastErr)"); return
            }

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

    /** Descarga con yt-dlp a una carpeta temporal y luego copia el archivo a MediaStore. */
    private suspend fun processYtDlp(item: ItemEntity, dao: AppDao, spec: String) {
        val saver = app.saver
        val platform = Platform.valueOf(item.platform)
        val body = spec.removePrefix("ytdlp:")
        val idx = body.substringBefore(':').toIntOrNull() ?: 0
        val url = body.substringAfter(':')
        val pid = "ytdlp-${item.id}"
        val dir = File(applicationContext.cacheDir, "ytdlp/${item.id}")
        dir.deleteRecursively()
        dir.mkdirs()
        var uri: Uri? = null
        var paused = false
        var cookieFile: File? = null
        try {
            YtDlp.ensure(applicationContext)
            val req = YoutubeDLRequest(url)
            req.addOption("-o", "${dir.absolutePath}/%(id)s.%(ext)s")
            req.addOption("-f", "b[ext=mp4]/bv*[ext=mp4]+ba[ext=m4a]/b/bv*+ba")
            req.addOption("--merge-output-format", "mp4")
            if (idx > 0) req.addOption("--playlist-items", idx.toString()) else req.addOption("--no-playlist")
            cookieFile = com.gonzalowebdev.mediadownloader.net.Sessions.cookieFile(applicationContext, platform)
            cookieFile?.let { req.addOption("--cookies", it.absolutePath) }
            req.addOption("--no-mtime")
            req.addOption("--no-part")
            req.addOption("--retries", "3")
            req.addOption("--socket-timeout", "30")
            var lastCheck = 0L
            withContext(Dispatchers.IO) {
                try {
                    YoutubeDL.getInstance().execute(req, pid) { progress, _, _ ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastCheck > 800) {
                            lastCheck = now
                            val st = runBlocking { dao.statusOf(item.id) }
                            if (st != Status.DOWNLOADING) {
                                paused = true
                                YoutubeDL.getInstance().destroyProcessById(pid)
                            } else if (isStopped) {
                                YoutubeDL.getInstance().destroyProcessById(pid)
                            } else {
                                runBlocking { dao.progress(item.id, progress.toLong(), 100L) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (paused) throw PausedException()
                    if (isStopped) throw CancellationException("Trabajo detenido")
                    throw e
                }
            }
            currentCoroutineContext().ensureActive()
            val produced = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.length() }
            if (produced == null || produced.length() == 0L) throw IOException("yt-dlp no generó ningún archivo")
            val base = item.fileName.substringBeforeLast('.', item.fileName)
            val name = saver.uniqueName(platform, "$base.${produced.extension.ifBlank { "mp4" }}")
            val mime = guessMime(name)
            val target = saver.create(platform, name, mime)
            uri = target
            saver.output(target).use { out -> produced.inputStream().use { it.copyTo(out) } }
            saver.finish(target)
            dao.markDone(item.id, target.toString(), name, produced.length(), System.currentTimeMillis())
            doneRun.incrementAndGet()
            notifyProgress(dao)
        } catch (e: PausedException) {
            uri?.let { saver.abort(it) }
        } catch (e: CancellationException) {
            uri?.let { saver.abort(it) }
            withContext(NonCancellable) { dao.setStatus(item.id, Status.PENDING) }
            throw e
        } catch (e: Exception) {
            uri?.let { saver.abort(it) }
            fail(item, dao, YtDlp.friendly(e.message))
        } finally {
            dir.deleteRecursively()
            cookieFile?.delete()
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

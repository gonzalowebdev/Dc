package com.gonzalowebdev.mediadownloader.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.gonzalowebdev.mediadownloader.App
import com.gonzalowebdev.mediadownloader.data.ItemEntity
import com.gonzalowebdev.mediadownloader.data.JobEntity
import com.gonzalowebdev.mediadownloader.data.Status
import com.gonzalowebdev.mediadownloader.domain.Resolved

data class EnqueueResult(val added: Int, val alreadyDownloaded: Int)

class Repo(private val ctx: Context) {
    private val app get() = ctx.applicationContext as App
    private val dao get() = app.db.dao()

    fun schedule() {
        val net = if (app.prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("downloads", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    suspend fun enqueue(r: Resolved): EnqueueResult {
        val done = dao.doneKeys(r.platform.name).toHashSet()
        val unique = r.entries.distinctBy { it.key }
        val fresh = unique.filter { it.key !in done }
        val skipped = unique.size - fresh.size
        if (fresh.isEmpty()) return EnqueueResult(0, skipped)
        val now = System.currentTimeMillis()
        val jobId = dao.insertJob(
            JobEntity(platform = r.platform.name, title = r.title, sourceUrl = r.sourceUrl, type = r.type.name, createdAt = now)
        )
        fresh.chunked(500).forEach { chunk ->
            dao.insertItems(chunk.map {
                ItemEntity(
                    jobId = jobId, platform = r.platform.name, sourceKey = it.key, fileName = it.fileName,
                    urls = it.urls.joinToString("\n"), kind = it.kind.name, status = Status.PENDING, createdAt = now
                )
            })
        }
        schedule()
        return EnqueueResult(fresh.size, skipped)
    }

    suspend fun pauseJob(id: Long) = dao.pauseJob(id)
    suspend fun resumeJob(id: Long) { dao.resumeJob(id); schedule() }
    suspend fun retryFailed(id: Long) { dao.retryFailed(id); schedule() }
    suspend fun retryItem(id: Long) { dao.retryItem(id); schedule() }
    suspend fun deleteItem(id: Long) = dao.deleteItem(id)
    suspend fun deleteJob(id: Long) { dao.deleteItemsOfJob(id); dao.deleteJobRow(id) }
}

package com.gonzalowebdev.mediadownloader.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

object Status {
    const val PENDING = "PENDING"
    const val DOWNLOADING = "DOWNLOADING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
    const val PAUSED = "PAUSED"
}

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val platform: String,
    val title: String,
    val sourceUrl: String,
    val type: String,
    val createdAt: Long
)

@Entity(
    tableName = "items",
    indices = [Index("jobId"), Index("status"), Index(value = ["platform", "sourceKey"])]
)
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val platform: String,
    val sourceKey: String,
    val fileName: String,
    /** URLs candidatas separadas por salto de línea (la primera que responda se usa). */
    val urls: String,
    val kind: String,
    val status: String,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val sizeBytes: Long = 0,
    val location: String? = null,
    val savedName: String? = null,
    val error: String? = null,
    val createdAt: Long,
    val finishedAt: Long? = null
)

data class JobStats(
    val id: Long,
    val platform: String,
    val title: String,
    val type: String,
    val sourceUrl: String,
    val createdAt: Long,
    val total: Int,
    val done: Int,
    val failed: Int,
    val paused: Int,
    val pending: Int,
    val active: Int
)

@Dao
interface AppDao {
    @Insert suspend fun insertJob(job: JobEntity): Long
    @Insert suspend fun insertItems(items: List<ItemEntity>)

    @Query("SELECT sourceKey FROM items WHERE platform = :platform AND status = 'DONE'")
    suspend fun doneKeys(platform: String): List<String>

    @Query("SELECT * FROM items WHERE status = 'PENDING' ORDER BY id LIMIT :n")
    suspend fun nextPending(n: Int): List<ItemEntity>

    @Query("UPDATE items SET status = :status, error = NULL WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("SELECT status FROM items WHERE id = :id")
    suspend fun statusOf(id: Long): String?

    @Query("UPDATE items SET bytesDone = :done, bytesTotal = :total WHERE id = :id")
    suspend fun progress(id: Long, done: Long, total: Long)

    @Query("UPDATE items SET status = 'DONE', location = :location, savedName = :name, sizeBytes = :size, bytesDone = :size, error = NULL, finishedAt = :at WHERE id = :id")
    suspend fun markDone(id: Long, location: String, name: String, size: Long, at: Long)

    @Query("UPDATE items SET status = 'FAILED', error = :error, finishedAt = :at WHERE id = :id")
    suspend fun markFailed(id: Long, error: String, at: Long)

    @Query("UPDATE items SET status = 'PENDING' WHERE status = 'DOWNLOADING'")
    suspend fun resetInterrupted()

    @Query("UPDATE items SET status = 'PAUSED' WHERE jobId = :jobId AND status IN ('PENDING','DOWNLOADING')")
    suspend fun pauseJob(jobId: Long)

    @Query("UPDATE items SET status = 'PENDING' WHERE jobId = :jobId AND status = 'PAUSED'")
    suspend fun resumeJob(jobId: Long)

    @Query("UPDATE items SET status = 'PENDING', error = NULL, bytesDone = 0, bytesTotal = 0 WHERE jobId = :jobId AND status = 'FAILED'")
    suspend fun retryFailed(jobId: Long)

    @Query("UPDATE items SET status = 'PENDING', error = NULL, bytesDone = 0, bytesTotal = 0 WHERE id = :id")
    suspend fun retryItem(id: Long)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun deleteItem(id: Long)

    @Query("DELETE FROM items WHERE jobId = :jobId")
    suspend fun deleteItemsOfJob(jobId: Long)

    @Query("DELETE FROM jobs WHERE id = :jobId")
    suspend fun deleteJobRow(jobId: Long)

    @Query("SELECT COUNT(*) FROM items WHERE status IN ('PENDING','DOWNLOADING')")
    suspend fun openCount(): Int

    @Query(
        """SELECT j.id AS id, j.platform AS platform, j.title AS title, j.type AS type,
        j.sourceUrl AS sourceUrl, j.createdAt AS createdAt,
        COUNT(i.id) AS total,
        COALESCE(SUM(CASE WHEN i.status = 'DONE' THEN 1 ELSE 0 END), 0) AS done,
        COALESCE(SUM(CASE WHEN i.status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed,
        COALESCE(SUM(CASE WHEN i.status = 'PAUSED' THEN 1 ELSE 0 END), 0) AS paused,
        COALESCE(SUM(CASE WHEN i.status IN ('PENDING','DOWNLOADING') THEN 1 ELSE 0 END), 0) AS pending,
        COALESCE(SUM(CASE WHEN i.status = 'DOWNLOADING' THEN 1 ELSE 0 END), 0) AS active
        FROM jobs j LEFT JOIN items i ON i.jobId = j.id
        GROUP BY j.id HAVING COUNT(i.id) > 0 ORDER BY j.createdAt DESC"""
    )
    fun jobStats(): Flow<List<JobStats>>

    @Query("SELECT * FROM items WHERE status = 'FAILED' ORDER BY id")
    fun failedItems(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE status IN ('DONE','FAILED') ORDER BY COALESCE(finishedAt, createdAt) DESC LIMIT 2000")
    fun history(): Flow<List<ItemEntity>>
}

@Database(entities = [JobEntity::class, ItemEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
}

package com.gonzalowebdev.mediadownloader.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import com.gonzalowebdev.mediadownloader.data.Prefs
import com.gonzalowebdev.mediadownloader.ui.MainActivity

object Notifs {
    private const val CH_PROGRESS = "progress"
    private const val CH_DONE = "done"
    private const val ID_PROGRESS = 1001
    private const val ID_DONE = 1002

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Descargas en curso", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Descargas finalizadas", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun foreground(ctx: Context, text: String, progress: Int, max: Int): ForegroundInfo {
        val n = NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Media Downloader Hub")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(max, progress, max == 0)
            .setContentIntent(openIntent(ctx))
            .build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(ID_PROGRESS, n)
    }

    fun finished(ctx: Context, prefs: Prefs, done: Int, failed: Int) {
        if (!prefs.notifications) return
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val text = if (failed == 0) "$done archivos descargados" else "$done descargados · $failed con error"
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Descargas finalizadas")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openIntent(ctx))
            .build()
        try { nm.notify(ID_DONE, n) } catch (e: SecurityException) { /* sin permiso */ }
    }
}

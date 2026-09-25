package com.cris.doamodgallery.worker

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cris.doamodgallery.DoaGalleryApp
import com.cris.doamodgallery.data.GalleryRepository
import com.cris.doamodgallery.data.OfflineMediaStore
import kotlinx.coroutines.delay

class OfflineThumbnailWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val items = GalleryRepository(applicationContext).current()
        if (items.isEmpty()) return Result.success()

        OfflineMediaStore.reportThumbnailProgress(0, items.size)
        setForeground(foreground(0, items.size))

        return try {
            var done = 0
            for (item in items) {
                if (isStopped) return Result.failure()
                runCatching { OfflineMediaStore.ensureThumbnail(applicationContext, item) }
                done += 1
                OfflineMediaStore.reportThumbnailProgress(done, items.size)

                if (done == 1 || done % 8 == 0 || done == items.size) {
                    val pct = ((done * 100L) / items.size.coerceAtLeast(1)).toInt()
                    setProgress(Data.Builder().putInt("progress", pct).putInt("done", done).putInt("total", items.size).build())
                    setForeground(foreground(done, items.size))
                }

                // Keep CPU/network pressure low so the gallery and the rest of the phone stay responsive.
                if (done % 12 == 0) delay(90)
            }

            OfflineMediaStore.cleanupStaleThumbnails(applicationContext, items)
            notifyFinished(items.size)
            Result.success(Data.Builder().putInt("total", items.size).build())
        } catch (e: Exception) {
            Result.failure(Data.Builder().putString("error", e.message ?: e.javaClass.simpleName).build())
        }
    }

    private fun foreground(done: Int, total: Int): ForegroundInfo {
        val pct = ((done * 100L) / total.coerceAtLeast(1)).toInt().coerceIn(0, 100)
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification: Notification = NotificationCompat.Builder(applicationContext, DoaGalleryApp.DOWNLOAD_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("DOA Mod Gallery · biblioteca offline")
            .setContentText("Miniaturas $done/$total · $pct%")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, pct, false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancelIntent)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun notifyFinished(total: Int) {
        val notification = NotificationCompat.Builder(applicationContext, DoaGalleryApp.DOWNLOAD_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Biblioteca offline lista")
            .setContentText("$total miniaturas procesadas. Ya puedes abrir la galería con menos red.")
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(COMPLETE_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NOTIFICATION_ID = 4210
        private const val COMPLETE_NOTIFICATION_ID = 4211
    }
}

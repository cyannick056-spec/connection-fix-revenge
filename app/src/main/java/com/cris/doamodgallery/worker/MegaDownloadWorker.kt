package com.cris.doamodgallery.worker

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cris.doamodgallery.DoaGalleryApp
import com.cris.doamodgallery.data.DownloadDestinationStore
import com.cris.doamodgallery.data.MegaIndexRepository
import com.cris.doamodgallery.data.MegaPublicFolderClient

class MegaDownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val handle = inputData.getString(KEY_HANDLE).orEmpty()
        val name = inputData.getString(KEY_NAME).orEmpty()
        val key = decode(inputData.getString(KEY_FILE_KEY).orEmpty())
        val iv = decode(inputData.getString(KEY_IV).orEmpty())
        if (handle.isBlank() || name.isBlank() || key.size < 16 || iv.size < 16) {
            return Result.failure(Data.Builder().putString("error", "Datos de descarga incompletos").build())
        }

        setForeground(foreground(name, 0))
        return try {
            val target = DownloadDestinationStore(applicationContext).create(name)
            MegaPublicFolderClient().downloadTo(
                MegaIndexRepository.MEGA_FOLDER_URL,
                handle,
                key,
                iv,
                target.output
            ) { pct, done, total ->
                if (isStopped) throw InterruptedException("Descarga cancelada")
                setProgressAsync(
                    Data.Builder()
                        .putInt("progress", pct)
                        .putLong("done", done)
                        .putLong("total", total)
                        .build()
                )
                setForegroundAsync(foreground(name, pct))
            }

            notifyFinished(name, target.displayLocation)
            Result.success(Data.Builder().putString("location", target.displayLocation).build())
        } catch (e: Exception) {
            if (!isStopped) notifyFailed(name, e.message ?: "No se pudo descargar")
            Result.failure(Data.Builder().putString("error", e.message ?: e.javaClass.simpleName).build())
        }
    }

    private fun foreground(name: String, pct: Int): ForegroundInfo {
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification: Notification = NotificationCompat.Builder(applicationContext, DoaGalleryApp.DOWNLOAD_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("DOA Mod Gallery · descargando")
            .setContentText("$name · $pct%")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, pct.coerceIn(0, 100), false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancelIntent)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun notifyFinished(name: String, location: String) {
        val notification = NotificationCompat.Builder(applicationContext, DoaGalleryApp.DOWNLOAD_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Descarga terminada")
            .setContentText("$name · $location")
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(COMPLETE_NOTIFICATION_ID + (name.hashCode() and 0x3ff), notification)
    }

    private fun notifyFailed(name: String, error: String) {
        val notification = NotificationCompat.Builder(applicationContext, DoaGalleryApp.DOWNLOAD_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Error descargando mod")
            .setContentText("$name · $error")
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(ERROR_NOTIFICATION_ID + (name.hashCode() and 0x3ff), notification)
    }

    private fun decode(v: String): ByteArray = runCatching {
        Base64.decode(v, Base64.DEFAULT)
    }.getOrDefault(byteArrayOf())

    companion object {
        const val KEY_HANDLE = "handle"
        const val KEY_NAME = "name"
        const val KEY_FILE_KEY = "file_key"
        const val KEY_IV = "iv"
        private const val NOTIFICATION_ID = 4107
        private const val COMPLETE_NOTIFICATION_ID = 5200
        private const val ERROR_NOTIFICATION_ID = 6300
    }
}

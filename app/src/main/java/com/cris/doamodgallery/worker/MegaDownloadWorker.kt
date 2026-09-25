package com.cris.doamodgallery.worker

import android.app.Notification
import android.content.Context
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
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
        if (handle.isBlank() || name.isBlank() || key.size < 16 || iv.size < 16) return Result.failure(Data.Builder().putString("error", "Datos de descarga incompletos").build())
        setForeground(foreground(name, 0))
        return try {
            val target = DownloadDestinationStore(applicationContext).create(name)
            MegaPublicFolderClient().downloadTo(MegaIndexRepository.MEGA_FOLDER_URL, handle, key, iv, target.output) { pct, done, total ->
                setProgressAsync(Data.Builder().putInt("progress", pct).putLong("done", done).putLong("total", total).build())
                setForegroundAsync(foreground(name, pct))
            }
            Result.success(Data.Builder().putString("location", target.displayLocation).build())
        } catch (e: Exception) {
            Result.failure(Data.Builder().putString("error", e.message ?: e.javaClass.simpleName).build())
        }
    }
    private fun foreground(name: String, pct: Int): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, DoaGalleryApp.DOWNLOAD_CHANNEL)
            .setSmallIcon(com.cris.doamodgallery.R.drawable.ic_launcher).setContentTitle("Descargando mod").setContentText(name)
            .setOnlyAlertOnce(true).setOngoing(pct < 100).setProgress(100, pct, false).build()
        return ForegroundInfo(4107, notification)
    }
    private fun decode(v: String): ByteArray = runCatching { Base64.decode(v, Base64.DEFAULT) }.getOrDefault(byteArrayOf())
    companion object { const val KEY_HANDLE="handle"; const val KEY_NAME="name"; const val KEY_FILE_KEY="file_key"; const val KEY_IV="iv" }
}

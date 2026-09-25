package com.cris.doamodgallery.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.cris.doamodgallery.worker.OfflineThumbnailWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object OfflineMediaStore {
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"
    const val THUMB_WORK_NAME = "doa_offline_thumbnail_library"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    @Volatile private var lastContext: Context? = null
    @Volatile private var lastDone: Int = 0
    @Volatile private var lastTotal: Int = 0

    /**
     * Enqueue a persistent background job instead of starting thousands of coroutines in
     * the application process. WorkManager keeps it alive safely when the app goes to the
     * background and the worker exposes progress through an Android notification.
     */
    fun scheduleThumbnails(context: Context, items: List<ModItem>) {
        if (items.isEmpty()) return
        val app = context.applicationContext
        lastContext = app
        lastDone = 0
        lastTotal = items.size

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<OfflineThumbnailWorker>()
            .setConstraints(constraints)
            .addTag(THUMB_WORK_NAME)
            .build()

        WorkManager.getInstance(app).enqueueUniqueWork(
            THUMB_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancelThumbnailSync() {
        lastContext?.let { WorkManager.getInstance(it).cancelUniqueWork(THUMB_WORK_NAME) }
    }

    fun isThumbnailSyncRunning(): Boolean {
        val context = lastContext ?: return false
        return runCatching {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(THUMB_WORK_NAME)
                .get(2, TimeUnit.SECONDS)
                .any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        }.getOrDefault(false)
    }

    fun thumbnailProgress(): Pair<Int, Int> = lastDone to lastTotal

    fun reportThumbnailProgress(done: Int, total: Int) {
        lastDone = done
        lastTotal = total
    }

    fun thumbnailFile(context: Context, item: ModItem): File? {
        val url = item.previewUrl.ifBlank { item.hdUrl }
        if (url.isBlank()) return null
        val file = File(thumbDir(context), mediaName(item.id, url))
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    fun hdFile(context: Context, item: ModItem): File? {
        val url = item.hdUrl.ifBlank { item.previewUrl }
        if (url.isBlank()) return null
        val file = File(hdDir(context), mediaName(item.id, url))
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    suspend fun ensureThumbnail(context: Context, item: ModItem): File? = withContext(Dispatchers.IO) {
        val url = item.previewUrl.ifBlank { item.hdUrl }
        if (url.isBlank()) return@withContext null
        val dir = thumbDir(context)
        dir.mkdirs()
        val file = File(dir, mediaName(item.id, url))
        if (file.isFile && file.length() > 0L) return@withContext file
        if (download(url, file)) file else null
    }

    suspend fun ensureHd(context: Context, item: ModItem): File? = withContext(Dispatchers.IO) {
        val url = item.hdUrl.ifBlank { item.previewUrl }
        if (url.isBlank()) return@withContext null
        val dir = hdDir(context)
        dir.mkdirs()
        val file = File(dir, mediaName(item.id, url))
        if (file.isFile && file.length() > 0L) return@withContext file
        if (download(url, file)) file else null
    }

    fun cleanupStaleThumbnails(context: Context, items: List<ModItem>) {
        val liveNames = items.mapNotNull { item ->
            val url = item.previewUrl.ifBlank { item.hdUrl }
            url.takeIf { it.isNotBlank() }?.let { mediaName(item.id, it) }
        }.toHashSet()
        thumbDir(context).listFiles()?.forEach { file ->
            if (file.isFile && file.name !in liveNames && !file.name.endsWith(".part")) {
                runCatching { file.delete() }
            }
        }
    }

    fun storageBytes(context: Context): Long = dirSize(thumbDir(context)) + dirSize(hdDir(context))
    fun thumbnailBytes(context: Context): Long = dirSize(thumbDir(context))
    fun hdBytes(context: Context): Long = dirSize(hdDir(context))
    fun thumbnailCount(context: Context): Int = thumbDir(context).listFiles()?.count { it.isFile && !it.name.endsWith(".part") } ?: 0
    fun hdCount(context: Context): Int = hdDir(context).listFiles()?.count { it.isFile && !it.name.endsWith(".part") } ?: 0

    fun clearThumbnails(context: Context) {
        lastContext = context.applicationContext
        cancelThumbnailSync()
        thumbDir(context).deleteRecursively()
        lastDone = 0
        lastTotal = 0
    }

    fun clearHd(context: Context) {
        hdDir(context).deleteRecursively()
    }

    private fun download(url: String, dest: File): Boolean {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        runCatching { if (tmp.exists()) tmp.delete() }
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                tmp.outputStream().buffered().use { output ->
                    body.byteStream().buffered().use { input -> input.copyTo(output) }
                }
            }
            if (!tmp.exists() || tmp.length() <= 0L) {
                tmp.delete()
                false
            } else {
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                dest.isFile && dest.length() > 0L
            }
        } catch (_: Exception) {
            runCatching { tmp.delete() }
            false
        }
    }

    private fun mediaName(id: String, url: String): String = sha256("$id\n$url") + ".img"

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun root(context: Context): File = File(context.applicationContext.filesDir, "gallery_media")
    private fun thumbDir(context: Context): File = File(root(context), "thumbs")
    private fun hdDir(context: Context): File = File(root(context), "hd")
    private fun dirSize(dir: File): Long = if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

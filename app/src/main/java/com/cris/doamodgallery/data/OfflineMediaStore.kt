package com.cris.doamodgallery.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object OfflineMediaStore {
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"
    private const val BATCH_SIZE = 64
    private const val THUMB_CONCURRENCY = 4

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncLock = Any()
    @Volatile private var thumbSyncJob: Job? = null

    fun scheduleThumbnails(context: Context, items: List<ModItem>) {
        if (items.isEmpty()) return
        val app = context.applicationContext
        synchronized(syncLock) {
            thumbSyncJob?.cancel()
            thumbSyncJob = scope.launch {
                syncThumbnails(app, items)
            }
        }
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

    suspend fun ensureHd(context: Context, item: ModItem): File? = withContext(Dispatchers.IO) {
        val url = item.hdUrl.ifBlank { item.previewUrl }
        if (url.isBlank()) return@withContext null
        val dir = hdDir(context)
        dir.mkdirs()
        val file = File(dir, mediaName(item.id, url))
        if (file.isFile && file.length() > 0L) return@withContext file
        if (download(url, file)) file else null
    }

    fun storageBytes(context: Context): Long =
        dirSize(thumbDir(context)) + dirSize(hdDir(context))

    fun thumbnailBytes(context: Context): Long = dirSize(thumbDir(context))
    fun hdBytes(context: Context): Long = dirSize(hdDir(context))

    fun clearThumbnails(context: Context) {
        thumbDir(context).deleteRecursively()
    }

    fun clearHd(context: Context) {
        hdDir(context).deleteRecursively()
    }

    private suspend fun syncThumbnails(context: Context, items: List<ModItem>) {
        val dir = thumbDir(context)
        dir.mkdirs()

        val liveNames = items.mapNotNull { item ->
            val url = item.previewUrl.ifBlank { item.hdUrl }
            url.takeIf { it.isNotBlank() }?.let { mediaName(item.id, it) }
        }.toHashSet()

        val semaphore = Semaphore(THUMB_CONCURRENCY)
        for (batch in items.chunked(BATCH_SIZE)) {
            coroutineScope {
                batch.map { item ->
                    launch {
                        semaphore.withPermit {
                            val url = item.previewUrl.ifBlank { item.hdUrl }
                            if (url.isBlank()) return@withPermit
                            val dest = File(dir, mediaName(item.id, url))
                            if (!dest.isFile || dest.length() <= 0L) {
                                runCatching { download(url, dest) }
                            }
                        }
                    }
                }.joinAll()
            }
        }

        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in liveNames && !file.name.endsWith(".part")) {
                runCatching { file.delete() }
            }
        }
    }

    private fun download(url: String, dest: File): Boolean {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        runCatching { if (tmp.exists()) tmp.delete() }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

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

    private fun dirSize(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

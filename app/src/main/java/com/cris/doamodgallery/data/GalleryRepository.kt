package com.cris.doamodgallery.data

import android.content.Context
import com.cris.doamodgallery.util.JsonFileStore
import com.cris.doamodgallery.util.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class GalleryRepository(context: Context) {
    private val store = JsonFileStore(context)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var cache: AppCache = store.read("gallery.json", AppCache::class.java, AppCache())

    fun current(): List<ModItem> = cache.items

    fun save(items: List<ModItem>) {
        cache = AppCache(items, System.currentTimeMillis())
        store.write("gallery.json", cache)
    }

    fun updateOne(item: ModItem) {
        save(cache.items.map { if (it.id == item.id) item else it })
    }

    /**
     * Cheap local migration for old caches. v0.2.3 had correct 3379 entries but the
     * character parser was intentionally too strict, so the spinner only exposed a few
     * names. Rebuild the visible title + target character without re-downloading anything.
     */
    fun reindexLocalMetadata(): Boolean {
        val source = cache.items
        if (source.isEmpty()) return false

        var changed = false
        val updated = source.map { item ->
            val rawTitle = TextUtils.cleanPostTitle(item.title)
            val pretty = if (
                rawTitle.isBlank() ||
                rawTitle == "Sin título verificado" ||
                TextUtils.looksLikePostId(rawTitle)
            ) {
                "Sin título verificado"
            } else {
                TextUtils.prettyTitle(rawTitle)
            }
            val character = TextUtils.detectCharacter(pretty)

            if (pretty != item.title || character != item.character) {
                changed = true
                item.copy(title = pretty, character = character)
            } else item
        }

        if (changed) save(updated)
        return changed
    }

    suspend fun resolveHd(item: ModItem): ModItem = withContext(Dispatchers.IO) {
        if (item.hdUrl.isNotBlank()) return@withContext item
        val request = Request.Builder().url(item.pageUrl).header("User-Agent", UA).build()
        val html = http.newCall(request).execute().use { it.body?.string().orEmpty() }
        if (html.isBlank()) return@withContext item
        val doc = Jsoup.parse(html, item.pageUrl)
        val candidates = listOfNotNull(
            doc.selectFirst("meta[property=og:image]")?.attr("abs:content")?.takeIf { it.isNotBlank() },
            doc.selectFirst("link[rel=image_src]")?.attr("abs:href")?.takeIf { it.isNotBlank() }
        ) + doc.select("img").mapNotNull { el ->
            (el.attr("abs:src").ifBlank { el.attr("abs:data-src") }).takeIf { it.contains("postimg") }
        }
        val hd = candidates.firstOrNull { it.contains("i.postimg.cc") }
            ?: candidates.firstOrNull().orEmpty()
        val updated = item.copy(hdUrl = hd.ifBlank { item.previewUrl })
        updateOne(updated)
        updated
    }

    suspend fun enrichAllHd(onProgress: (Int, Int) -> Unit): List<ModItem> = withContext(Dispatchers.IO) {
        val source = cache.items
        if (source.isEmpty()) return@withContext source
        val semaphore = Semaphore(6)
        val out = source.toMutableList()
        coroutineScope {
            source.forEachIndexed { index, item ->
                launch {
                    semaphore.withPermit {
                        val updated = runCatching { resolveHdNoSave(item) }.getOrDefault(item)
                        synchronized(out) { out[index] = updated }
                        onProgress(index + 1, source.size)
                    }
                }
            }
        }
        save(out)
        out
    }

    private fun resolveHdNoSave(item: ModItem): ModItem {
        if (item.hdUrl.isNotBlank()) return item
        val req = Request.Builder().url(item.pageUrl).header("User-Agent", UA).build()
        val html = http.newCall(req).execute().use { it.body?.string().orEmpty() }
        if (html.isBlank()) return item
        val doc = Jsoup.parse(html, item.pageUrl)
        val candidates = mutableListOf<String>()
        doc.selectFirst("meta[property=og:image]")?.attr("abs:content")
            ?.takeIf { it.isNotBlank() }?.let(candidates::add)
        doc.selectFirst("link[rel=image_src]")?.attr("abs:href")
            ?.takeIf { it.isNotBlank() }?.let(candidates::add)
        doc.select("img").forEach { el ->
            val u = el.attr("abs:src").ifBlank { el.attr("abs:data-src") }
            if (u.contains("postimg")) candidates.add(u)
        }
        val hd = candidates.firstOrNull { it.contains("i.postimg.cc") }
            ?: candidates.firstOrNull().orEmpty()
        return item.copy(hdUrl = hd.ifBlank { item.previewUrl })
    }

    fun attachMega(matches: Map<String, MegaFile>): List<ModItem> {
        val updated = cache.items.map { item ->
            val f = matches[item.id]
            if (f == null) {
                item.copy(
                    megaName = "",
                    megaHandle = "",
                    megaKeyBase64 = "",
                    megaIvBase64 = "",
                    megaSize = 0L,
                    megaScore = 0.0
                )
            } else {
                item.copy(
                    megaName = f.name,
                    megaHandle = f.handle,
                    megaKeyBase64 = f.fileKeyBase64,
                    megaIvBase64 = f.ivBase64,
                    megaSize = f.size
                )
            }
        }
        save(updated)
        return updated
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"
    }
}

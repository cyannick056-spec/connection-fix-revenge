package com.cris.doamodgallery.scanner

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import com.cris.doamodgallery.data.ModItem
import com.cris.doamodgallery.util.TextUtils
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class PostimagesGalleryScanner(private val activity: Activity, private val host: ViewGroup) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun scan(onProgress: (Int, String) -> Unit): List<ModItem> = withContext(Dispatchers.Main) {
        val web = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = DESKTOP_UA
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 0.01f
            isClickable = false
            isFocusable = false
        }
        host.addView(web, ViewGroup.LayoutParams(2, 2))
        val loaded = CompletableDeferred<Unit>()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!loaded.isCompleted) loaded.complete(Unit)
            }
        }

        try {
            onProgress(0, "Abriendo galería completa de Postimages…")
            web.loadUrl(GALLERY_URL)
            withTimeout(45_000) { loaded.await() }
            delay(1800)

            val best = linkedMapOf<String, Row>()
            var stable = 0
            var lastCount = -1
            var rounds = 0

            while (stable < 70 && rounds < 2200) {
                rounds++
                val json = eval(web, EXTRACT_JS)
                val array = runCatching { JSONArray(json) }.getOrElse { JSONArray() }

                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val href = o.optString("href").trimEnd('/')
                    if (!href.matches(Regex("https://postimg\\.cc/[A-Za-z0-9]+"))) continue

                    val incoming = Row(
                        href = href,
                        src = o.optString("src"),
                        title = TextUtils.cleanPostTitle(o.optString("title"))
                    )
                    val old = best[href]
                    best[href] = if (old == null) incoming else old.merge(incoming)
                }

                val count = best.size
                stable = if (count == lastCount) stable + 1 else 0
                lastCount = count
                val pct = (4 + ((rounds.coerceAtMost(420) / 420.0) * 74)).toInt().coerceAtMost(78)
                onProgress(pct, "Escaneando galería… $count skins detectadas")

                eval(web, SCROLL_JS)
                delay(if (stable < 4) 120 else 360)
            }

            onProgress(80, "Corrigiendo nombres y previews…")
            val rows = best.values.toList()
            val done = AtomicInteger(0)
            val semaphore = Semaphore(8)

            val resolved = withContext(Dispatchers.IO) {
                coroutineScope {
                    rows.map { row ->
                        async {
                            semaphore.withPermit {
                                val fixed = if (row.needsMetadataRepair()) {
                                    runCatching { resolveMetadata(row) }.getOrDefault(row)
                                } else row

                                val n = done.incrementAndGet()
                                if (n == 1 || n == rows.size || n % 25 == 0) {
                                    val pct = 80 + ((n * 19L) / rows.size.coerceAtLeast(1)).toInt()
                                    withContext(Dispatchers.Main) {
                                        onProgress(pct.coerceAtMost(99), "Corrigiendo nombres… $n/${rows.size}")
                                    }
                                }
                                fixed
                            }
                        }
                    }.awaitAll()
                }
            }

            val result = resolved.map { r ->
                val fallback = r.href.substringAfterLast('/')
                val title = TextUtils.cleanPostTitle(r.title).ifBlank { fallback }
                ModItem(
                    id = r.href,
                    title = title,
                    pageUrl = r.href,
                    previewUrl = r.src,
                    character = TextUtils.detectCharacter(title)
                )
            }.distinctBy { it.id }
                .sortedBy { it.title.lowercase() }

            onProgress(100, "Galería lista: ${result.size} skins")
            result
        } finally {
            host.removeView(web)
            web.stopLoading()
            web.destroy()
        }
    }

    private fun resolveMetadata(row: Row): Row {
        val req = Request.Builder().url(row.href).header("User-Agent", DESKTOP_UA).build()
        val html = http.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return row
            response.body?.string().orEmpty()
        }
        if (html.isBlank()) return row

        val doc = Jsoup.parse(html, row.href)
        val title = listOfNotNull(
            doc.selectFirst("meta[property=og:title]")?.attr("content"),
            doc.selectFirst("meta[name=twitter:title]")?.attr("content"),
            doc.title()
        ).map { TextUtils.cleanPostTitle(it) }
            .firstOrNull { it.isNotBlank() && !TextUtils.looksLikePostId(it) }
            .orEmpty()

        val src = listOfNotNull(
            doc.selectFirst("meta[property=og:image]")?.attr("abs:content"),
            doc.selectFirst("meta[name=twitter:image]")?.attr("abs:content"),
            doc.selectFirst("link[rel=image_src]")?.attr("abs:href")
        ).firstOrNull { it.contains("i.postimg.cc") }
            .orEmpty()

        return row.merge(Row(row.href, src, title))
    }

    private suspend fun eval(web: WebView, script: String): String = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        web.evaluateJavascript(script) { result ->
            val decoded = runCatching { gson.fromJson(result, String::class.java) }.getOrNull() ?: result
            if (cont.isActive) cont.resume(decoded)
        }
    }

    private data class Row(val href: String, val src: String, val title: String) {
        fun merge(other: Row): Row {
            val mergedTitle = when {
                other.title.isNotBlank() && !TextUtils.looksLikePostId(other.title) -> other.title
                title.isNotBlank() -> title
                else -> other.title
            }
            val mergedSrc = when {
                other.src.contains("i.postimg.cc") -> other.src
                src.contains("i.postimg.cc") -> src
                other.src.isNotBlank() -> other.src
                else -> src
            }
            return Row(href, mergedSrc, mergedTitle)
        }

        fun needsMetadataRepair(): Boolean =
            title.isBlank() || TextUtils.looksLikePostId(title) || !src.contains("i.postimg.cc")
    }

    companion object {
        const val GALLERY_URL = "https://postimg.cc/gallery/wtKYrM4"
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:142.0) Gecko/20100101 Firefox/142.0"

        val EXTRACT_JS = """
            (function(){
              const out=[];
              for(const a of document.querySelectorAll('a[href]')){
                const href=(a.href||'').replace(/\/$/,'');
                if(!/^https:\/\/postimg\.cc\/[A-Za-z0-9]+$/.test(href)) continue;

                const img=a.querySelector('img');
                const src=img?(img.currentSrc||img.src||img.dataset.src||img.dataset.original||img.getAttribute('data-lazy-src')||''):'';
                let title=(img?(img.alt||img.title||''):'') || a.getAttribute('title') || (a.innerText||'').trim();

                if(!title){
                  const parent=a.parentElement;
                  if(parent){
                    const label=parent.querySelector('figcaption,.image-title,.gallery-title,.title,[class*="title"]');
                    if(label) title=(label.textContent||'').trim();
                  }
                }

                out.push({href:href,src:src,title:title});
              }
              return JSON.stringify(out);
            })();
        """.trimIndent()

        val SCROLL_JS = """
            (function(){
              const buttons=[...document.querySelectorAll('button,a')].filter(el=>{
                const t=(el.textContent||'').trim().toLowerCase();
                return t==='load more' || t==='show more' || t==='more' || t==='cargar más' || t==='mostrar más';
              });
              buttons.forEach(b=>{try{b.click();}catch(e){}});
              window.scrollTo(0,Math.max(document.body.scrollHeight,document.documentElement.scrollHeight));
              window.dispatchEvent(new Event('scroll'));
              return 'ok';
            })();
        """.trimIndent()
    }
}

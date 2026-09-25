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
        val dm = activity.resources.displayMetrics
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
            translationX = dm.widthPixels * 2f
        }
        host.addView(web, ViewGroup.LayoutParams(dm.widthPixels, dm.heightPixels.coerceAtLeast(1400)))

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
            var stableCycles = 0
            var lastCount = -1
            var cycle = 0

            while (cycle < MAX_LOAD_CYCLES) {
                cycle++
                eval(web, CLICK_MORE_JS)
                delay(120)
                eval(web, "window.scrollTo(0,0);window.dispatchEvent(new Event('scroll'));'ok';")
                delay(120)
                eval(web, SMOOTH_TO_BOTTOM_JS)
                delay(2250)
                eval(web, CLICK_MORE_JS)
                delay(220)
                mergeRows(best, eval(web, EXTRACT_JS))

                var count = best.size
                val domCount = evalInt(web, "document.querySelectorAll('#thumb-list > .col').length")
                val shown = maxOf(count, domCount)
                val loadPct = 5 + ((shown.coerceAtMost(EXPECTED_GALLERY_SIZE) * 66L) / EXPECTED_GALLERY_SIZE).toInt()
                onProgress(loadPct.coerceIn(5, 72), "Forzando galería completa… $shown skins detectadas")

                if (cycle % 5 == 0) {
                    eval(web, SMOOTH_TO_TOP_JS)
                    delay(650)
                    eval(web, SMOOTH_TO_BOTTOM_JS)
                    delay(2250)
                    mergeRows(best, eval(web, EXTRACT_JS))
                    count = best.size
                }

                stableCycles = if (count == lastCount) stableCycles + 1 else 0
                lastCount = count

                if (count >= MIN_COMPLETE_ITEMS && stableCycles >= 6) break

                if (count < MIN_COMPLETE_ITEMS && stableCycles >= 5) {
                    eval(web, NUDGE_JS)
                    delay(1900)
                    mergeRows(best, eval(web, EXTRACT_JS))
                    stableCycles = 0
                }
            }

            if (best.size < MIN_COMPLETE_ITEMS) {
                throw IllegalStateException(
                    "Postimages solo entregó ${best.size} skins; se requieren al menos $MIN_COMPLETE_ITEMS. " +
                        "No guardaré una biblioteca incompleta. Pulsa actualizar de nuevo con conexión estable."
                )
            }

            onProgress(74, "Verificando nombres de ${best.size} skins…")
            val rows = best.values.toList()
            val done = AtomicInteger(0)
            val semaphore = Semaphore(6)

            val resolved = withContext(Dispatchers.IO) {
                coroutineScope {
                    rows.map { row ->
                        async {
                            semaphore.withPermit {
                                val fixed = if (row.needsMetadataRepair()) {
                                    runCatching { resolveMetadata(row) }.getOrDefault(row)
                                } else row

                                val n = done.incrementAndGet()
                                if (n == 1 || n == rows.size || n % 75 == 0) {
                                    val pct = 74 + ((n * 25L) / rows.size.coerceAtLeast(1)).toInt()
                                    withContext(Dispatchers.Main) {
                                        onProgress(pct.coerceAtMost(99), "Verificando títulos… $n/${rows.size}")
                                    }
                                }
                                fixed
                            }
                        }
                    }.awaitAll()
                }
            }

            val result = resolved.map { r ->
                val canonical = TextUtils.cleanPostTitle(r.title).trim()
                val title = canonical
                    .takeIf { it.isNotBlank() && !TextUtils.looksLikePostId(it) }
                    ?.let(TextUtils::prettyTitle)
                    ?: "Sin título verificado"

                val preview = r.preview.ifBlank { r.hd }
                val hd = r.hd.ifBlank { r.preview }

                ModItem(
                    id = r.href,
                    title = title,
                    pageUrl = r.href,
                    previewUrl = preview,
                    hdUrl = hd,
                    character = TextUtils.detectCharacter(title)
                )
            }.distinctBy { it.id }
                .sortedBy { it.title.lowercase() }

            onProgress(100, "Galería completa: ${result.size} skins")
            result
        } finally {
            host.removeView(web)
            web.stopLoading()
            web.destroy()
        }
    }

    private fun mergeRows(best: LinkedHashMap<String, Row>, rawJson: String) {
        val array = runCatching { JSONArray(rawJson) }.getOrElse { JSONArray() }
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val href = o.optString("href").trimEnd('/')
            if (!href.matches(Regex("https://postimg\\.cc/[A-Za-z0-9_-]+"))) continue

            val incoming = Row(
                href = href,
                preview = o.optString("preview"),
                hd = o.optString("hd"),
                title = TextUtils.cleanPostTitle(o.optString("title"))
            )
            val old = best[href]
            best[href] = if (old == null) incoming else old.merge(incoming)
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

        val hd = listOfNotNull(
            doc.selectFirst("meta[property=og:image]")?.attr("abs:content"),
            doc.selectFirst("meta[name=twitter:image]")?.attr("abs:content"),
            doc.selectFirst("link[rel=image_src]")?.attr("abs:href")
        ).firstOrNull { it.contains("i.postimg.cc") }
            .orEmpty()

        return row.merge(Row(row.href, row.preview, hd, title))
    }

    private suspend fun eval(web: WebView, script: String): String = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        web.evaluateJavascript(script) { result ->
            val decoded = runCatching { gson.fromJson(result, String::class.java) }.getOrNull() ?: result
            if (cont.isActive) cont.resume(decoded)
        }
    }

    private suspend fun evalInt(web: WebView, expression: String): Int {
        val raw = eval(web, "String($expression)")
        return raw.trim().trim('"').toIntOrNull() ?: 0
    }

    private data class Row(
        val href: String,
        val preview: String,
        val hd: String,
        val title: String
    ) {
        fun merge(other: Row): Row {
            val mergedTitle = when {
                other.title.isNotBlank() && !TextUtils.looksLikePostId(other.title) -> other.title
                title.isNotBlank() && !TextUtils.looksLikePostId(title) -> title
                other.title.isNotBlank() -> other.title
                else -> title
            }

            val mergedPreview = when {
                other.preview.contains("postimg") -> other.preview
                preview.contains("postimg") -> preview
                other.preview.isNotBlank() -> other.preview
                else -> preview
            }

            val mergedHd = when {
                other.hd.contains("i.postimg.cc") -> other.hd
                hd.contains("i.postimg.cc") -> hd
                other.hd.isNotBlank() -> other.hd
                else -> hd
            }

            return Row(href, mergedPreview, mergedHd, mergedTitle)
        }

        fun needsMetadataRepair(): Boolean =
            title.isBlank() || TextUtils.looksLikePostId(title) || !hd.contains("i.postimg.cc")
    }

    companion object {
        const val GALLERY_URL = "https://postimg.cc/gallery/wtKYrM4"
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:142.0) Gecko/20100101 Firefox/142.0"
        private const val MIN_COMPLETE_ITEMS = 3300
        private const val EXPECTED_GALLERY_SIZE = 3379
        private const val MAX_LOAD_CYCLES = 90

        val EXTRACT_JS = """
            (function(){
              const out=[];
              const cols=[...document.querySelectorAll('#thumb-list > .col')];
              for(const col of cols){
                const key=col.dataset.image||'';
                const hotlink=col.dataset.hotlink||'';
                const name=col.dataset.name||'';
                const ext=col.dataset.ext||'';
                const a=col.querySelector('a[href]');
                const img=col.querySelector('img');
                const href=key ? ('https://postimg.cc/'+key) : ((a&&a.href)||'').replace(/\/$/,'');
                const preview=img?(img.currentSrc||img.src||img.dataset.src||img.dataset.original||''):'';
                const hd=(hotlink&&name&&ext) ? ('https://i.postimg.cc/'+hotlink+'/'+name+'.'+ext) : preview;
                const title=name || (img?(img.alt||img.title||''):'') || (col.innerText||'').trim();
                if(href) out.push({href:href,preview:preview,hd:hd,title:title});
              }
              if(out.length===0){
                for(const a of document.querySelectorAll('a[href^="https://postimg.cc/"]')){
                  const href=(a.href||'').replace(/\/$/,'');
                  if(!/^https:\/\/postimg\.cc\/[A-Za-z0-9_-]+$/.test(href)) continue;
                  const img=a.querySelector('img');
                  const preview=img?(img.currentSrc||img.src||img.dataset.src||img.dataset.original||''):'';
                  const title=(img?(img.alt||img.title||''):'') || a.getAttribute('title') || (a.innerText||'').trim();
                  out.push({href:href,preview:preview,hd:preview,title:title});
                }
              }
              return JSON.stringify(out);
            })();
        """.trimIndent()

        val CLICK_MORE_JS = """
            (function(){
              let clicked=0;
              for(const el of document.querySelectorAll('button,a')){
                const t=((el.innerText||el.textContent||'')+'').trim().toLowerCase();
                if(t.includes('load more')||t.includes('show more')||t.includes('cargar más')||t.includes('mostrar más')){
                  try{ el.click(); clicked++; }catch(e){}
                }
              }
              window.dispatchEvent(new Event('scroll'));
              return String(clicked);
            })();
        """.trimIndent()

        val SMOOTH_TO_BOTTOM_JS = """
            (function(){
              const start=window.pageYOffset;
              const target=Math.max(document.body.scrollHeight,document.documentElement.scrollHeight);
              const distance=target-start;
              const duration=1800;
              const t0=performance.now();
              function step(now){
                const p=Math.min((now-t0)/duration,1);
                const e=p<0.5?4*p*p*p:1-Math.pow(-2*p+2,3)/2;
                window.scrollTo(0,start+distance*e);
                window.dispatchEvent(new Event('scroll'));
                if(p<1) requestAnimationFrame(step);
              }
              requestAnimationFrame(step);
              return 'started';
            })();
        """.trimIndent()

        val SMOOTH_TO_TOP_JS = """
            (function(){
              const start=window.pageYOffset;
              const distance=-start;
              const duration=550;
              const t0=performance.now();
              function step(now){
                const p=Math.min((now-t0)/duration,1);
                window.scrollTo(0,start+distance*p);
                window.dispatchEvent(new Event('scroll'));
                if(p<1) requestAnimationFrame(step);
              }
              requestAnimationFrame(step);
              return 'started';
            })();
        """.trimIndent()

        val NUDGE_JS = """
            (function(){
              const h=Math.max(document.body.scrollHeight,document.documentElement.scrollHeight);
              const v=Math.max(window.innerHeight,800);
              window.scrollTo(0,Math.max(0,h-v*4));
              window.dispatchEvent(new Event('scroll'));
              setTimeout(()=>{window.scrollTo(0,h);window.dispatchEvent(new Event('scroll'));},250);
              setTimeout(()=>{window.scrollBy(0,-v);window.dispatchEvent(new Event('scroll'));},550);
              setTimeout(()=>{window.scrollTo(0,Math.max(document.body.scrollHeight,document.documentElement.scrollHeight));window.dispatchEvent(new Event('scroll'));},900);
              return 'nudged';
            })();
        """.trimIndent()
    }
}

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import kotlin.coroutines.resume

class PostimagesGalleryScanner(private val activity: Activity, private val host: ViewGroup) {
    private val gson = Gson()

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun scan(onProgress: (Int, String) -> Unit): List<ModItem> = withContext(Dispatchers.Main) {
        val web = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 0.01f
        }
        host.addView(web, ViewGroup.LayoutParams(2, 2))
        val loaded = CompletableDeferred<Unit>()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { if (!loaded.isCompleted) loaded.complete(Unit) }
        }
        try {
            onProgress(0, "Abriendo galería de Postimages…")
            web.loadUrl(GALLERY_URL)
            withTimeout(35_000) { loaded.await() }
            delay(1400)
            val best = linkedMapOf<String, Row>()
            var stable = 0; var lastCount = -1; var rounds = 0
            while (stable < 16 && rounds < 1400) {
                rounds++
                val json = eval(web, EXTRACT_JS)
                val array = runCatching { JSONArray(json) }.getOrElse { JSONArray() }
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val href = o.optString("href").trimEnd('/')
                    if (!href.matches(Regex("https://postimg\\.cc/[A-Za-z0-9]+"))) continue
                    val row = Row(href, o.optString("src"), o.optString("title"))
                    val old = best[href]
                    if (old == null || row.score >= old.score) best[href] = row
                }
                val count = best.size
                stable = if (count == lastCount) stable + 1 else 0
                lastCount = count
                val pct = (5 + ((rounds.coerceAtMost(300) / 300.0) * 80)).toInt().coerceAtMost(85)
                onProgress(pct, "Escaneando galería… $count skins detectadas")
                eval(web, "window.scrollTo(0,document.body.scrollHeight);window.dispatchEvent(new Event('scroll'));'ok';")
                delay(if (stable < 3) 115 else 280)
            }
            onProgress(90, "Organizando ${best.size} skins…")
            best.values.map { r ->
                val title = r.title.ifBlank { r.href.substringAfterLast('/') }
                ModItem(id=r.href,title=title,pageUrl=r.href,previewUrl=r.src,character=TextUtils.detectCharacter(title))
            }.sortedBy { it.title.lowercase() }.also { onProgress(100, "Galería lista: ${it.size} skins") }
        } finally {
            host.removeView(web); web.stopLoading(); web.destroy()
        }
    }

    private suspend fun eval(web: WebView, script: String): String = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        web.evaluateJavascript(script) { result ->
            val decoded = runCatching { gson.fromJson(result, String::class.java) }.getOrNull() ?: result
            if (cont.isActive) cont.resume(decoded)
        }
    }

    private data class Row(val href: String, val src: String, val title: String) {
        val score: Int get() = (if (title.isNotBlank()) 2 else 0) + (if (src.contains("i.postimg.cc")) 3 else 0)
    }

    companion object {
        const val GALLERY_URL = "https://postimg.cc/gallery/wtKYrM4"
        val EXTRACT_JS = """
            (function(){
              const out=[];
              for(const a of document.querySelectorAll('a[href^="https://postimg.cc/"]')){
                const href=(a.href||'').replace(/\/$/,'');
                if(!/^https:\/\/postimg\.cc\/[A-Za-z0-9]+$/.test(href)) continue;
                const img=a.querySelector('img');
                const src=img?(img.currentSrc||img.src||img.dataset.src||img.dataset.original||''):'';
                let title=img?(img.alt||img.title||''):'';
                if(!title) title=(a.innerText||'').trim();
                if(!title){const box=a.closest('div'); if(box) title=(box.innerText||'').trim();}
                out.push({href:href,src:src,title:title});
              }
              return JSON.stringify(out);
            })();
        """.trimIndent()
    }
}

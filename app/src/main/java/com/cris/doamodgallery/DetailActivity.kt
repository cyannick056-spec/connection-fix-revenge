package com.cris.doamodgallery

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil.load
import com.cris.doamodgallery.data.FavoritesStore
import com.cris.doamodgallery.data.GalleryRepository
import com.cris.doamodgallery.data.ModItem
import com.cris.doamodgallery.databinding.ActivityDetailBinding
import com.cris.doamodgallery.worker.MegaDownloadWorker
import kotlinx.coroutines.launch

class DetailActivity : AppCompatActivity() {
    private lateinit var b: ActivityDetailBinding
    private lateinit var gallery: GalleryRepository
    private lateinit var favorites: FavoritesStore
    private var item: ModItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(b.root)

        gallery = GalleryRepository(this)
        favorites = FavoritesStore(this)
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        item = gallery.current().firstOrNull { it.id == id }
        if (item == null) {
            finish()
            return
        }

        b.toolbar.setNavigationOnClickListener { finish() }
        b.originalButton.setOnClickListener {
            item?.pageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
        b.favoriteButton.setOnClickListener {
            item?.let { mod ->
                favorites.toggle(mod.id)
                refreshFavorite()
            }
        }
        b.downloadButton.setOnClickListener { startDownload() }

        render(item!!)
        resolveHd()
    }

    override fun onResume() {
        super.onResume()
        refreshFavorite()
    }

    private fun render(mod: ModItem) {
        item = mod
        b.toolbar.title = mod.title
        b.title.text = mod.title
        b.character.text = mod.character
        b.image.load(mod.hdUrl.ifBlank { mod.previewUrl }) {
            crossfade(true)
            placeholder(R.color.surface_2)
            error(R.color.surface_2)
        }
        if (mod.megaHandle.isNotBlank()) {
            b.megaInfo.text = "✅ Descarga localizada en MEGA: ${mod.megaName}"
            b.downloadButton.isEnabled = true
            b.downloadButton.alpha = 1f
        } else {
            b.megaInfo.text = "Sin descarga localizada en MEGA. Sincroniza MEGA desde la pantalla principal."
            b.downloadButton.isEnabled = false
            b.downloadButton.alpha = 0.45f
        }
        refreshFavorite()
    }

    private fun refreshFavorite() {
        val mod = item ?: return
        b.favoriteButton.text = if (favorites.isFavorite(mod.id)) "★ Quitar de favoritos" else "☆ Favorito"
    }

    private fun resolveHd() {
        val mod = item ?: return
        if (mod.hdUrl.isNotBlank()) return
        lifecycleScope.launch {
            runCatching { gallery.resolveHd(mod) }
                .onSuccess { updated -> render(updated) }
        }
    }

    private fun startDownload() {
        val mod = item ?: return
        if (mod.megaHandle.isBlank() || mod.megaKeyBase64.isBlank() || mod.megaIvBase64.isBlank()) return

        val input = Data.Builder()
            .putString(MegaDownloadWorker.KEY_HANDLE, mod.megaHandle)
            .putString(MegaDownloadWorker.KEY_NAME, mod.megaName.ifBlank { "mod.7z" })
            .putString(MegaDownloadWorker.KEY_FILE_KEY, mod.megaKeyBase64)
            .putString(MegaDownloadWorker.KEY_IV, mod.megaIvBase64)
            .build()
        val request = OneTimeWorkRequestBuilder<MegaDownloadWorker>().setInputData(input).build()
        val wm = WorkManager.getInstance(this)
        wm.enqueue(request)

        b.downloadProgress.visibility = View.VISIBLE
        b.downloadButton.isEnabled = false
        b.downloadStatus.text = "Preparando descarga…"

        wm.getWorkInfoByIdLiveData(request.id).observe(this) { info ->
            if (info == null) return@observe
            val pct = info.progress.getInt("progress", 0)
            val done = info.progress.getLong("done", 0L)
            val total = info.progress.getLong("total", 0L)
            b.downloadProgress.progress = pct
            b.downloadStatus.text = when (info.state) {
                WorkInfo.State.ENQUEUED -> "En cola…"
                WorkInfo.State.RUNNING -> "Descargando… $pct% ${formatBytes(done)} / ${formatBytes(total)}"
                WorkInfo.State.SUCCEEDED -> "Descarga terminada · ${info.outputData.getString("location").orEmpty()}"
                WorkInfo.State.FAILED -> "Error: ${info.outputData.getString("error") ?: "No se pudo descargar"}"
                WorkInfo.State.CANCELLED -> "Descarga cancelada"
                WorkInfo.State.BLOCKED -> "Esperando…"
            }
            if (info.state.isFinished) {
                b.downloadButton.isEnabled = true
                if (info.state == WorkInfo.State.SUCCEEDED) b.downloadProgress.progress = 100
            }
        }
    }

    private fun formatBytes(v: Long): String {
        if (v <= 0) return "?"
        val mb = v / (1024.0 * 1024.0)
        return if (mb >= 1.0) "%.1f MB".format(mb) else "%.0f KB".format(v / 1024.0)
    }

    companion object {
        const val EXTRA_ID = "mod_id"
    }
}

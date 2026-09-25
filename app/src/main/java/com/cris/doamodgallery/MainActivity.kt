package com.cris.doamodgallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.cris.doamodgallery.adapter.ModAdapter
import com.cris.doamodgallery.data.DownloadDestinationStore
import com.cris.doamodgallery.data.FavoritesStore
import com.cris.doamodgallery.data.GalleryRepository
import com.cris.doamodgallery.data.MegaIndexRepository
import com.cris.doamodgallery.data.ModItem
import com.cris.doamodgallery.data.SettingsStore
import com.cris.doamodgallery.databinding.ActivityMainBinding
import com.cris.doamodgallery.scanner.PostimagesGalleryScanner
import com.cris.doamodgallery.util.TextUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var gallery: GalleryRepository
    private lateinit var mega: MegaIndexRepository
    private lateinit var favorites: FavoritesStore
    private lateinit var uiSettings: SettingsStore
    private lateinit var adapter: ModAdapter
    private lateinit var grid: GridLayoutManager
    private var showFavorites = false
    private var busy = false
    private var pendingPatchUri: Uri? = null

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            DownloadDestinationStore(this).setTree(uri)
            b.statusText.text = "Carpeta de descargas actualizada."
        }
    }

    private val patchPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingPatchUri = uri
            installPatchApk(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        gallery = GalleryRepository(this)
        mega = MegaIndexRepository(this)
        favorites = FavoritesStore(this)
        uiSettings = SettingsStore(this)

        adapter = ModAdapter(
            onOpen = { openDetail(it) },
            onFavorite = { favorites.toggle(it.id) }
        )

        grid = GridLayoutManager(this, uiSettings.resolvedColumns(resources.configuration.screenWidthDp))
        b.recycler.layoutManager = grid
        b.recycler.adapter = adapter
        adapter.setColumns(grid.spanCount)

        setupSpinner()
        setupUi()
        requestNotificationsIfNeeded()

        if (gallery.current().isEmpty()) refreshGallery(autoMega = true) else render()
    }

    override fun onResume() {
        super.onResume()
        if (::gallery.isInitialized) render()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.canRequestPackageInstalls() &&
            pendingPatchUri != null
        ) {
            val uri = pendingPatchUri
            pendingPatchUri = null
            uri?.let { launchPackageInstaller(it) }
        }
    }

    private fun setupSpinner() {
        val values = listOf(getString(R.string.all_characters)) + TextUtils.characters.distinct().sorted()
        val a = ArrayAdapter(this, R.layout.spinner_item, values)
        a.setDropDownViewResource(R.layout.spinner_item)
        b.characterSpinner.adapter = a
        b.characterSpinner.onItemSelectedListener = SimpleItemSelectedListener { render() }
    }

    private fun setupUi() {
        b.search.doAfterTextChanged { render() }
        b.favoritesButton.setOnClickListener {
            showFavorites = !showFavorites
            b.favoritesButton.text = if (showFavorites) "★" else "☆"
            render()
        }
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> { refreshGallery(autoMega = false); true }
                R.id.action_hd -> { improveHd(); true }
                R.id.action_mega -> { syncMega(); true }
                R.id.action_folder -> { folderPicker.launch(null); true }
                R.id.action_columns -> { showColumnsDialog(); true }
                R.id.action_theme -> { showThemeDialog(); true }
                R.id.action_patch -> { choosePatchApk(); true }
                else -> false
            }
        }
    }

    private fun showColumnsDialog() {
        val labels = arrayOf("Automático (3 móvil / 4 tablet)", "3 columnas", "4 columnas")
        val values = intArrayOf(0, 3, 4)
        val currentIndex = values.indexOf(uiSettings.columnsMode).takeIf { it >= 0 } ?: 1
        MaterialAlertDialogBuilder(this)
            .setTitle("Columnas de la galería")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                uiSettings.columnsMode = values[which]
                applyColumns()
                dialog.dismiss()
            }
            .show()
    }

    private fun applyColumns() {
        val count = uiSettings.resolvedColumns(resources.configuration.screenWidthDp)
        grid.spanCount = count
        adapter.setColumns(count)
        b.statusText.text = "Galería en $count columnas."
    }

    private fun showThemeDialog() {
        val labels = arrayOf("Oscuro", "Claro", "Seguir sistema")
        val values = arrayOf(SettingsStore.THEME_DARK, SettingsStore.THEME_LIGHT, SettingsStore.THEME_SYSTEM)
        val currentIndex = values.indexOf(uiSettings.themeMode).takeIf { it >= 0 } ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle("Tema")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                uiSettings.themeMode = values[which]
                val mode = when (values[which]) {
                    SettingsStore.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    SettingsStore.THEME_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    else -> AppCompatDelegate.MODE_NIGHT_YES
                }
                AppCompatDelegate.setDefaultNightMode(mode)
                dialog.dismiss()
            }
            .show()
    }

    private fun choosePatchApk() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Instalar parche / actualización")
            .setMessage("Selecciona un APK de DOA Mod Gallery Android. Android lo instalará encima de esta versión y conservará tus datos si el APK usa el mismo package y la misma firma.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Elegir APK") { _, _ ->
                patchPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
            }
            .show()
    }

    private fun installPatchApk(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            pendingPatchUri = uri
            MaterialAlertDialogBuilder(this)
                .setTitle("Permitir actualizaciones")
                .setMessage("Android necesita permitir que DOA Mod Gallery instale actualizaciones. Activa «Permitir desde esta fuente» y vuelve a la app.")
                .setNegativeButton("Cancelar") { _, _ -> pendingPatchUri = null }
                .setPositiveButton("Abrir ajuste") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .show()
            return
        }
        pendingPatchUri = null
        launchPackageInstaller(uri)
    }

    private fun launchPackageInstaller(uri: Uri) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }.onFailure {
            Toast.makeText(this, "No pude abrir el instalador: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun render() {
        val q = b.search.text?.toString().orEmpty()
        val selected = b.characterSpinner.selectedItem?.toString().orEmpty()
            .takeUnless { it == getString(R.string.all_characters) }.orEmpty()
        val favs = favorites.all()
        val filtered = gallery.current().filter { item ->
            val text = "${item.title} ${item.character} ${item.megaName}"
            TextUtils.tokenMatch(q, text) &&
                (selected.isBlank() || item.character == selected) &&
                (!showFavorites || item.id in favs)
        }
        adapter.submit(filtered, favs)
        if (!busy) b.statusText.text = "${filtered.size} de ${gallery.current().size} skins"
    }

    private fun refreshGallery(autoMega: Boolean) {
        if (busy) return
        busy = true
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val old = gallery.current().associateBy { it.id }
            try {
                val scanner = PostimagesGalleryScanner(this@MainActivity, b.root)
                val scanned = scanner.scan { pct, msg ->
                    b.progress.progress = pct
                    b.statusText.text = msg
                }
                val merged = scanned.map { fresh ->
                    val prior = old[fresh.id]
                    if (prior == null) fresh else fresh.copy(
                        hdUrl = prior.hdUrl,
                        megaName = prior.megaName,
                        megaHandle = prior.megaHandle,
                        megaKeyBase64 = prior.megaKeyBase64,
                        megaIvBase64 = prior.megaIvBase64,
                        megaSize = prior.megaSize,
                        megaScore = prior.megaScore
                    )
                }
                gallery.save(merged)
                render()
                if (autoMega) syncMegaInternal()
            } catch (e: Exception) {
                b.statusText.text = "Error actualizando galería: ${e.message}"
            } finally {
                busy = false
                b.progress.visibility = View.GONE
                render()
            }
        }
    }

    private fun improveHd() {
        if (busy || gallery.current().isEmpty()) return
        busy = true
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val updated = gallery.enrichAllHd { done, total ->
                    val pct = ((done * 100L) / total.coerceAtLeast(1)).toInt()
                    runOnUiThread {
                        b.progress.progress = pct
                        b.statusText.text = "Mejorando imágenes HD… $done/$total ($pct%)"
                    }
                }
                adapter.submit(updated, favorites.all())
                b.statusText.text = "Imágenes HD actualizadas."
            } catch (e: Exception) {
                b.statusText.text = "Error HD: ${e.message}"
            } finally {
                busy = false
                b.progress.visibility = View.GONE
                render()
            }
        }
    }

    private fun syncMega() {
        if (busy) return
        lifecycleScope.launch { syncMegaInternal() }
    }

    private suspend fun syncMegaInternal() {
        if (gallery.current().isEmpty()) return
        busy = true
        b.progress.visibility = View.VISIBLE
        try {
            mega.sync { pct, msg ->
                runOnUiThread {
                    b.progress.progress = pct.coerceIn(0, 100)
                    b.statusText.text = msg
                }
            }
            b.statusText.text = "Relacionando MEGA con la galería…"
            val matches = mega.match(gallery.current()) { done, total, found ->
                val pct = ((done * 100L) / total.coerceAtLeast(1)).toInt()
                runOnUiThread {
                    b.progress.progress = pct
                    b.statusText.text = "Relacionando MEGA… $done/$total · $found coincidencias"
                }
            }
            gallery.attachMega(matches)
            b.statusText.text = "MEGA listo: ${matches.size} skins con descarga."
            render()
        } catch (e: Exception) {
            b.statusText.text = "Error MEGA: ${e.message}"
        } finally {
            busy = false
            b.progress.visibility = View.GONE
            render()
        }
    }

    private fun openDetail(item: ModItem) {
        startActivity(Intent(this, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_ID, item.id))
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private class SimpleItemSelectedListener(private val action: () -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = action()
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}

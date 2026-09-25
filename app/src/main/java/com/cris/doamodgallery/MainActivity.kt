package com.cris.doamodgallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
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
import com.cris.doamodgallery.data.OfflineMediaStore
import com.cris.doamodgallery.data.SettingsStore
import com.cris.doamodgallery.databinding.ActivityMainBinding
import com.cris.doamodgallery.scanner.PostimagesGalleryScanner
import com.cris.doamodgallery.update.AppUpdater
import com.cris.doamodgallery.util.TextUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var gallery: GalleryRepository
    private lateinit var mega: MegaIndexRepository
    private lateinit var favorites: FavoritesStore
    private lateinit var uiSettings: SettingsStore
    private lateinit var updater: AppUpdater
    private lateinit var adapter: ModAdapter
    private lateinit var grid: GridLayoutManager
    private var showFavorites = false
    private var busy = false
    private var pendingUpdatePath: String? = null
    private var searchJob: Job? = null
    private var renderJob: Job? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        gallery = GalleryRepository(this)
        mega = MegaIndexRepository(this)
        favorites = FavoritesStore(this)
        uiSettings = SettingsStore(this)
        updater = AppUpdater(this)

        adapter = ModAdapter(
            onOpen = { openDetail(it) },
            onFavorite = { favorites.toggle(it.id) }
        )

        grid = GridLayoutManager(this, uiSettings.resolvedColumns(resources.configuration.screenWidthDp))
        b.recycler.layoutManager = grid
        b.recycler.adapter = adapter
        b.recycler.setHasFixedSize(true)
        b.recycler.setItemViewCacheSize(9)
        b.recycler.itemAnimator = null
        adapter.setColumns(grid.spanCount)

        setupSpinner()
        setupUi()
        requestNotificationsIfNeeded()

        val cached = gallery.current()

        // If a saved library exists, show it immediately. Never force a network scan at startup.
        if (cached.isNotEmpty()) {
            render()
            b.statusText.text = "${cached.size} skins · biblioteca local"

            lifecycleScope.launch {
                val changed = withContext(Dispatchers.IO) {
                    gallery.reindexLocalMetadata()
                }
                if (changed) {
                    refreshCharacterSpinner()
                    render()
                }
            }
        } else {
            // First installation only: there is no local library yet.
            refreshGallery(autoMega = false)
        }

        lifecycleScope.launch {
            delay(3500)
            checkForAppUpdate(forceMessage = false)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::gallery.isInitialized) render()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.canRequestPackageInstalls() &&
            pendingUpdatePath != null
        ) {
            val file = File(pendingUpdatePath!!)
            pendingUpdatePath = null
            if (file.exists()) launchDownloadedInstaller(file)
        }
    }

    private fun setupSpinner() {
        refreshCharacterSpinner()
        b.characterSpinner.setOnTouchListener { _, _ ->
            b.search.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(b.search.windowToken, 0)
            false
        }
        b.characterSpinner.onItemSelectedListener = SimpleItemSelectedListener { render() }
    }

    private fun refreshCharacterSpinner() {
        val previous = b.characterSpinner.selectedItem?.toString().orEmpty()
        val realCharacters = gallery.current()
            .map { it.character.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedBy { it.lowercase() }

        val values = listOf(getString(R.string.all_characters)) + realCharacters
        val a = ArrayAdapter(this, R.layout.spinner_item, values)
        a.setDropDownViewResource(R.layout.spinner_item)
        b.characterSpinner.adapter = a

        val index = values.indexOf(previous).takeIf { it >= 0 } ?: 0
        b.characterSpinner.setSelection(index, false)
    }

    private fun setupUi() {
        b.search.doAfterTextChanged {
            searchJob?.cancel()
            searchJob = lifecycleScope.launch {
                delay(120)
                render()
            }
        }

        b.favoritesButton.setOnClickListener {
            showFavorites = !showFavorites
            b.favoritesButton.text = if (showFavorites) "★" else "☆"
            render()
        }

        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> { refreshGallery(autoMega = true); true }
                R.id.action_offline -> { showOfflineLibraryDialog(); true }
                R.id.action_hd -> { improveHd(); true }
                R.id.action_mega -> { syncMega(); true }
                R.id.action_folder -> { folderPicker.launch(null); true }
                R.id.action_columns -> { showColumnsDialog(); true }
                R.id.action_theme -> { showThemeDialog(); true }
                R.id.action_patch -> { checkForAppUpdate(forceMessage = true); true }
                else -> false
            }
        }
    }

    private fun showOfflineLibraryDialog() {
        lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) {
                OfflineStats(
                    thumbs = OfflineMediaStore.thumbnailCount(this@MainActivity),
                    thumbBytes = OfflineMediaStore.thumbnailBytes(this@MainActivity),
                    hd = OfflineMediaStore.hdCount(this@MainActivity),
                    hdBytes = OfflineMediaStore.hdBytes(this@MainActivity),
                    running = OfflineMediaStore.isThumbnailSyncRunning(),
                    progress = OfflineMediaStore.thumbnailProgress()
                )
            }

            val progressText = if (stats.running) {
                "\nDescarga activa: ${stats.progress.first}/${stats.progress.second}"
            } else ""

            val options = arrayOf(
                "Descargar miniaturas faltantes",
                if (stats.running) "Pausar descarga de miniaturas" else "No hay descarga activa",
                "Limpiar miniaturas",
                "Limpiar imágenes HD"
            )

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Biblioteca offline")
                .setMessage(
                    "Miniaturas: ${stats.thumbs} · ${formatBytes(stats.thumbBytes)}\n" +
                        "HD guardadas: ${stats.hd} · ${formatBytes(stats.hdBytes)}" +
                        progressText +
                        "\n\nLa app ya no descarga miles de imágenes al iniciar."
                )
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            val items = gallery.current()
                            if (items.isEmpty()) {
                                Toast.makeText(this@MainActivity, "Primero actualiza la galería.", Toast.LENGTH_SHORT).show()
                            } else {
                                OfflineMediaStore.scheduleThumbnails(this@MainActivity, items)
                                b.statusText.text = "Miniaturas offline descargándose suavemente en segundo plano."
                                Toast.makeText(
                                    this@MainActivity,
                                    "Descarga iniciada. Puedes seguir usando la app.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        1 -> {
                            if (OfflineMediaStore.isThumbnailSyncRunning()) {
                                OfflineMediaStore.cancelThumbnailSync()
                                b.statusText.text = "Descarga offline pausada. Lo ya descargado se conserva."
                            }
                        }
                        2 -> {
                            lifecycleScope.launch(Dispatchers.IO) {
                                OfflineMediaStore.clearThumbnails(this@MainActivity)
                            }
                            b.statusText.text = "Miniaturas locales limpiadas."
                        }
                        3 -> {
                            lifecycleScope.launch(Dispatchers.IO) {
                                OfflineMediaStore.clearHd(this@MainActivity)
                            }
                            b.statusText.text = "Imágenes HD locales limpiadas."
                        }
                    }
                }
                .setNegativeButton("Cerrar", null)
                .show()
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

    private fun checkForAppUpdate(forceMessage: Boolean) {
        lifecycleScope.launch {
            runCatching { updater.latest() }
                .onSuccess { info ->
                    if (updater.isNewer(info)) {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle("Actualización ${info.versionName}")
                            .setMessage("Hay una versión nueva. La app puede descargar el APK aquí mismo y abrir el instalador de Android.")
                            .setNegativeButton("Ahora no", null)
                            .setPositiveButton("Descargar") { _, _ -> downloadAppUpdate(info) }
                            .show()
                    } else if (forceMessage) {
                        Toast.makeText(this@MainActivity, "Ya tienes la versión más reciente.", Toast.LENGTH_SHORT).show()
                    }
                }
                .onFailure { e ->
                    if (forceMessage) {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle("No pude comprobar actualizaciones")
                            .setMessage(e.message ?: "Error desconocido")
                            .setPositiveButton("Aceptar", null)
                            .show()
                    }
                }
        }
    }

    private fun downloadAppUpdate(info: AppUpdater.UpdateInfo) {
        if (busy) {
            Toast.makeText(this, "Espera a que termine la tarea actual.", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val file = updater.download(info) { pct ->
                    runOnUiThread {
                        b.progress.progress = pct
                        b.statusText.text = "Descargando actualización ${info.versionName}… $pct%"
                    }
                }

                if (!updater.signaturesMatch(file)) {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Firma de actualización diferente")
                        .setMessage(
                            "Android no permite actualizar una app con una firma diferente. " +
                                "Esta protección evita el mensaje genérico de «No se pudo instalar». " +
                                "Instala una vez la versión estable firmada y, desde entonces, las siguientes actualizaciones serán compatibles."
                        )
                        .setPositiveButton("Aceptar", null)
                        .show()
                    b.statusText.text = "La actualización se descargó, pero la firma no coincide."
                    return@launch
                }

                requestInstallDownloaded(file)
            } catch (e: Exception) {
                b.statusText.text = "Error descargando actualización: ${e.message}"
            } finally {
                busy = false
                b.progress.visibility = View.GONE
            }
        }
    }

    private fun requestInstallDownloaded(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            pendingUpdatePath = file.absolutePath
            MaterialAlertDialogBuilder(this)
                .setTitle("Permitir actualizaciones")
                .setMessage("Activa «Permitir desde esta fuente» para que DOA Mod Gallery pueda abrir sus actualizaciones descargadas.")
                .setNegativeButton("Cancelar") { _, _ -> pendingUpdatePath = null }
                .setPositiveButton("Abrir ajuste") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .show()
            return
        }
        launchDownloadedInstaller(file)
    }

    private fun launchDownloadedInstaller(file: File) {
        runCatching {
            val uri = updater.uriFor(file)
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
        val all = gallery.current()
        val q = b.search.text?.toString().orEmpty().trim()
        val selected = b.characterSpinner.selectedItem?.toString().orEmpty()
            .takeUnless { it == getString(R.string.all_characters) }.orEmpty()
        val favs = favorites.all()

        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            val filtered = withContext(Dispatchers.Default) {
                val base = all.asSequence()
                    .filter { selected.isBlank() || it.character == selected }
                    .filter { !showFavorites || it.id in favs }

                if (q.isBlank()) {
                    base.toList()
                } else {
                    base.mapNotNull { item ->
                        val score = TextUtils.searchScore(q, item.title, item.character)
                        if (score >= 0) item to score else null
                    }.sortedWith(
                        compareByDescending<Pair<ModItem, Int>> { it.second }
                            .thenBy { it.first.title.lowercase() }
                    ).map { it.first }
                        .toList()
                }
            }

            adapter.submit(filtered, favs)
            if (!busy) b.statusText.text = "${filtered.size} de ${all.size} skins"
        }
    }

    private fun refreshGallery(autoMega: Boolean) {
        if (busy) return
        busy = true
        b.progress.visibility = View.VISIBLE

        // Keep the already-saved gallery visible during refresh.
        render()

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
                        hdUrl = fresh.hdUrl.ifBlank { prior.hdUrl },
                        megaName = prior.megaName,
                        megaHandle = prior.megaHandle,
                        megaKeyBase64 = prior.megaKeyBase64,
                        megaIvBase64 = prior.megaIvBase64,
                        megaSize = prior.megaSize,
                        megaScore = prior.megaScore
                    )
                }

                // Persist first. AtomicFile keeps the previous valid gallery if Android
                // kills the process or storage temporarily fails.
                withContext(Dispatchers.IO) {
                    gallery.save(merged)
                    gallery.reindexLocalMetadata()
                }

                refreshCharacterSpinner()
                render()
                b.statusText.text = "${gallery.current().size} skins guardadas localmente."

                if (autoMega) syncMegaInternal()
            } catch (e: Exception) {
                b.statusText.text = "Escaneo incompleto: ${e.message}"
                Toast.makeText(
                    this@MainActivity,
                    "Se conservó tu última galería guardada.",
                    Toast.LENGTH_LONG
                ).show()
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
            val megaFiles = mega.sync { pct, msg ->
                runOnUiThread {
                    b.progress.progress = pct.coerceIn(0, 100)
                    b.statusText.text = msg
                }
            }

            b.statusText.text = "MEGA indexado: ${megaFiles.size} archivos. Relacionando…"

            val matches = mega.match(gallery.current()) { done, total, found ->
                val pct = ((done * 100L) / total.coerceAtLeast(1)).toInt()
                runOnUiThread {
                    b.progress.progress = pct
                    b.statusText.text = "Relacionando MEGA… $done/$total · $found coincidencias"
                }
            }

            withContext(Dispatchers.IO) {
                gallery.attachMega(matches)
            }

            b.statusText.text = "MEGA listo: ${megaFiles.size} archivos · ${matches.size} skins con descarga."
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
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun formatBytes(value: Long): String {
        if (value <= 0L) return "0 B"
        val kb = value / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> "%.2f GB".format(gb)
            mb >= 1.0 -> "%.1f MB".format(mb)
            kb >= 1.0 -> "%.0f KB".format(kb)
            else -> "$value B"
        }
    }

    private data class OfflineStats(
        val thumbs: Int,
        val thumbBytes: Long,
        val hd: Int,
        val hdBytes: Long,
        val running: Boolean,
        val progress: Pair<Int, Int>
    )
}

private class SimpleItemSelectedListener(private val action: () -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = action()
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}

package com.cris.doamodgallery.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class DownloadDestinationStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("download_dest", Context.MODE_PRIVATE)
    fun setTree(uri: Uri?) { prefs.edit().putString("tree", uri?.toString().orEmpty()).apply() }
    fun tree(): Uri? = prefs.getString("tree", "")?.takeIf { it.isNotBlank() }?.let(Uri::parse)
    data class Target(val output: OutputStream, val displayLocation: String)

    fun create(fileName: String): Target {
        val custom = tree()
        if (custom != null) {
            val root = DocumentFile.fromTreeUri(context, custom) ?: error("No se pudo abrir la carpeta elegida")
            root.findFile(fileName)?.delete()
            val doc = root.createFile(mime(fileName), fileName) ?: error("No se pudo crear $fileName")
            val out = context.contentResolver.openOutputStream(doc.uri, "w") ?: error("No se pudo escribir $fileName")
            return Target(out, root.name ?: "Carpeta elegida")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime(fileName))
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DOA Mod Gallery")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("No se pudo crear la descarga")
            val out = context.contentResolver.openOutputStream(uri, "w") ?: error("No se pudo abrir la descarga")
            return PendingMediaStoreTarget(context, uri, out).asTarget()
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DOA Mod Gallery")
        dir.mkdirs(); val file = File(dir, fileName); return Target(FileOutputStream(file), file.absolutePath)
    }

    private fun mime(name: String): String = when {
        name.endsWith(".7z", true) -> "application/x-7z-compressed"
        name.endsWith(".zip", true) -> "application/zip"
        name.endsWith(".rar", true) -> "application/vnd.rar"
        else -> "application/octet-stream"
    }

    private class PendingMediaStoreTarget(private val context: Context, private val uri: Uri, private val delegate: OutputStream) {
        fun asTarget(): Target {
            val wrapped = object : OutputStream() {
                override fun write(b: Int) = delegate.write(b)
                override fun write(b: ByteArray) = delegate.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
                override fun flush() = delegate.flush()
                override fun close() {
                    delegate.close()
                    val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    context.contentResolver.update(uri, values, null, null)
                }
            }
            return Target(wrapped, "Descargas/DOA Mod Gallery")
        }
    }
}

package com.cris.doamodgallery.update

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class AppUpdater(private val activity: Activity) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String
    )

    suspend fun latest(): UpdateInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$LATEST_JSON?ts=${System.currentTimeMillis()}")
            .header("Cache-Control", "no-cache")
            .build()
        val text = http.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("Servidor de actualización HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        if (text.isBlank()) error("El servidor no devolvió información de actualización")
        val j = JSONObject(text)
        UpdateInfo(
            versionCode = j.getInt("versionCode"),
            versionName = j.optString("versionName", "desconocida"),
            apkUrl = j.getString("apkUrl")
        )
    }

    fun isNewer(info: UpdateInfo): Boolean = info.versionCode.toLong() > currentVersionCode()

    @Suppress("DEPRECATION")
    private fun currentVersionCode(): Long {
        val pm = activity.packageManager
        val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(activity.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getPackageInfo(activity.packageName, 0)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode else pkg.versionCode.toLong()
    }

    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        val finalFile = File(dir, "DOA-Mod-Gallery-${info.versionName}.apk")
        val part = File(dir, finalFile.name + ".part")
        if (part.exists()) part.delete()

        val req = Request.Builder()
            .url(info.apkUrl)
            .header("Cache-Control", "no-cache")
            .build()

        http.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("Descarga de actualización HTTP ${response.code}")
            val body = response.body ?: error("Actualización vacía")
            val total = body.contentLength()
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(128 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        out.write(buffer, 0, read)
                        done += read
                        val pct = if (total > 0) ((done * 100L) / total).toInt().coerceIn(0, 99) else 0
                        if (pct != last) {
                            last = pct
                            onProgress(pct)
                        }
                    }
                    out.flush()
                }
            }
        }

        if (part.length() < 100_000L) {
            part.delete()
            error("El APK descargado parece incompleto")
        }
        if (finalFile.exists()) finalFile.delete()
        if (!part.renameTo(finalFile)) {
            part.copyTo(finalFile, overwrite = true)
            part.delete()
        }
        onProgress(100)
        finalFile
    }

    fun uriFor(file: File) = FileProvider.getUriForFile(
        activity,
        "${activity.packageName}.fileprovider",
        file
    )

    fun signaturesMatch(apk: File): Boolean {
        return runCatching {
            val pm = activity.packageManager
            val installed = packageInfo(pm, activity.packageName, null) ?: return@runCatching false
            val archive = packageInfo(pm, apk.absolutePath, apk) ?: return@runCatching false
            signerDigest(installed) == signerDigest(archive)
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(pm: PackageManager, pathOrPackage: String, archive: File?): android.content.pm.PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return if (archive == null) {
            pm.getPackageInfo(pathOrPackage, flags)
        } else {
            pm.getPackageArchiveInfo(pathOrPackage, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun signerDigest(info: android.content.pm.PackageInfo): String {
        val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return ""
            val sig = if (signing.hasMultipleSigners()) signing.apkContentsSigners.firstOrNull()
            else signing.signingCertificateHistory.firstOrNull()
            sig?.toByteArray() ?: return ""
        } else {
            info.signatures?.firstOrNull()?.toByteArray() ?: return ""
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val LATEST_JSON = "https://raw.githubusercontent.com/cyannick056-spec/connection-fix-revenge/main/downloads/latest.json"
    }
}

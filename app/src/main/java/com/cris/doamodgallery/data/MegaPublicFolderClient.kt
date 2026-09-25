package com.cris.doamodgallery.data

import android.util.Base64
import com.cris.doamodgallery.util.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MegaPublicFolderClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class FolderRef(val handle: String, val key: ByteArray)

    fun parseFolderUrl(url: String): FolderRef {
        val m = Regex("/folder/([A-Za-z0-9_-]+)#([A-Za-z0-9_-]+)").find(url)
            ?: error("Enlace de carpeta MEGA no válido")
        return FolderRef(m.groupValues[1], b64UrlDecode(m.groupValues[2]))
    }

    suspend fun listFiles(url: String, onProgress: (Int, String) -> Unit = { _, _ -> }): List<MegaFile> = withContext(Dispatchers.IO) {
        val ref = parseFolderUrl(url)
        require(ref.key.size >= 16) { "Clave de carpeta MEGA inválida" }

        onProgress(8, "Leyendo árbol completo de MEGA…")
        val result = api(
            ref.handle,
            JSONObject().apply {
                put("a", "f")
                put("c", 1)
                put("r", 1)
                put("ca", 1)
            }
        )

        val nodes = result.optJSONArray("f") ?: JSONArray()
        val decoded = linkedMapOf<String, DecodedNode>()
        var encryptedFiles = 0

        for (i in 0 until nodes.length()) {
            val n = nodes.optJSONObject(i) ?: continue
            val handle = n.optString("h")
            val parent = n.optString("p")
            val type = n.optInt("t", -1)
            val attrs = n.optString("a")
            val rawKey = n.optString("k")
            val size = n.optLong("s", 0L)
            if (handle.isBlank() || (type != 0 && type != 1)) continue
            if (type == 0) encryptedFiles++

            var chosenKey: ByteArray? = null
            var chosenIv: ByteArray? = null
            var name = ""

            for (keyMaterial in decryptNodeKeyCandidates(rawKey, ref.key)) {
                val actualKey = if (type == 0 && keyMaterial.size >= 32) {
                    ByteArray(16) { idx ->
                        (keyMaterial[idx].toInt() xor keyMaterial[idx + 16].toInt()).toByte()
                    }
                } else {
                    keyMaterial.copyOfRange(0, 16)
                }

                val iv = if (type == 0 && keyMaterial.size >= 24) {
                    ByteArray(16).also { dst -> keyMaterial.copyInto(dst, 0, 16, 24) }
                } else {
                    ByteArray(16)
                }

                val candidateName = decryptAttributes(attrs, actualKey).orEmpty()
                if (candidateName.isNotBlank()) {
                    name = candidateName
                    chosenKey = actualKey
                    chosenIv = iv
                    break
                }
            }

            if (name.isBlank() || chosenKey == null || chosenIv == null) continue
            decoded[handle] = DecodedNode(handle, parent, type, name, size, chosenKey, chosenIv)

            if (i % 100 == 0 || i == nodes.length() - 1) {
                val pct = 12 + (48.0 * i / maxOf(1, nodes.length())).toInt()
                onProgress(
                    pct,
                    "Descifrando MEGA… ${i + 1}/${nodes.length()} · ${decoded.size} nodos válidos"
                )
            }
        }

        fun buildPath(node: DecodedNode): String {
            val parts = mutableListOf<String>()
            var cur: DecodedNode? = node
            val guard = mutableSetOf<String>()
            while (cur != null && guard.add(cur.handle)) {
                parts += cur.name
                cur = decoded[cur.parent]
            }
            return parts.asReversed().joinToString("/")
        }

        val allDecodedFiles = decoded.values.filter { it.type == 0 }
        val files = allDecodedFiles
            .filter { isCandidateModFile(it.name, it.size) }
            .map { n ->
                val path = buildPath(n)
                MegaFile(
                    n.handle,
                    n.parent,
                    n.name,
                    path,
                    TextUtils.detectCharacter(path),
                    n.size,
                    Base64.encodeToString(n.key, Base64.NO_WRAP),
                    Base64.encodeToString(n.iv, Base64.NO_WRAP)
                )
            }

        onProgress(
            100,
            "MEGA: ${files.size} archivos utilizables · ${allDecodedFiles.size}/$encryptedFiles archivos descifrados · ${nodes.length()} nodos"
        )
        files
    }

    suspend fun getDownloadInfo(folderUrl: String, nodeHandle: String): MegaDownloadInfo = withContext(Dispatchers.IO) {
        val ref = parseFolderUrl(folderUrl)
        var obj = api(
            ref.handle,
            JSONObject().apply {
                put("a", "g")
                put("g", 1)
                put("n", nodeHandle)
            }
        )
        var direct = obj.optString("g")
        if (direct.isBlank()) {
            obj = api(
                ref.handle,
                JSONObject().apply {
                    put("a", "g")
                    put("g", 1)
                    put("p", nodeHandle)
                }
            )
            direct = obj.optString("g")
        }
        if (direct.isBlank()) error("MEGA no devolvió URL de descarga")
        MegaDownloadInfo(direct, obj.optLong("s", 0L))
    }

    suspend fun downloadTo(
        folderUrl: String,
        nodeHandle: String,
        fileKey: ByteArray,
        iv: ByteArray,
        output: java.io.OutputStream,
        onProgress: (Int, Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val info = getDownloadInfo(folderUrl, nodeHandle)
        val req = Request.Builder().url(info.url).get().build()
        http.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("Descarga MEGA HTTP ${response.code}")
            val body = response.body ?: error("MEGA devolvió una descarga vacía")
            val total = if (info.size > 0) info.size else body.contentLength().coerceAtLeast(0L)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(fileKey.copyOfRange(0, 16), "AES"),
                IvParameterSpec(iv.copyOf(16))
            )

            body.byteStream().use { input ->
                output.use { out ->
                    val buffer = ByteArray(128 * 1024)
                    var read: Int
                    var done = 0L
                    var lastPct = -1
                    while (input.read(buffer).also { read = it } >= 0) {
                        if (read == 0) continue
                        val clear = cipher.update(buffer, 0, read)
                        if (clear != null && clear.isNotEmpty()) out.write(clear)
                        done += read
                        val pct = if (total > 0) {
                            ((done * 100L) / total).toInt().coerceIn(0, 99)
                        } else 0
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct, done, total)
                        }
                    }
                    val tail = cipher.doFinal()
                    if (tail.isNotEmpty()) out.write(tail)
                    out.flush()
                    onProgress(100, done, total)
                }
            }
        }
    }

    private fun isCandidateModFile(name: String, size: Long): Boolean {
        val n = name.lowercase()
        val known = Regex("(?i).+\\.(7z|zip|rar|tmc|tmcl|zipmod|pak|mod|bin|001|002|003|004)$")
        val multipart = Regex("(?i).+(\\.7z\\.\\d{3}|\\.part\\d+\\.rar|\\.r\\d{2})$")
        if (known.matches(name) || multipart.matches(name)) return true

        val excluded = listOf(
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".svg",
            ".mp4", ".webm", ".avi", ".mkv", ".mov", ".mp3", ".wav", ".flac",
            ".txt", ".md", ".nfo", ".json", ".html", ".htm", ".url", ".lnk"
        )
        if (excluded.any { n.endsWith(it) }) return false

        // La carpeta de FunnyBunny contiene algunos paquetes con extensiones poco comunes.
        // Los conservamos si son archivos reales de tamaño razonable para no perder descargas.
        return size >= 32 * 1024L
    }

    private fun api(folderHandle: String, command: JSONObject): JSONObject {
        val id = SecureRandom().nextInt(Int.MAX_VALUE)
        val url = "https://g.api.mega.co.nz/cs?id=$id&n=$folderHandle"
        val body = JSONArray().put(command).toString().toRequestBody(JSON)
        val req = Request.Builder().url(url).post(body).build()
        val text = http.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("MEGA HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        val arr = JSONArray(text)
        val first = arr.opt(0)
        if (first is Number) error("MEGA API error ${first.toInt()}")
        return arr.optJSONObject(0) ?: error("Respuesta MEGA inesperada")
    }

    private fun decryptNodeKeyCandidates(raw: String, folderKey: ByteArray): List<ByteArray> {
        if (raw.isBlank()) return emptyList()
        return raw.split('/').mapNotNull { part ->
            val candidate = part.substringAfter(':', part)
            val enc = runCatching { b64UrlDecode(candidate) }.getOrNull() ?: return@mapNotNull null
            if (enc.isEmpty() || enc.size % 16 != 0) return@mapNotNull null
            runCatching { aesEcbDecrypt(folderKey.copyOfRange(0, 16), enc) }
                .getOrNull()
                ?.takeIf { it.size == 16 || it.size == 32 }
        }
    }

    private fun decryptAttributes(raw: String, key: ByteArray): String? {
        if (raw.isBlank()) return null
        return runCatching {
            val enc = b64UrlDecode(raw)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.copyOfRange(0, 16), "AES"),
                IvParameterSpec(ByteArray(16))
            )
            val text = cipher.doFinal(enc).toString(Charsets.UTF_8).trimEnd('\u0000')
            if (!text.startsWith("MEGA")) return@runCatching null
            JSONObject(text.substring(4)).optString("n").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun aesEcbDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    private fun b64UrlDecode(input: String): ByteArray {
        var s = input.replace('-', '+').replace('_', '/')
        while (s.length % 4 != 0) s += "="
        return Base64.decode(s, Base64.DEFAULT)
    }

    private data class DecodedNode(
        val handle: String,
        val parent: String,
        val type: Int,
        val name: String,
        val size: Long,
        val key: ByteArray,
        val iv: ByteArray
    )

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

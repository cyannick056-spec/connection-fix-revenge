package com.cris.doamodgallery.data

import android.content.Context
import com.cris.doamodgallery.util.JsonFileStore
import com.cris.doamodgallery.util.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln

class MegaIndexRepository(context: Context) {
    private val store = JsonFileStore(context)
    private val client = MegaPublicFolderClient()
    @Volatile private var cache = store.read("mega.json", MegaCache::class.java, MegaCache())

    fun files(): List<MegaFile> = cache.files

    suspend fun sync(onProgress: (Int, String) -> Unit): List<MegaFile> {
        val files = client.listFiles(MEGA_FOLDER_URL, onProgress)
        cache = MegaCache(files, System.currentTimeMillis())
        store.write("mega.json", cache)
        return files
    }

    suspend fun match(items: List<ModItem>, onProgress: (Int, Int, Int) -> Unit): Map<String, MegaFile> = withContext(Dispatchers.Default) {
        val files = cache.files
        if (files.isEmpty()) return@withContext emptyMap()

        val charWords = TextUtils.characters.flatMap { TextUtils.normalize(it).split(' ') }.toSet()
        data class Meta(val file: MegaFile, val tokens: Set<String>, val char: String)
        val meta = files.map { f ->
            Meta(
                f,
                TextUtils.megaTokens("${f.character} ${f.path} ${f.name}").filterNot { it in charWords }.toSet(),
                f.character.lowercase()
            )
        }

        val tokenFreq = mutableMapOf<String, Int>()
        val postings = mutableMapOf<String, MutableSet<Int>>()
        val byChar = mutableMapOf<String, MutableSet<Int>>()
        val unscoped = mutableSetOf<Int>()

        meta.forEachIndexed { i, m ->
            if (m.char.isBlank()) unscoped += i else byChar.getOrPut(m.char) { mutableSetOf() } += i
            m.tokens.forEach { t ->
                tokenFreq[t] = (tokenFreq[t] ?: 0) + 1
                postings.getOrPut(t) { mutableSetOf() } += i
            }
        }

        fun score(title: String, itemChar: String, f: MegaFile): Double {
            val a = TextUtils.normalize(title)
            val b = TextUtils.normalize("${f.character} ${f.path} ${f.name}")
            if (a == b) return 1.0

            val ta = TextUtils.megaTokens(a).filterNot { it in charWords }.toSet()
            val tb = TextUtils.megaTokens(b).filterNot { it in charWords }.toSet()
            val inter = ta intersect tb
            if (inter.isEmpty()) return 0.0

            val seq = TextUtils.levenshteinRatio(a, b)
            var value: Double

            if (ta.isNotEmpty() && tb.isNotEmpty() && (ta.containsAll(tb) || tb.containsAll(ta))) {
                val subset = minOf(ta.size, tb.size).toDouble() / maxOf(ta.size, tb.size)
                value = minOf(0.99, maxOf(0.82 + 0.15 * subset, seq * 0.93))
            } else {
                val weighted = inter.sumOf { t ->
                    val freq = maxOf(1, tokenFreq[t] ?: 1)
                    1.0 + maxOf(0.0, minOf(2.2, ln((files.size + 2.0) / freq)))
                }
                val wa = ta.sumOf {
                    1.0 + maxOf(0.0, minOf(2.2, ln((files.size + 2.0) / maxOf(1, tokenFreq[it] ?: 1))))
                }.coerceAtLeast(1.0)
                val wb = tb.sumOf {
                    1.0 + maxOf(0.0, minOf(2.2, ln((files.size + 2.0) / maxOf(1, tokenFreq[it] ?: 1))))
                }.coerceAtLeast(1.0)
                val coverage = weighted / minOf(wa, wb)
                value = minOf(1.0, maxOf(coverage * 0.91, seq * 0.83) + minOf(0.13, inter.size * 0.048))
            }

            val fc = f.character.lowercase()
            if (itemChar.isNotBlank() && fc.isNotBlank()) {
                value += if (itemChar == fc) 0.055 else -0.035
            }
            return value.coerceIn(0.0, 1.0)
        }

        fun rankCandidates(ids: Set<Int>, qTokens: Set<String>): List<Int> = ids
            .sortedByDescending { idx ->
                val ft = meta[idx].tokens
                val inter = qTokens intersect ft
                if (inter.isEmpty()) 0.0 else {
                    val cq = inter.size.toDouble() / maxOf(1, qTokens.size)
                    val cf = inter.size.toDouble() / maxOf(1, ft.size)
                    cq * 0.72 + cf * 0.28
                }
            }
            .take(56)

        val result = mutableMapOf<String, MegaFile>()
        val allIds = meta.indices.toSet()

        items.forEachIndexed { pos, item ->
            val char = item.character.lowercase()
            val qTokens = TextUtils.megaTokens(item.title).filterNot { it in charWords }.toSet()
            val tokenCandidates = qTokens.flatMap { postings[it].orEmpty() }.toSet()

            val sameChar = if (char.isNotBlank() && byChar.containsKey(char)) {
                (byChar[char].orEmpty() + unscoped).toSet()
            } else allIds

            val primaryIds = tokenCandidates.intersect(sameChar).ifEmpty { sameChar }
            val primary = rankCandidates(primaryIds, qTokens)

            var best: MegaFile? = null
            var bestScore = 0.0

            for (idx in primary) {
                val s = score(item.title, char, meta[idx].file)
                if (s > bestScore) {
                    bestScore = s
                    best = meta[idx].file
                }
            }

            // Si la carpeta/personaje no coincide bien, hacemos fallback global en vez de descartar el mod.
            if (bestScore < 0.68 && tokenCandidates.isNotEmpty()) {
                val global = rankCandidates(tokenCandidates, qTokens)
                for (idx in global) {
                    val s = score(item.title, char, meta[idx].file)
                    if (s > bestScore) {
                        bestScore = s
                        best = meta[idx].file
                    }
                }
            }

            if (best != null && bestScore >= 0.57) result[item.id] = best!!
            if (pos == 0 || pos == items.lastIndex || pos % 25 == 0) {
                onProgress(pos + 1, items.size, result.size)
            }
        }
        result
    }

    fun client(): MegaPublicFolderClient = client

    companion object {
        const val MEGA_FOLDER_URL = "https://mega.nz/folder/78c3ha7R#SCNY-a-NdCqp78nuClrlPA"
    }
}

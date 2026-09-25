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
        val meta = files.map { f -> Meta(f, TextUtils.megaTokens("${f.character} ${f.name}").filterNot { it in charWords }.toSet(), f.character.lowercase()) }
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

        fun score(title: String, f: MegaFile): Double {
            val a = TextUtils.normalize(title)
            val b = TextUtils.normalize("${f.character} ${f.name}")
            if (a == b) return 1.0
            val ta = TextUtils.megaTokens(a).filterNot { it in charWords }.toSet()
            val tb = TextUtils.megaTokens(b).filterNot { it in charWords }.toSet()
            val inter = ta intersect tb
            if (inter.isEmpty()) return 0.0
            val seq = TextUtils.levenshteinRatio(a, b)
            if (ta.isNotEmpty() && tb.isNotEmpty() && (ta.containsAll(tb) || tb.containsAll(ta))) {
                val subset = minOf(ta.size, tb.size).toDouble() / maxOf(ta.size, tb.size)
                return minOf(0.99, maxOf(0.80 + 0.16 * subset, seq * 0.92))
            }
            val weighted = inter.sumOf { t ->
                val freq = maxOf(1, tokenFreq[t] ?: 1)
                1.0 + maxOf(0.0, minOf(2.2, ln((files.size + 2.0) / freq)))
            }
            val wa = ta.sumOf { 1.0 + maxOf(0.0, minOf(2.2, ln((files.size + 2.0) / maxOf(1, tokenFreq[it] ?: 1)))) }.coerceAtLeast(1.0)
            val wb = tb.sumOf { 1.0 + maxOf(0.0, minOf(2.2, ln((files.size + 2.0) / maxOf(1, tokenFreq[it] ?: 1)))) }.coerceAtLeast(1.0)
            val coverage = weighted / minOf(wa, wb)
            return minOf(1.0, maxOf(coverage * 0.90, seq * 0.82) + minOf(0.12, inter.size * 0.045))
        }

        val result = mutableMapOf<String, MegaFile>()
        val allIds = meta.indices.toSet()
        items.forEachIndexed { pos, item ->
            val char = item.character.lowercase()
            val base = if (char.isNotBlank() && byChar.containsKey(char)) (byChar[char].orEmpty() + unscoped).toSet() else allIds
            val qTokens = TextUtils.megaTokens(item.title).filterNot { it in charWords }.toSet()
            val plausible = qTokens.flatMap { postings[it].orEmpty() }.toSet().intersect(base).ifEmpty { base }
            val ranked = plausible.sortedByDescending { idx ->
                val ft = meta[idx].tokens
                val inter = qTokens intersect ft
                if (inter.isEmpty()) 0.0 else {
                    val cq = inter.size.toDouble() / maxOf(1, qTokens.size)
                    val cf = inter.size.toDouble() / maxOf(1, ft.size)
                    cq * 0.7 + cf * 0.3
                }
            }.take(32)
            var best: MegaFile? = null
            var bestScore = 0.0
            for (idx in ranked) {
                val s = score(item.title, meta[idx].file)
                if (s > bestScore) { bestScore = s; best = meta[idx].file }
            }
            if (best != null && bestScore >= 0.61) result[item.id] = best!!
            if (pos == 0 || pos == items.lastIndex || pos % 25 == 0) onProgress(pos + 1, items.size, result.size)
        }
        result
    }

    fun client(): MegaPublicFolderClient = client

    companion object {
        const val MEGA_FOLDER_URL = "https://mega.nz/folder/78c3ha7R#SCNY-a-NdCqp78nuClrlPA"
    }
}

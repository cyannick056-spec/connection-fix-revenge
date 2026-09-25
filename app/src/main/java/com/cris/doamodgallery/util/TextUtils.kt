package com.cris.doamodgallery.util

import java.text.Normalizer
import kotlin.math.max

object TextUtils {
    val characters = listOf(
        "Marie Rose","Phase 4","Alpha-152","Naotora Ii","Mai Shiranui","Ryu Hayabusa","Brad Wong","Jann Lee","La Mariposa",
        "Honoka","Kasumi","Ayane","Hitomi","Leifang","Kokoro","Helena","Christie","Tina","Mila","Lisa","Rachel","Momiji",
        "Nyotengu","Rig","Bass","Bayman","Hayate","Eliot","Gen Fu","Ein","Jacky","Akira","Leon","Raidou","Zack","Sarah","Pai",
        "Naotora","Mai","Tamaki","Misaki","Luna","Fiona","Kanna","Lobelia","Nanami","Amy","Nagisa","Monica","Sayuri","Patty",
        "Tsukushi","Koharu","Elise","Shandy","Yukino","Shizuku","Reika","Meg","Azusa"
    )

    private val junk = setOf(
        "doa","doa5","doa5lr","dead","alive","mod","mods","costume","outfit","skin","skins","pack","zip","rar","7z","tmc","tmcl",
        "postimages","postimage","download","folder","doax3","doaxvv","xvv","x3","ver","version","final","fixed","fix","update","updated",
        "preview","image","images"
    )

    fun normalize(value: String): String {
        val n = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        return n.replace(Regex("\\s+"), " ")
    }

    fun prettyTitle(value: String): String = cleanPostTitle(value)
        .replace(Regex("[-_]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * Funnybunny often names a source outfit first and the target character last:
     * "Amy DOAX3 Daiquiri Ayane". Other posts use the target first:
     * "Honoka DOAXVV Moonlight" or "HAIR Honoka ...".
     *
     * We therefore choose the LAST known character occurrence in the canonical title.
     * If only Honoka is present, Honoka wins; if both Amy and Ayane are present, Ayane wins.
     */
    fun detectCharacter(text: String): String {
        val n = " ${normalize(cleanPostTitle(text))} "
        if (n.isBlank()) return ""

        return characters.mapNotNull { character ->
            val cn = normalize(character)
            val index = n.lastIndexOf(" $cn ")
            if (index >= 0) Triple(character, index, cn.length) else null
        }.maxWithOrNull(compareBy<Triple<String, Int, Int>> { it.second }.thenBy { it.third })
            ?.first
            .orEmpty()
    }

    fun cleanPostTitle(value: String): String {
        return value
            .replace(Regex("(?i)\\s*[—–|]\\s*postimages.*$"), "")
            .replace(Regex("(?i)\\s*postimages\\s*$"), "")
            .replace(Regex("(?i)\\.(jpg|jpeg|png|webp|gif)$"), "")
            .trim()
    }

    fun looksLikePostId(value: String): Boolean {
        val v = cleanPostTitle(value).trim()
        return v.matches(Regex("[A-Za-z0-9]{6,12}")) && v.none { it == ' ' || it == '-' || it == '_' }
    }

    /**
     * Search used by Android. It deliberately ignores MEGA metadata; MEGA filenames can
     * describe a different source/variant and were causing searches such as "Honoka" to
     * show Ayane/Hitomi cards.
     *
     * Rules:
     * - A query that is exactly a character name becomes an exact character search.
     * - Otherwise all query words must match the title/character (AND search).
     * - Full phrases and prefixes rank above loose token-prefix matches.
     * Returns -1 when the item does not match.
     */
    fun searchScore(query: String, title: String, character: String): Int {
        val q = normalize(query)
        if (q.isBlank()) return 0

        val t = normalize(title)
        val c = normalize(character)

        val exactCharacter = characters.firstOrNull { normalize(it) == q }
        if (exactCharacter != null) {
            return if (c == q) 10_000 else -1
        }

        if (t == q) return 9_500
        if (t.startsWith("$q ") || t.startsWith(q)) return 9_000
        if ((" $t ").contains(" $q ")) return 8_500

        val needles = q.split(' ').filter { it.isNotBlank() }
        if (needles.isEmpty()) return 0
        val titleTokens = t.split(' ').filter { it.isNotBlank() }
        val charTokens = c.split(' ').filter { it.isNotBlank() }
        val haystack = titleTokens + charTokens

        var score = 0
        for (needle in needles) {
            val exact = haystack.any { it == needle }
            val prefix = needle.length >= 3 && haystack.any { it.startsWith(needle) }
            if (!exact && !prefix) return -1
            score += if (exact) 700 else 420
        }

        if (c.isNotBlank() && needles.any { it == c }) score += 1_500
        if (needles.all { it in titleTokens }) score += 900
        return score
    }

    fun tokenMatch(query: String, text: String): Boolean {
        val q = normalize(query)
        if (q.isBlank()) return true
        val t = normalize(text)
        if (t.contains(q)) return true

        val qs = q.split(' ').filter { it.isNotBlank() }
        val ts = t.split(' ').filter { it.isNotBlank() }
        return qs.all { needle ->
            ts.any { token ->
                token == needle || (needle.length >= 3 && token.startsWith(needle))
            }
        }
    }

    fun megaTokens(value: String): Set<String> = normalize(cleanPostTitle(value))
        .split(' ')
        .filter { it.length > 1 && it !in junk }
        .toSet()

    fun levenshteinRatio(a0: String, b0: String): Double {
        val a = normalize(a0)
        val b = normalize(b0)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in a.indices) {
            cur[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + cost)
            }
            for (k in prev.indices) prev[k] = cur[k]
        }
        return 1.0 - prev[b.length].toDouble() / max(a.length, b.length).toDouble()
    }
}

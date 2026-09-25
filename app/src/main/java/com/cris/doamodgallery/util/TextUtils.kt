package com.cris.doamodgallery.util

import java.text.Normalizer
import kotlin.math.max

object TextUtils {
    // Known names are still useful for MEGA matching, but a gallery category is only
    // accepted when the character name appears at the END of the canonical post title.
    // This prevents source-outfit names such as "Amy DOAXVV ... Hitomi" from creating
    // an incorrect Amy category when the actual target is Hitomi.
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

    fun detectCharacter(text: String): String {
        val cleaned = normalize(cleanPostTitle(text))
        if (cleaned.isBlank()) return ""

        // Funnybunny's naming convention places the target character at the end.
        // Only accepting a suffix prevents outfit/source names from polluting filters.
        return characters
            .map { it to normalize(it) }
            .filter { (_, cn) -> cleaned == cn || cleaned.endsWith(" $cn") }
            .maxByOrNull { (_, cn) -> cn.length }
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

    fun tokenMatch(query: String, text: String): Boolean {
        val q = normalize(query)
        if (q.isBlank()) return true
        val t = normalize(text)
        if (t.contains(q)) return true

        val qs = q.split(' ').filter { it.isNotBlank() }
        val ts = t.split(' ').filter { it.isNotBlank() }
        return qs.all { needle ->
            ts.any { token ->
                token == needle || token.startsWith(needle) || needle.startsWith(token)
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

package com.cris.doamodgallery.data

import android.content.Context

class FavoritesStore(context: Context) {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
    fun all(): Set<String> = prefs.getStringSet("ids", emptySet())?.toSet().orEmpty()
    fun isFavorite(id: String): Boolean = id in all()
    fun toggle(id: String): Boolean {
        val set = all().toMutableSet()
        val nowFavorite = if (id in set) { set.remove(id); false } else { set.add(id); true }
        prefs.edit().putStringSet("ids", set).apply()
        return nowFavorite
    }
}

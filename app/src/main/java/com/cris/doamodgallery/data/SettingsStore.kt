package com.cris.doamodgallery.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("ui_settings", Context.MODE_PRIVATE)
    var columnsMode: Int
        get() = prefs.getInt(KEY_COLUMNS, 3)
        set(value) = prefs.edit().putInt(KEY_COLUMNS, value).apply()
    var themeMode: String
        get() = prefs.getString(KEY_THEME, THEME_DARK) ?: THEME_DARK
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()
    fun resolvedColumns(screenWidthDp: Int): Int = when (columnsMode) { 3 -> 3; 4 -> 4; else -> if (screenWidthDp >= 600) 4 else 3 }
    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(when (themeMode) {
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_YES
        })
    }
    companion object {
        const val THEME_DARK="dark"; const val THEME_LIGHT="light"; const val THEME_SYSTEM="system"
        private const val KEY_COLUMNS="columns"; private const val KEY_THEME="theme"
    }
}

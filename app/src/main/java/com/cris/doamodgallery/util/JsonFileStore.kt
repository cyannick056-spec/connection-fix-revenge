package com.cris.doamodgallery.util

import android.content.Context
import com.google.gson.Gson
import java.io.File

class JsonFileStore(private val context: Context) {
    private val gson = Gson()
    fun <T> read(name: String, clazz: Class<T>, default: T): T = try {
        val file = File(context.filesDir, name)
        if (!file.exists()) default else gson.fromJson(file.readText(), clazz) ?: default
    } catch (_: Exception) { default }
    fun write(name: String, value: Any) {
        val file = File(context.filesDir, name)
        val tmp = File(context.filesDir, "$name.tmp")
        tmp.writeText(gson.toJson(value))
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }
}

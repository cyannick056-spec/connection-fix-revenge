package com.cris.doamodgallery.util

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import java.io.File
import java.io.OutputStreamWriter

class JsonFileStore(private val context: Context) {
    private val gson = Gson()

    fun <T> read(name: String, clazz: Class<T>, default: T): T = try {
        val atomic = AtomicFile(File(context.filesDir, name))
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) {
            default
        } else {
            atomic.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                gson.fromJson(reader, clazz) ?: default
            }
        }
    } catch (_: Exception) {
        default
    }

    @Synchronized
    fun write(name: String, value: Any) {
        val atomic = AtomicFile(File(context.filesDir, name))
        val stream = atomic.startWrite()
        try {
            val writer = OutputStreamWriter(stream, Charsets.UTF_8)
            writer.write(gson.toJson(value))
            writer.flush()
            atomic.finishWrite(stream)
        } catch (t: Throwable) {
            atomic.failWrite(stream)
            throw t
        }
    }
}

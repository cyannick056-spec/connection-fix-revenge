package com.cris.doamodgallery

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.cris.doamodgallery.data.SettingsStore

class DoaGalleryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore(this).applyTheme()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DOWNLOAD_CHANNEL,
                "Descargas de mods",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Progreso de descargas desde MEGA" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val DOWNLOAD_CHANNEL = "doa_mod_downloads"
    }
}

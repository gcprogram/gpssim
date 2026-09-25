package com.gcprogram.gpssim

import android.app.Application
import android.preference.PreferenceManager
import org.osmdroid.config.Configuration

class GpsSimApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // osmdroid braucht einen Load-Path fürs Tile-Cache und eine User-Agent-ID,
        // sonst blockt der OSM-Tile-Server Requests (analog c:geo-Konfiguration)
        Configuration.getInstance().load(
            this,
            PreferenceManager.getDefaultSharedPreferences(this)
        )
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().osmdroidTileCache = getExternalFilesDir("osmdroid_tiles")
            ?: cacheDir
    }
}

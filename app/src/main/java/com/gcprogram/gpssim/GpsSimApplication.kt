package com.gcprogram.gpssim

import android.app.Application
import android.preference.PreferenceManager
import org.osmdroid.config.Configuration
import org.osmdroid.mapsforge.MapsForgeTileSource

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

        // WICHTIG: ohne diesen einmaligen Aufruf lädt MapsForgeTileSource kein gültiges
        // Render-Theme (XML-Stil für Straßen/Flächen/Beschriftung) - die Kachel-Anfragen
        // laufen dann scheinbar normal durch (Karte gilt als "aktiv"), liefern aber leere/
        // transparente Bitmaps, weil dem Renderer schlicht der Stil fehlt. Genau das Symptom
        // "Offline-Karte aktiv, aber nichts wird gezeichnet" - siehe GCToolkit-Android, wo
        // dieser Aufruf ebenfalls vor jeder Verwendung von MapsForgeTileSource steht.
        MapsForgeTileSource.createInstance(this)
    }
}

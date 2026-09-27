package com.gcprogram.gpssim.gpx

import android.content.Context
import android.net.Uri
import com.gcprogram.gpssim.geo.GeoCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream

/**
 * Prozessweiter Singleton für die zuletzt importierte GPX-Cacheliste. Die Rohdatei wird
 * gestreamt (8-KB-Puffer) ins App-Verzeichnis kopiert (wie OfflineMapManager für .map-Dateien),
 * damit die Liste beim nächsten App-Start ohne erneuten Import wieder da ist.
 *
 * [selectedCache] steuert, was MapScreen anzeigt: null = Übersicht aller Caches (posted-
 * bzw. Final-Koordinaten, Cache-Typ-Icon), gesetzt = nur die Wegpunkte dieses einen Caches.
 */
object GpxRepository {

    private val _caches = MutableStateFlow<List<GeoCache>>(emptyList())
    val caches: StateFlow<List<GeoCache>> = _caches.asStateFlow()

    private val _selectedCache = MutableStateFlow<GeoCache?>(null)
    val selectedCache: StateFlow<GeoCache?> = _selectedCache.asStateFlow()

    private fun gpxDir(context: Context): File =
        File(context.filesDir, "gpx").apply { mkdirs() }

    private fun storedFile(context: Context): File = File(gpxDir(context), "current.gpx")

    /** Importiert eine GPX/PQ-ZIP-Datei per SAF-Uri, speichert sie und parst sie sofort. */
    fun import(context: Context, sourceUri: Uri): GpxImporter.Result {
        val target = storedFile(context)
        context.contentResolver.openInputStream(sourceUri).use { input ->
            requireNotNull(input) { "Konnte die gewählte Datei nicht öffnen" }
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(8 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        }
        val result = target.inputStream().use { GpxImporter.parseStream(it) }
        _caches.value = result.caches
        _selectedCache.value = null
        return result
    }

    /** Lädt die zuletzt importierte GPX beim App-Start erneut, falls vorhanden. */
    fun loadPersisted(context: Context) {
        if (_caches.value.isNotEmpty()) return
        val file = storedFile(context)
        if (!file.exists()) return
        val result = runCatching { file.inputStream().use { GpxImporter.parseStream(it) } }.getOrNull()
        if (result != null) _caches.value = result.caches
    }

    fun selectCache(cache: GeoCache?) {
        _selectedCache.value = cache
    }

    fun clear(context: Context) {
        storedFile(context).delete()
        _caches.value = emptyList()
        _selectedCache.value = null
    }
}

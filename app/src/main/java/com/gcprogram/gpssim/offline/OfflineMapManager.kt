package com.gcprogram.gpssim.offline

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/**
 * Verwaltet die ausgewählte Mapsforge-Offline-Kartendatei (.map, z.B. von
 * openandromaps.de oder download.mapsforge.org).
 *
 * Der System-Dateipicker liefert nur eine content://-Uri, aus der Mapsforge nicht
 * direkt lesen kann (braucht wahlfreien Dateizugriff für die interne Kachel-Indexstruktur).
 * Deshalb wird die Datei einmalig gestreamt (8-KB-Puffer, nicht komplett in den Speicher
 * geladen - Dateien können mehrere hundert MB groß sein) ins App-interne Verzeichnis kopiert.
 */
object OfflineMapManager {

    private const val PREFS_NAME = "offline_map_prefs"
    private const val KEY_MAP_FILE_NAME = "map_file_name"
    private const val KEY_MAP_DISPLAY_NAME = "map_display_name"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun offlineMapsDir(context: Context): File =
        File(context.filesDir, "offline_maps").apply { mkdirs() }

    /** Kopiert die per SAF ausgewählte .map-Datei gestreamt ins App-Verzeichnis und merkt sie sich als aktiv. */
    fun importMap(context: Context, sourceUri: Uri): File {
        val displayName = queryDisplayName(context, sourceUri) ?: "offline_map.map"
        val targetFile = File(offlineMapsDir(context), "current.map")

        context.contentResolver.openInputStream(sourceUri).use { input ->
            requireNotNull(input) { "Konnte die gewählte Datei nicht öffnen" }
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(8 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        }

        prefs(context).edit()
            .putString(KEY_MAP_FILE_NAME, targetFile.name)
            .putString(KEY_MAP_DISPLAY_NAME, displayName)
            .apply()

        return targetFile
    }

    /** Liefert die aktuell importierte Kartendatei, oder null wenn noch keine gewählt wurde. */
    fun currentMapFile(context: Context): File? {
        val fileName = prefs(context).getString(KEY_MAP_FILE_NAME, null) ?: return null
        val file = File(offlineMapsDir(context), fileName)
        return if (file.exists()) file else null
    }

    /** Anzeigename der zuletzt importierten Karte (Originaldateiname), für die UI. */
    fun currentMapDisplayName(context: Context): String? =
        prefs(context).getString(KEY_MAP_DISPLAY_NAME, null)

    fun clearMap(context: Context) {
        currentMapFile(context)?.delete()
        prefs(context).edit().remove(KEY_MAP_FILE_NAME).remove(KEY_MAP_DISPLAY_NAME).apply()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    }
}

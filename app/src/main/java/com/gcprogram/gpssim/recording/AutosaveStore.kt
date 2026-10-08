package com.gcprogram.gpssim.recording

import android.content.Context
import com.gcprogram.gpssim.geo.RecordedPoint
import java.io.File

/**
 * Automatische Zwischensicherung einer laufenden Aufzeichnung - schreibt periodisch (siehe
 * TrackRecordingService) eine GPX-Datei ins private App-Verzeichnis (kein Berechtigungs- oder
 * SAF-Dialog nötig, läuft also unbemerkt im Hintergrund mit). Das ist KEIN Ersatz für das
 * bewusste "Speichern" (GPX/KML über den System-Dateiauswahldialog, siehe TrackerScreen) -
 * sondern nur ein Sicherheitsnetz, falls der Prozess vor dem manuellen Speichern beendet wird
 * (App "Kraft stoppen", aggressives Akku-Management mancher Hersteller, Absturz).
 *
 * Immer EINE feste Datei statt einer pro Tour/Name - vermeidet Dateileichen beim Umbenennen
 * während einer laufenden Aufzeichnung; der Tourname landet trotzdem im <name>-Element der GPX
 * (siehe GpxTrackExporter), geht beim Laden also nicht verloren.
 */
object AutosaveStore {
    private const val SUBDIR = "autosave"
    private const val FILENAME = "autosave.gpx"

    private fun file(context: Context): File =
        File(context.filesDir, "$SUBDIR/$FILENAME")

    fun exists(context: Context): Boolean = file(context).exists()

    /** Schreibt synchron - die Datei ist klein (ein paar hundert Punkte), daher unkritisch. */
    fun write(context: Context, points: List<RecordedPoint>, trackName: String) {
        if (points.size < 2) return
        val f = file(context)
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(GpxTrackExporter.export(points, trackName.ifBlank { "Aufzeichnung" }))
        }
    }

    fun load(context: Context): List<RecordedPoint> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching { f.inputStream().use { RecordedTrackImporter.parse(it) } }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}

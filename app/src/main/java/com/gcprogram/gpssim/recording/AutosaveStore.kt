package com.gcprogram.gpssim.recording

import android.content.Context
import com.gcprogram.gpssim.geo.RecordedPoint
import java.io.File
import java.io.FileOutputStream

/**
 * Automatische Zwischensicherung einer laufenden Aufzeichnung - schreibt fortlaufend (ein
 * Fragment PRO eintreffendem GPS-Fix, siehe [appendPoint]) in eine Datei im privaten
 * App-Verzeichnis (kein Berechtigungs- oder SAF-Dialog nötig, läuft also unbemerkt im
 * Hintergrund mit). Das ist KEIN Ersatz für das bewusste "Speichern" (GPX/KML über den
 * System-Dateiauswahldialog, siehe TrackerScreen) - sondern nur ein Sicherheitsnetz, falls der
 * Prozess vor dem manuellen Speichern beendet wird (App "Kraft stoppen", aggressives
 * Akku-Management mancher Hersteller, Absturz, Reboot).
 *
 * GESCHICHTE/LEKTION (echter Datenverlust einer kompletten Tagestour): Die vorige Fassung hatte
 * zwei Fehler, die sich gegenseitig verschärft haben:
 *  1. Es gab GENAU EINE feste Datei ("autosave.gpx") für ALLE Aufzeichnungen. Wurde nach einem
 *     Absturz/Reboot eine NEUE Aufzeichnung gestartet, ohne die alte Sicherung zuerst zu laden
 *     oder zu verwerfen, hat die neue Aufzeichnung dieselbe Datei irgendwann überschrieben - die
 *     alte, noch nicht gerettete Tour war dann komplett weg.
 *  2. Es wurde nur alle 3 Minuten der GESAMTE bisherige Track neu geschrieben, nicht fortlaufend -
 *     bis zu 3 Minuten vor einem Absturz waren also ohnehin nie gesichert.
 * Diese Fassung behebt beides: JEDE Aufzeichnung bekommt beim Start eine EIGENE, zeitstempel-
 * benannte Datei (siehe [startSession]), die nie von einer anderen Aufzeichnung angefasst wird -
 * alte, nicht abgeholte Sicherungen bleiben also liegen, bis sie bewusst geladen oder verworfen
 * werden. Und jeder einzelne Punkt wird SOFORT an die Datei angehängt ([appendPoint]) statt nur
 * gepuffert - im schlimmsten Fall fehlt also nur der eine Punkt, der gerade während des Absturz-
 * Moments selbst geschrieben wurde, nicht mehr ganze Minuten oder eine ganze Tour.
 *
 * Dafür ist die Datei, SOLANGE die Aufzeichnung läuft, absichtlich kein vollständiges XML-Dokument:
 * die öffnenden Tags (<gpx><trk><name>...<trkseg>) stehen von Anfang an fest, die schließenden
 * (</trkseg></trk></gpx>, siehe [finalizeSession]) werden erst beim regulären Stopp angehängt.
 * Bricht der Prozess vorher ab, fehlen diese schließenden Tags - [RecordedTrackImporter] liest
 * so eine Datei trotzdem bestmöglich ein (fängt den dadurch entstehenden Parse-Fehler ab und
 * behält alle bis dahin vollständig gelesenen Punkte), eine künstliche Aufteilung in mehrere
 * <trkseg> gibt es dabei NICHT - ein <trkseg> bleibt für die ganze Aufzeichnung bestehen
 * (Segmente sind für tatsächlich erkannte Pausen gedacht, nicht als Nebeneffekt der Technik hier).
 */
object AutosaveStore {
    private const val SUBDIR = "autosave"

    // Datei der GERADE LAUFENDEN bzw. zuletzt geladenen Aufzeichnung - NICHT mit "der einen"
    // Autosave-Datei von früher verwechseln: das hier ist nur die Erinnerung, welche von
    // potenziell mehreren Dateien im autosave/-Ordner gerade "in Benutzung" ist (damit ein
    // bewusstes Speichern/Verwerfen die richtige Datei trifft) - der Ordner selbst kann
    // daneben beliebig viele weitere, noch nicht abgeholte Sicherungen alter Abstürze enthalten.
    @Volatile
    private var currentFile: File? = null

    private fun dir(context: Context): File = File(context.filesDir, SUBDIR)

    /** Neue Aufzeichnung gestartet - eigene, garantiert noch nicht existierende Datei anlegen und
     * sofort die öffnenden GPX-Tags hineinschreiben. Frühere, noch nicht abgeholte Sicherungen
     * (von einem vorherigen Absturz) bleiben unangetastet im selben Ordner liegen. */
    fun startSession(context: Context, trackName: String) {
        val d = dir(context)
        runCatching { d.mkdirs() }
        val f = File(d, "session_${System.currentTimeMillis()}.gpx")
        runCatching {
            f.writeText(GpxTrackExporter.header(trackName.ifBlank { "Aufzeichnung" }))
        }
        currentFile = f
    }

    /** Ein einzelner neuer Punkt - wird direkt angehängt und geflusht, nicht nur im RAM
     * gepuffert. Kein Aufruf von [startSession] zuvor (sollte nicht vorkommen) -> no-op. */
    fun appendPoint(point: RecordedPoint) {
        val f = currentFile ?: return
        runCatching {
            FileOutputStream(f, /* append = */ true).use { out ->
                out.write(GpxTrackExporter.trkptXml(point).toByteArray())
                out.flush()
                out.fd.sync()
            }
        }
    }

    /** Regulärer Stopp - schließende Tags anhängen, damit die Datei ein normales, von jedem
     * GPX-Tool lesbares Dokument wird. Die Datei bleibt trotzdem als [currentFile] gemerkt (für
     * ein eventuelles anschließendes bewusstes Speichern/Verwerfen, siehe [clearCurrent]). */
    fun finalizeSession() {
        val f = currentFile ?: return
        runCatching {
            FileOutputStream(f, /* append = */ true).use { out ->
                out.write(GpxTrackExporter.footer().toByteArray())
                out.flush()
                out.fd.sync()
            }
        }
    }

    /** Eine im autosave/-Ordner gefundene, noch nicht abgeholte Sicherung. */
    data class Recoverable(val file: File, val trackName: String, val pointCount: Int, val lastModified: Long)

    /** Alle Sicherungen im Ordner AUSSER der aktuell in Benutzung befindlichen (siehe
     * [currentFile]) - typischerweise Reste vorheriger Abstürze. Neueste zuerst. */
    fun listRecoverable(context: Context): List<Recoverable> {
        val files = dir(context).listFiles { f -> f.isFile && f.extension == "gpx" } ?: return emptyList()
        return files
            .filter { it != currentFile }
            .mapNotNull { f ->
                val parsed = runCatching { f.inputStream().use { RecordedTrackImporter.parseTrack(it) } }.getOrNull()
                if (parsed == null || parsed.points.isEmpty()) null
                else Recoverable(f, parsed.name ?: "Aufzeichnung", parsed.points.size, f.lastModified())
            }
            .sortedByDescending { it.lastModified }
    }

    /** Lädt eine konkrete [Recoverable] vollständig ein und merkt sie als [currentFile] - ein
     * anschließendes bewusstes Speichern löscht dann genau diese Datei (siehe [clearCurrent]). */
    fun load(entry: Recoverable): List<RecordedPoint> {
        val parsed = runCatching { entry.file.inputStream().use { RecordedTrackImporter.parseTrack(it) } }
            .getOrNull() ?: return emptyList()
        currentFile = entry.file
        return parsed.points
    }

    /** Löscht gezielt eine bestimmte Sicherung (z.B. "Verwerfen" in der Wiederherstellungs-Liste),
     * ohne die aktuell laufende/geladene Aufzeichnung zu berühren, falls es eine andere Datei ist. */
    fun discard(entry: Recoverable) {
        runCatching { entry.file.delete() }
        if (currentFile == entry.file) currentFile = null
    }

    /** Die aktuelle Aufzeichnung wurde bewusst exportiert oder verworfen - ihre Sicherungsdatei
     * wird jetzt nicht mehr gebraucht. */
    fun clearCurrent() {
        currentFile?.let { runCatching { it.delete() } }
        currentFile = null
    }
}

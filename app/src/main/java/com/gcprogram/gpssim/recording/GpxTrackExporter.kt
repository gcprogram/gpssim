package com.gcprogram.gpssim.recording

import com.gcprogram.gpssim.geo.RecordedPoint
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Schreibt eine Aufzeichnung (TrackRecorder.points) als GPX-1.1-Track (<trk>/<trkseg>/<trkpt>
 * mit <time>) - das Standardformat, das praktisch jedes Geocaching-/Wander-Tool lesen kann, und
 * das auch [RecordedTrackImporter] wieder einliest (Rundtrip Speichern -> Laden -> Abspielen).
 */
object GpxTrackExporter {

    // Eigener Namespace für Felder, die GPX 1.1 nicht vorsieht (siehe <accuracy> unten) -
    // offizielle GPX-Erweiterungen MÜSSEN unter einem eigenen Namespace stehen, sonst ist die
    // Datei gegen das GPX-Schema ungültig (schlägt bei strengen Validatoren/manchen Tools fehl).
    // "internal" statt "private": [AutosaveStore] schreibt dieselben <trkpt>-Fragmente einzeln
    // und inkrementell (siehe dort) statt wie hier den ganzen Track auf einmal - beide teilen
    // sich darum Header/Namespace/Fragment-Logik, damit es nur eine Stelle für das GPX-Format gibt.
    internal const val EXT_NS = "http://gpssim.gcprogram.com/gpx-extensions/1/0"

    /** Öffnender Teil (XML-Deklaration bis `<trkseg>`) - von [AutosaveStore] beim Start einer
     * neuen Aufzeichnung einmalig geschrieben, bevor einzelne Punkte nachträglich angehängt werden. */
    internal fun header(trackName: String): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"GPS Simulator\" xmlns=\"http://www.topografix.com/GPX/1/1\"")
        sb.append(" xmlns:gpssim=\"").append(EXT_NS).append("\">\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(escape(trackName)).append("</name>\n")
        sb.append("    <trkseg>\n")
        return sb.toString()
    }

    /** Schließender Teil (Gegenstück zu [header]) - von [AutosaveStore] erst beim regulären
     * Stopp angehängt; bis dahin bleibt die Datei absichtlich ohne diese Tags (siehe dort). */
    internal fun footer(): String = "    </trkseg>\n  </trk>\n</gpx>\n"

    /** Ein einzelnes <trkpt>-Fragment - gemeinsam genutzt von [export] (kompletter Track auf
     * einmal) und [AutosaveStore] (ein Fragment pro eintreffendem GPS-Fix). */
    internal fun trkptXml(p: RecordedPoint): String {
        val sb = StringBuilder()
        sb.append("      <trkpt lat=\"").append(p.latitude).append("\" lon=\"").append(p.longitude).append("\">\n")
        if (p.altitude != null) {
            sb.append("        <ele>").append(p.altitude).append("</ele>\n")
        }
        sb.append("        <time>").append(isoTime(p.timestampMillis)).append("</time>\n")
        if (p.speedMps != null) {
            // Keine Standard-GPX-Größe - als Kommentar-ähnliches <extensions>-Feld unter
            // eigenem Namespace wäre sauberer, für den Eigenbedarf (Rundtrip in dieser App)
            // reicht ein einfaches <speed>-Element, das andere Tools einfach ignorieren.
            sb.append("        <speed>").append(p.speedMps).append("</speed>\n")
        }
        if (p.accuracyMeters != null) {
            // <hdop> ist genau genommen eine dimensionslose Verdünnungszahl, keine Meterangabe -
            // aber die gängigen Geocaching-/Wander-Tools zeigen hdop als "Genauigkeit" an, und
            // der von Location.getAccuracy() gemeldete Meterradius ist der praxisnächste Wert,
            // den wir dafür haben (bewusste Vereinfachung für die Fremd-Tool-Anzeige). Der exakte
            // Wert fürs verlustfreie Wiedereinlesen in dieser App steht zusätzlich, unverändert,
            // in <extensions><gpssim:accuracy>.
            sb.append("        <hdop>").append(p.accuracyMeters).append("</hdop>\n")
            sb.append("        <extensions><gpssim:accuracy>").append(p.accuracyMeters)
                .append("</gpssim:accuracy></extensions>\n")
        }
        sb.append("      </trkpt>\n")
        return sb.toString()
    }

    fun export(points: List<RecordedPoint>, trackName: String): String {
        val sb = StringBuilder()
        sb.append(header(trackName))
        for (p in points) {
            sb.append(trkptXml(p))
        }
        sb.append(footer())
        return sb.toString()
    }

    private fun isoTime(millis: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis))

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

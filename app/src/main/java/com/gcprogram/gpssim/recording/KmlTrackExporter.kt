package com.gcprogram.gpssim.recording

import com.gcprogram.gpssim.geo.RecordedPoint
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Schreibt eine Aufzeichnung als KML mit einem gx:Track (Google-Erweiterung) statt eines
 * einfachen LineString - nur gx:Track hält Zeitstempel PRO Koordinate (<when> parallel zu
 * <gx:coord>), was für einen späteren zeitgetreuen Re-Import wichtig wäre. Öffnet sich direkt
 * in Google Earth/Maps mit korrekter zeitlicher Wiedergabe (Zeitleiste).
 */
object KmlTrackExporter {

    fun export(points: List<RecordedPoint>, trackName: String): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:gx=\"http://www.google.com/kml/ext/2.2\">\n")
        sb.append("  <Document>\n")
        sb.append("    <name>").append(escape(trackName)).append("</name>\n")
        sb.append("    <Placemark>\n")
        sb.append("      <name>").append(escape(trackName)).append("</name>\n")
        sb.append("      <gx:Track>\n")
        for (p in points) {
            sb.append("        <when>").append(isoTime(p.timestampMillis)).append("</when>\n")
        }
        for (p in points) {
            // gx:coord-Reihenfolge: lon lat alt (umgekehrt zu GPX!) - alt fehlt -> 0
            val alt = p.altitude ?: 0.0
            sb.append("        <gx:coord>").append(p.longitude).append(' ').append(p.latitude).append(' ').append(alt).append("</gx:coord>\n")
        }
        sb.append("      </gx:Track>\n")
        sb.append("    </Placemark>\n")
        sb.append("  </Document>\n")
        sb.append("</kml>\n")
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

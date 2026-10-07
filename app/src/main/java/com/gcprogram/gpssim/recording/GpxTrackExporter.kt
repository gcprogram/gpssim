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

    fun export(points: List<RecordedPoint>, trackName: String): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"GPS Simulator\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(escape(trackName)).append("</name>\n")
        sb.append("    <trkseg>\n")
        for (p in points) {
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
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
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

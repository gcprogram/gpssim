package com.gcprogram.gpssim.recording

import com.gcprogram.gpssim.geo.RecordedPoint
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Liest eine zuvor per [GpxTrackExporter] gespeicherte (oder von einem anderen Tool erzeugte)
 * GPX-Track-Datei (<trk>/<trkseg>/<trkpt> mit <time>) wieder ein - die "Load"-Funktion für
 * aufgezeichnete Touren. Anders als [com.gcprogram.gpssim.gpx.GpxImporter] (liest <wpt>-Caches)
 * liest dieser Importer <trkpt>-Elemente, die zwingend einen Zeitstempel brauchen (ohne <time>
 * ist eine zeitdynamische Wiedergabe nicht möglich - solche Punkte werden übersprungen).
 */
object RecordedTrackImporter {

    fun parse(input: InputStream): List<RecordedPoint> {
        val bytes = input.readBytes()
        val points = ArrayList<RecordedPoint>()
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(ByteArrayInputStream(bytes), null)
        }
        var event = parser.eventType
        var inTrkpt = false
        var lat = 0.0
        var lon = 0.0
        var ele: Double? = null
        var timeMillis: Long? = null
        var currentTag: String? = null

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = local(parser.name)
                    when (name) {
                        "trkpt" -> {
                            inTrkpt = true
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            ele = null
                            timeMillis = null
                        }
                        else -> if (inTrkpt) currentTag = name
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inTrkpt) {
                        when (currentTag) {
                            "ele" -> ele = parser.text?.trim()?.toDoubleOrNull()
                            "time" -> timeMillis = parseIsoTime(parser.text?.trim())
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = local(parser.name)
                    if (name == "trkpt") {
                        inTrkpt = false
                        val t = timeMillis
                        if (t != null) {
                            points.add(RecordedPoint(latitude = lat, longitude = lon, timestampMillis = t, altitude = ele))
                        }
                        // Punkte ohne <time> werden stillschweigend übersprungen - ohne
                        // Zeitstempel lässt sich keine zeitdynamische Wiedergabe berechnen.
                    } else if (inTrkpt && name == currentTag) {
                        currentTag = null
                    }
                }
            }
            event = parser.next()
        }
        return points
    }

    private fun parseIsoTime(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return try {
            Instant.parse(text).toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private fun local(name: String?): String = name?.substringAfterLast(':') ?: ""
}

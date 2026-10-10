package com.gcprogram.gpssim.recording

import com.gcprogram.gpssim.geo.RecordedPoint
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeParseException

/** Ergebnis von [RecordedTrackImporter.parseTrack]: Punkte PLUS der im <trk><name> gespeicherte
 * Tourname - wichtig für die Wiederherstellung einer automatischen Zwischensicherung (siehe
 * AutosaveStore), damit der beim Start vergebene Name nicht verloren geht. */
data class ParsedTrack(val name: String?, val points: List<RecordedPoint>)

/**
 * Liest eine zuvor per [GpxTrackExporter] gespeicherte (oder von einem anderen Tool erzeugte)
 * GPX-Track-Datei (<trk>/<trkseg>/<trkpt> mit <time>) wieder ein - die "Load"-Funktion für
 * aufgezeichnete Touren. Anders als [com.gcprogram.gpssim.gpx.GpxImporter] (liest <wpt>-Caches)
 * liest dieser Importer <trkpt>-Elemente, die zwingend einen Zeitstempel brauchen (ohne <time>
 * ist eine zeitdynamische Wiedergabe nicht möglich - solche Punkte werden übersprungen).
 *
 * WICHTIG (Lektion aus einem echten Datenverlust): [AutosaveStore] schreibt eine laufende
 * Aufzeichnung inkrementell und schließt die äußeren Tags (</trkseg></trk></gpx>) erst beim
 * regulären Stopp - stirbt der Prozess vorher (Absturz, "Kraft stoppen", Reboot), fehlen diese
 * schließenden Tags und das Dokument ist streng genommen nicht wohlformed. Ein kompletter
 * Parse-Fehler darf in diesem Fall NICHT dazu führen, dass bereits sauber gelesene Punkte
 * verworfen werden (das genaue Gegenteil von "Zwischensicherung") - darum fängt [parseTrack]
 * eine solche Exception selbst ab und liefert alles, was bis dahin erfolgreich gelesen wurde,
 * statt die Exception nach außen durchzureichen (wo sie vorher über ein äußeres runCatching die
 * GESAMTE Liste auf leer zurückgesetzt hat).
 */
object RecordedTrackImporter {

    /** Nur die Punkte - Kompatibilität für Aufrufer, die den Namen nicht brauchen. */
    fun parse(input: InputStream): List<RecordedPoint> = parseTrack(input).points

    fun parseTrack(input: InputStream): ParsedTrack {
        val bytes = input.readBytes()
        val points = ArrayList<RecordedPoint>()
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(ByteArrayInputStream(bytes), null)
        }
        var inTrkpt = false
        var lat = 0.0
        var lon = 0.0
        var ele: Double? = null
        var timeMillis: Long? = null
        var hdop: Float? = null
        var extAccuracy: Float? = null
        var currentTag: String? = null

        // Für den Tournamen: <name> ist direktes Kind von <trk>, NICHT von <trkpt> - eigene,
        // von currentTag getrennte Nachverfolgung, damit sich beide Ebenen nicht gegenseitig
        // überschreiben (ein <trkpt> kann kein <name> enthalten, aber zur Klarheit getrennt).
        var trkName: String? = null
        var outerTag: String? = null

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = local(parser.name)
                        when {
                            name == "trkpt" -> {
                                inTrkpt = true
                                lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                                lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                                ele = null
                                timeMillis = null
                                hdop = null
                                extAccuracy = null
                            }
                            inTrkpt -> currentTag = name
                            else -> outerTag = name
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inTrkpt) {
                            when (currentTag) {
                                "ele" -> ele = parser.text?.trim()?.toDoubleOrNull()
                                "time" -> timeMillis = parseIsoTime(parser.text?.trim())
                                "hdop" -> hdop = parser.text?.trim()?.toFloatOrNull()
                                // "accuracy" kommt NUR aus unserem eigenen <extensions><gpssim:accuracy>
                                // (siehe GpxTrackExporter) - FEATURE_PROCESS_NAMESPACES ist aus, daher
                                // kommt hier bereits der von local() entfernte, präfixlose Name an.
                                "accuracy" -> extAccuracy = parser.text?.trim()?.toFloatOrNull()
                            }
                        } else if (outerTag == "name" && trkName == null) {
                            // Nur den ERSTEN <name> übernehmen (das von <trk>, ganz am Anfang) -
                            // ein eventuelles <gpx><metadata><name> o.ä. käme sonst später und
                            // würde den eigentlichen Tournamen wieder überschreiben.
                            val t = parser.text?.trim()
                            if (!t.isNullOrEmpty()) trkName = t
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = local(parser.name)
                        if (name == "trkpt") {
                            inTrkpt = false
                            val t = timeMillis
                            if (t != null) {
                                // extAccuracy (unsere eigene <extensions>-Erweiterung) ist der exakte,
                                // unveränderte Wert - bevorzugt gegenüber <hdop>, das GpxTrackExporter
                                // nur als Annäherung für Fremd-Tools schreibt (siehe dort). Stammt die
                                // Datei aus einem anderen Tool, gibt es kein gpssim:accuracy, dann bleibt
                                // hdop als bester verfügbarer Näherungswert.
                                points.add(
                                    RecordedPoint(
                                        latitude = lat,
                                        longitude = lon,
                                        timestampMillis = t,
                                        altitude = ele,
                                        accuracyMeters = extAccuracy ?: hdop
                                    )
                                )
                            }
                            // Punkte ohne <time> werden stillschweigend übersprungen - ohne
                            // Zeitstempel lässt sich keine zeitdynamische Wiedergabe berechnen.
                        } else if (inTrkpt && name == currentTag) {
                            currentTag = null
                        } else if (!inTrkpt && name == outerTag) {
                            outerTag = null
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            // Abgeschnittenes Dokument (fehlende </trkseg></trk></gpx>, z.B. aus einer
            // Zwischensicherung, deren Prozess vor dem regulären Stopp beendet wurde) oder ein
            // anderer XML-Fehler mitten im Dokument - bewusst HIER abfangen statt den Aufrufer
            // per äußerem runCatching alles verwerfen zu lassen: alle bis zu diesem Punkt
            // vollständig gelesenen <trkpt> bleiben erhalten, nur der unvollständige Rest (max.
            // der letzte, evtl. angebrochene Punkt) geht verloren.
        }
        return ParsedTrack(trkName, points)
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

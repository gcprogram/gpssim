package com.gcprogram.gpssim.geo

/**
 * Ein per echtem GPS aufgezeichneter (oder aus einer gespeicherten Aufzeichnung geladener)
 * Punkt, MIT Zeitstempel - im Unterschied zu [TrackPoint] (manuell gesetzte Wegpunkte ohne
 * Zeitbezug). Der Zeitstempel ist die Grundlage für die zeitdynamisch-beschleunigte Wiedergabe
 * in [com.gcprogram.gpssim.location.RecordedTrackPlayer].
 */
data class RecordedPoint(
    val latitude: Double,
    val longitude: Double,
    val timestampMillis: Long,
    val altitude: Double? = null,
    val speedMps: Float? = null
)

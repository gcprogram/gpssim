package com.gcprogram.gpssim.geo

/** [label] ist optional - z.B. der Cache-Code/Titel oder Wegpunkt-Typ, wenn der Punkt aus
 * einer GPX-Cacheliste stammt (siehe TrackRepository.addAll). Für manuell eingefügte/getappte
 * Punkte bleibt es null. */
data class TrackPoint(val latitude: Double, val longitude: Double, val label: String? = null)

data class SimulatedPosition(
    val latitude: Double,
    val longitude: Double,
    val bearing: Float,
    val speedMps: Float
)

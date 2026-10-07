package com.gcprogram.gpssim.location

import android.content.Context
import com.gcprogram.gpssim.geo.RecordedPoint
import com.gcprogram.gpssim.geo.TrackRepository

/**
 * Zentrale Start-Logik für die Simulations-Fahrt - wird sowohl vom Play-FAB auf der Kartenseite
 * als auch vom neuen Play-Button auf der Wegpunktliste genutzt (siehe WaypointListScreen.kt),
 * damit beide denselben Ablauf verwenden (Mindestanzahl Wegpunkte prüfen, Foreground-Service
 * starten, Simulator starten) statt die Logik doppelt zu pflegen. Geschwindigkeit/Jitter stehen
 * bereits als geteilter Zustand in MockLocationController, müssen hier also nicht mehr gesetzt
 * werden - nur der aktuelle Track (aus TrackRepository) wird übernommen.
 *
 * @return true wenn die Simulation gestartet wurde, false wenn zu wenige Wegpunkte vorhanden sind.
 */
fun startSimulation(context: Context): Boolean {
    val waypoints = TrackRepository.waypoints.value
    if (waypoints.size < 2) return false
    MockLocationController.setTrack(waypoints)
    MockLocationService.start(context)
    MockLocationController.start()
    MockLocationController.markServiceActive(true)
    return true
}

/**
 * Analog [startSimulation], aber für die beschleunigte Wiedergabe einer Aufzeichnung (siehe
 * TrackerScreen.kt) - eigene Funktion statt Parameter an startSimulation(), weil hier zusätzlich
 * der Beschleunigungsfaktor mitgegeben wird und die Punkte nicht aus TrackRepository kommen.
 *
 * @return true wenn die Wiedergabe gestartet wurde, false wenn zu wenige Punkte vorhanden sind.
 */
fun startRecordedPlayback(context: Context, points: List<RecordedPoint>, accelerationFactor: Double): Boolean {
    if (points.size < 2) return false
    MockLocationController.setAccelerationFactor(accelerationFactor)
    MockLocationController.setRecordedTrack(points)
    MockLocationService.start(context)
    MockLocationController.start()
    MockLocationController.markServiceActive(true)
    return true
}

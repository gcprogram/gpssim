package com.gcprogram.gpssim.location

import android.content.Context
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

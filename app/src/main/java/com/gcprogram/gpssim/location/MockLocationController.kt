package com.gcprogram.gpssim.location

import com.gcprogram.gpssim.geo.SimulatedPosition
import com.gcprogram.gpssim.geo.TrackPoint
import com.gcprogram.gpssim.geo.TrackSimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/**
 * Prozessweiter Singleton, der den TrackSimulator hält. UI (MapScreen) und
 * MockLocationService greifen beide auf diese Instanz zu: die UI steuert
 * Track/Play/Pause, der Service liest currentPosition und schreibt sie über
 * LocationManager als Mock-Location in den GPS-Provider.
 */
object MockLocationController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val simulator = TrackSimulator(scope)

    val isRunning: StateFlow<Boolean> get() = simulator.isRunning
    val currentPosition: StateFlow<SimulatedPosition?> get() = simulator.currentPosition

    fun setTrack(points: List<TrackPoint>) = simulator.setTrack(points)
    fun setSpeedMps(speed: Double) = simulator.setSpeedMps(speed)
    fun start() = simulator.start()
    fun stop() = simulator.stop()
}

package com.gcprogram.gpssim.location

import com.gcprogram.gpssim.geo.SimulatedPosition
import com.gcprogram.gpssim.geo.SpeedPreset
import com.gcprogram.gpssim.geo.TrackPoint
import com.gcprogram.gpssim.geo.TrackSimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    // GPS-Jitter: ±5 m zufälliger Versatz, den MockLocationService NUR auf die an das OS
    // gemeldete Position anwendet (der simulierte Track/Marker selbst bleibt exakt) - simuliert
    // die übliche Ungenauigkeit echter GPS-Empfänger.
    private val _jitterEnabled = MutableStateFlow(false)
    val jitterEnabled: StateFlow<Boolean> = _jitterEnabled.asStateFlow()
    fun setJitterEnabled(enabled: Boolean) {
        _jitterEnabled.value = enabled
    }

    fun setTrack(points: List<TrackPoint>) = simulator.setTrack(points)
    fun setSpeedMps(speed: Double) = simulator.setSpeedMps(speed)
    fun setSpeedPreset(preset: SpeedPreset) = simulator.setSpeedPreset(preset)
    fun start() = simulator.start()
    fun stop() = simulator.stop()
}

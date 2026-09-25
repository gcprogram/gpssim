package com.gcprogram.gpssim.geo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Simuliert eine lineare Bewegung entlang eines Tracks (einer Liste von Wegpunkten)
 * mit konstanter Geschwindigkeit. Läuft als Coroutine mit 1-Sekunden-Updates und
 * bleibt am letzten Punkt stehen (kein Loop).
 */
class TrackSimulator(private val scope: CoroutineScope) {

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _currentPosition = MutableStateFlow<SimulatedPosition?>(null)
    val currentPosition: StateFlow<SimulatedPosition?> = _currentPosition.asStateFlow()

    private var track: List<TrackPoint> = emptyList()
    private var speedMps: Double = 1.4 // ~5 km/h Fußgänger-Default
    private var job: Job? = null

    // Fortschritt entlang des aktuellen Segments
    private var segmentIndex = 0
    private var distanceIntoSegment = 0.0

    fun setTrack(points: List<TrackPoint>) {
        track = points
        segmentIndex = 0
        distanceIntoSegment = 0.0
        if (points.isNotEmpty()) {
            emitPosition(points.first(), bearingTo = points.getOrNull(1))
        }
    }

    fun setSpeedMps(speed: Double) {
        speedMps = speed.coerceAtLeast(0.1)
    }

    fun start() {
        if (track.size < 2) return
        if (_isRunning.value) return
        _isRunning.value = true
        job = scope.launch {
            val tickMillis = 1000L
            while (isActive && _isRunning.value) {
                advance(speedMps * (tickMillis / 1000.0))
                delay(tickMillis)
            }
        }
    }

    fun stop() {
        _isRunning.value = false
        job?.cancel()
        job = null
    }

    fun reset() {
        stop()
        segmentIndex = 0
        distanceIntoSegment = 0.0
        track.firstOrNull()?.let { emitPosition(it, bearingTo = track.getOrNull(1)) }
    }

    private fun advance(deltaMeters: Double) {
        if (segmentIndex >= track.size - 1) {
            stop()
            return
        }
        var remaining = deltaMeters
        var from = track[segmentIndex]
        var to = track[segmentIndex + 1]
        var segLen = GeoMath.distanceMeters(from.latitude, from.longitude, to.latitude, to.longitude)

        distanceIntoSegment += remaining

        while (distanceIntoSegment >= segLen) {
            distanceIntoSegment -= segLen
            segmentIndex++
            if (segmentIndex >= track.size - 1) {
                distanceIntoSegment = 0.0
                emitPosition(track.last(), bearingTo = null)
                stop()
                return
            }
            from = track[segmentIndex]
            to = track[segmentIndex + 1]
            segLen = GeoMath.distanceMeters(from.latitude, from.longitude, to.latitude, to.longitude)
        }

        val t = if (segLen > 0) distanceIntoSegment / segLen else 0.0
        val (lat, lon) = GeoMath.interpolate(from.latitude, from.longitude, to.latitude, to.longitude, t)
        emitPosition(TrackPoint(lat, lon), bearingTo = to)
    }

    private fun emitPosition(point: TrackPoint, bearingTo: TrackPoint?) {
        val bearing = bearingTo?.let {
            GeoMath.bearingDegrees(point.latitude, point.longitude, it.latitude, it.longitude)
        } ?: 0f
        _currentPosition.value = SimulatedPosition(
            latitude = point.latitude,
            longitude = point.longitude,
            bearing = bearing,
            speedMps = if (_isRunning.value) speedMps.toFloat() else 0f
        )
    }
}

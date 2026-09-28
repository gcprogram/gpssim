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
 * mit konstanter Geschwindigkeit (oder im Rocket-Modus: Teleport + langsame Endanflug-Strecke).
 * Läuft als Coroutine mit 1-Sekunden-Updates und bleibt am letzten Punkt stehen (kein Loop).
 */
class TrackSimulator(private val scope: CoroutineScope) {

    companion object {
        /** Ab dieser Segment-Länge teleportiert der Rocket-Modus statt die ganze Strecke abzufliegen. */
        private const val ROCKET_APPROACH_METERS = 100.0

        /** Geschwindigkeit für die letzten 100 m im Rocket-Modus (bzw. für ganz kurze Segmente) - Fahrradtempo. */
        private const val ROCKET_APPROACH_KMH = 18.0
    }

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _currentPosition = MutableStateFlow<SimulatedPosition?>(null)
    val currentPosition: StateFlow<SimulatedPosition?> = _currentPosition.asStateFlow()

    private var track: List<TrackPoint> = emptyList()
    private var speedMps: Double = 5.0 / 3.6 // Fußgänger-Default
    private var rocketMode: Boolean = false
    private var job: Job? = null

    // Fortschritt entlang des aktuellen Segments
    private var segmentIndex = 0
    private var distanceIntoSegment = 0.0

    fun setTrack(points: List<TrackPoint>) {
        track = points
        segmentIndex = 0
        distanceIntoSegment = 0.0
        applyRocketSkipIfNeeded()
        emitCurrentInterpolatedPosition()
    }

    /** Für individuelle/zukünftige Geschwindigkeitseingaben - deaktiviert den Rocket-Modus. */
    fun setSpeedMps(speed: Double) {
        rocketMode = false
        speedMps = speed.coerceAtLeast(0.1)
    }

    /** Setzt die Geschwindigkeit über eine der vier UI-Voreinstellungen (siehe SpeedPreset). */
    fun setSpeedPreset(preset: SpeedPreset) {
        if (preset == SpeedPreset.ROCKET) {
            rocketMode = true
            speedMps = ROCKET_APPROACH_KMH / 3.6
        } else {
            rocketMode = false
            speedMps = (preset.kmh ?: 5.0) / 3.6
        }
        // Falls der Track schon gesetzt ist (Preset-Wechsel vor erneutem Play), Sprungpunkt neu berechnen
        applyRocketSkipIfNeeded()
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
        applyRocketSkipIfNeeded()
        emitCurrentInterpolatedPosition()
    }

    /** Emittiert die Position, die (segmentIndex, distanceIntoSegment) gerade beschreibt. */
    private fun emitCurrentInterpolatedPosition() {
        if (track.size < 2) {
            track.firstOrNull()?.let { emitPosition(it, bearingTo = null) }
            return
        }
        val from = track[segmentIndex]
        val to = track[segmentIndex + 1]
        val segLen = GeoMath.distanceMeters(from.latitude, from.longitude, to.latitude, to.longitude)
        val t = if (segLen > 0) distanceIntoSegment / segLen else 0.0
        val (lat, lon) = GeoMath.interpolate(from.latitude, from.longitude, to.latitude, to.longitude, t)
        emitPosition(TrackPoint(lat, lon), bearingTo = to)
    }

    /**
     * Im Rocket-Modus: springt beim Betreten eines neuen Segments sofort bis auf
     * ROCKET_APPROACH_METERS an den Zielpunkt heran (kein sichtbarer Zwischenschritt) -
     * die eigentliche Simulation legt danach nur noch die letzten 100 m in Fahrrad-
     * geschwindigkeit zurück. Ist das Segment kürzer als 100 m, bleibt distanceIntoSegment
     * bei 0 und die komplette (kurze) Strecke wird regulär in Fahrradgeschwindigkeit gefahren.
     */
    private fun applyRocketSkipIfNeeded() {
        if (!rocketMode) return
        if (track.size < 2 || segmentIndex >= track.size - 1) return
        val from = track[segmentIndex]
        val to = track[segmentIndex + 1]
        val segLen = GeoMath.distanceMeters(from.latitude, from.longitude, to.latitude, to.longitude)
        distanceIntoSegment = if (segLen > ROCKET_APPROACH_METERS) segLen - ROCKET_APPROACH_METERS else 0.0
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
            if (rocketMode) {
                // Neues Segment betreten - im Rocket-Modus direkt bis zum Endanflug vorspulen.
                // Der Sprung ist "kostenlos" (Teleport), daher hier raus aus der Schleife statt
                // den Rest des aktuellen Ticks noch zusätzlich zu verbrauchen.
                applyRocketSkipIfNeeded()
                break
            }
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
            speedMps = if (_isRunning.value) speedMps.toFloat() else 0f,
            segmentIndex = segmentIndex
        )
    }
}

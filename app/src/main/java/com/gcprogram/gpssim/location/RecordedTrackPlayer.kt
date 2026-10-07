package com.gcprogram.gpssim.location

import com.gcprogram.gpssim.geo.GeoMath
import com.gcprogram.gpssim.geo.RecordedPoint
import com.gcprogram.gpssim.geo.SimulatedPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Spielt eine Aufzeichnung (TrackRecorder/RecordedTrackImporter) mit der ORIGINALEN
 * Zeitdynamik ab, nur komprimiert um einen Beschleunigungsfaktor - Pausen und Tempowechsel aus
 * der echten Aufzeichnung bleiben spürbar, laufen nur schneller ab (z.B. Faktor 10 => eine
 * 2-Stunden-Wanderung läuft in 12 Minuten ab). Das unterscheidet sich bewusst von
 * [com.gcprogram.gpssim.geo.TrackSimulator], der Wegpunkte mit KONSTANTER Geschwindigkeit
 * abfährt, ohne Rücksicht auf reale Zeitabstände.
 *
 * Läuft als eigene Coroutine mit 500-ms-Updates (feiner als TrackSimulator, damit auch bei
 * hohem Faktor noch ein paar Zwischenschritte pro Originalsegment sichtbar bleiben).
 */
class RecordedTrackPlayer(private val scope: CoroutineScope) {

    companion object {
        private const val TICK_MILLIS = 500L
    }

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _currentPosition = MutableStateFlow<SimulatedPosition?>(null)
    val currentPosition: StateFlow<SimulatedPosition?> = _currentPosition.asStateFlow()

    private val _accelerationFactor = MutableStateFlow(10.0)
    val accelerationFactor: StateFlow<Double> = _accelerationFactor.asStateFlow()
    fun setAccelerationFactor(factor: Double) {
        _accelerationFactor.value = factor.coerceAtLeast(0.1)
    }

    private var track: List<RecordedPoint> = emptyList()
    private var virtualElapsedMs: Long = 0L
    private var job: Job? = null

    /** Lädt einen neuen Track und setzt die Wiedergabeposition auf den Anfang zurück. */
    fun setTrack(points: List<RecordedPoint>) {
        stop()
        track = points
        virtualElapsedMs = 0L
        emitAt(0L)
    }

    fun start() {
        if (track.size < 2) return
        if (_isRunning.value) return
        _isRunning.value = true
        job = scope.launch {
            while (isActive && _isRunning.value) {
                val totalMs = (track.last().timestampMillis - track.first().timestampMillis).coerceAtLeast(0L)
                virtualElapsedMs += (TICK_MILLIS * _accelerationFactor.value).toLong()
                if (virtualElapsedMs >= totalMs) {
                    emitAt(totalMs)
                    stop()
                    return@launch
                }
                emitAt(virtualElapsedMs)
                delay(TICK_MILLIS)
            }
        }
    }

    /** Hält die Wiedergabe an der aktuellen Stelle an - resume per start() setzt dort fort. */
    fun stop() {
        _isRunning.value = false
        job?.cancel()
        job = null
    }

    /** Zurück an den Anfang des geladenen Tracks (gestoppt). */
    fun reset() {
        stop()
        virtualElapsedMs = 0L
        emitAt(0L)
    }

    private fun emitAt(elapsedMs: Long) {
        if (track.size < 2) {
            val only = track.firstOrNull() ?: return
            _currentPosition.value = SimulatedPosition(
                latitude = only.latitude, longitude = only.longitude,
                bearing = 0f, speedMps = 0f, segmentIndex = 0
            )
            return
        }
        val startTime = track.first().timestampMillis
        val targetTime = startTime + elapsedMs

        // Bracket-Punkte für targetTime finden - lineare Suche reicht, Aufzeichnungen haben
        // typischerweise ein paar hundert bis wenige tausend Punkte, kein Hot Path bei 2 Hz.
        var idx = 0
        while (idx < track.size - 2 && track[idx + 1].timestampMillis < targetTime) idx++

        val from = track[idx]
        val to = track[idx + 1]
        val segMs = (to.timestampMillis - from.timestampMillis).coerceAtLeast(1)
        val t = ((targetTime - from.timestampMillis).toDouble() / segMs).coerceIn(0.0, 1.0)
        val (lat, lon) = GeoMath.interpolate(from.latitude, from.longitude, to.latitude, to.longitude, t)
        val bearing = GeoMath.bearingDegrees(from.latitude, from.longitude, to.latitude, to.longitude)
        val segDistance = GeoMath.distanceMeters(from.latitude, from.longitude, to.latitude, to.longitude)
        val segSeconds = segMs / 1000.0
        val speed = if (segSeconds > 0) (segDistance / segSeconds).toFloat() else 0f

        _currentPosition.value = SimulatedPosition(
            latitude = lat, longitude = lon, bearing = bearing,
            speedMps = if (_isRunning.value) speed else 0f, segmentIndex = idx
        )
    }
}

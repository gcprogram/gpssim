package com.gcprogram.gpssim.location

import com.gcprogram.gpssim.geo.GeoMath
import com.gcprogram.gpssim.geo.RecordedPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecordingState { IDLE, RECORDING, PAUSED }

/**
 * Prozessweiter Singleton für die GPS-Aufzeichnung: hält den Zustand (aus/läuft/pausiert) und
 * die bisher gesammelten Punkte. [TrackRecordingService] schreibt hier über [addPoint] echte
 * GPS-Fixes rein, solange der Zustand RECORDING ist; TrackerScreen steuert Start/Pause/Stop und
 * liest die Punkte zum Speichern (GPX/KML) oder zur beschleunigten Wiedergabe.
 *
 * [loadExternal] befüllt dieselbe Punktliste aus einer zuvor gespeicherten Datei (siehe
 * RecordedTrackImporter) - so nutzen Speichern/Laden/Abspielen denselben "aktuellen Track"
 * statt eigener Zwischenspeicher.
 */
object TrackRecorder {
    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _points = MutableStateFlow<List<RecordedPoint>>(emptyList())
    val points: StateFlow<List<RecordedPoint>> = _points.asStateFlow()

    /** Startet eine NEUE Aufzeichnung - verwirft einen vorher geladenen/aufgezeichneten Track. */
    fun start() {
        _points.value = emptyList()
        _state.value = RecordingState.RECORDING
    }

    fun pause() {
        if (_state.value == RecordingState.RECORDING) _state.value = RecordingState.PAUSED
    }

    fun resume() {
        if (_state.value == RecordingState.PAUSED) _state.value = RecordingState.RECORDING
    }

    /** Beendet die Aufzeichnung - die Punkte bleiben erhalten (zum Speichern/Abspielen). */
    fun stop() {
        _state.value = RecordingState.IDLE
    }

    fun clear() {
        _points.value = emptyList()
        _state.value = RecordingState.IDLE
    }

    /** Vom TrackRecordingService aufgerufen, wenn ein neuer echter GPS-Fix eintrifft. */
    fun addPoint(point: RecordedPoint) {
        if (_state.value != RecordingState.RECORDING) return
        _points.value = _points.value + point
    }

    /** Lädt eine zuvor gespeicherte Aufzeichnung (siehe RecordedTrackImporter) zum Abspielen/Weiterbearbeiten. */
    fun loadExternal(points: List<RecordedPoint>) {
        _points.value = points
        _state.value = RecordingState.IDLE
    }

    fun totalDistanceMeters(): Double {
        val pts = _points.value
        if (pts.size < 2) return 0.0
        var sum = 0.0
        for (i in 1 until pts.size) {
            sum += GeoMath.distanceMeters(
                pts[i - 1].latitude, pts[i - 1].longitude, pts[i].latitude, pts[i].longitude
            )
        }
        return sum
    }

    /** Aufgezeichnete Dauer (letzter minus erster Zeitstempel) in Millisekunden. */
    fun durationMillis(): Long {
        val pts = _points.value
        if (pts.size < 2) return 0L
        return pts.last().timestampMillis - pts.first().timestampMillis
    }
}

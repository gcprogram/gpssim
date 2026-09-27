package com.gcprogram.gpssim.geo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Prozessweiter Singleton für die Wegpunktliste der Simulations-Fahrt (Play/Pause-Track).
 * Ersetzt die frühere lokale Liste in MapScreen, damit auch CacheListScreen (Long-Press
 * "alle Wegpunkte eines Caches hinzufügen") und die neue WaypointListScreen (Einzel-Entfernen)
 * denselben Track sehen und ändern können, ohne Zustand über Compose-Navigation zu verlieren.
 */
object TrackRepository {
    private val _waypoints = MutableStateFlow<List<TrackPoint>>(emptyList())
    val waypoints: StateFlow<List<TrackPoint>> = _waypoints.asStateFlow()

    fun add(point: TrackPoint) {
        _waypoints.value = _waypoints.value + point
    }

    fun addAll(points: List<TrackPoint>) {
        _waypoints.value = _waypoints.value + points
    }

    fun removeAt(index: Int) {
        val current = _waypoints.value
        if (index !in current.indices) return
        _waypoints.value = current.toMutableList().apply { removeAt(index) }
    }

    fun clear() {
        _waypoints.value = emptyList()
    }
}

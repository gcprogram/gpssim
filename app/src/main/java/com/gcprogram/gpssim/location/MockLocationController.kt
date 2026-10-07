package com.gcprogram.gpssim.location

import com.gcprogram.gpssim.geo.RecordedPoint
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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Welche der beiden Wiedergabe-Engines gerade aktiv ist (siehe MockLocationController). */
enum class PlaybackEngine { MANUAL, RECORDED }

/**
 * Prozessweiter Singleton, der BEIDE Wiedergabe-Engines hält und nach außen (UI, Mock-Location-
 * Service) als EINE gemeinsame Position/Lauf-Status zusammenführt:
 *  - [simulator] (TrackSimulator) - manuell gesetzte Wegpunkte, konstantes Tempo (Fußgänger/
 *    Fahrrad/Auto/Rakete), wie bisher.
 *  - [recordedPlayer] (RecordedTrackPlayer) - eine Aufzeichnung mit originaler, nur um einen
 *    Faktor beschleunigter Zeitdynamik (siehe TrackRecorder/TrackerScreen).
 * [activeEngine] merkt sich, welche der beiden zuletzt mit einem Track versorgt wurde
 * (setTrack() bzw. setRecordedTrack()) - start()/stop() wirken dann auf genau diese Engine,
 * sodass bestehende Aufrufer (Play-FAB auf der Kartenseite, Play-Button der Wegpunktliste)
 * unverändert funktionieren.
 */
object MockLocationController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val simulator = TrackSimulator(scope)
    val recordedPlayer = RecordedTrackPlayer(scope)

    private val _activeEngine = MutableStateFlow(PlaybackEngine.MANUAL)
    val activeEngine: StateFlow<PlaybackEngine> = _activeEngine.asStateFlow()

    private val _currentPosition = MutableStateFlow<SimulatedPosition?>(null)
    val currentPosition: StateFlow<SimulatedPosition?> = _currentPosition.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    init {
        simulator.currentPosition
            .onEach { pos -> if (_activeEngine.value == PlaybackEngine.MANUAL) _currentPosition.value = pos }
            .launchIn(scope)
        recordedPlayer.currentPosition
            .onEach { pos -> if (_activeEngine.value == PlaybackEngine.RECORDED) _currentPosition.value = pos }
            .launchIn(scope)
        simulator.isRunning
            .onEach { running -> if (_activeEngine.value == PlaybackEngine.MANUAL) _isRunning.value = running }
            .launchIn(scope)
        recordedPlayer.isRunning
            .onEach { running -> if (_activeEngine.value == PlaybackEngine.RECORDED) _isRunning.value = running }
            .launchIn(scope)
    }

    // Ob der Mock-Location-Provider/Foreground-Service überhaupt aktiv ist - unabhängig davon,
    // ob der Track gerade läuft oder pausiert ist (isRunning). Getrennt von isRunning, damit UI
    // (MapScreen, WaypointListScreen, TrackerScreen) einen dritten Zustand "pausiert" abbilden
    // kann: Service an, Track steht. Gilt für beide Engines gleichermaßen.
    private val _serviceActive = MutableStateFlow(false)
    val serviceActive: StateFlow<Boolean> = _serviceActive.asStateFlow()
    fun markServiceActive(active: Boolean) {
        _serviceActive.value = active
    }

    // GPS-Jitter: ±5 m zufälliger Versatz, den MockLocationService NUR auf die an das OS
    // gemeldete Position anwendet (der simulierte Track/Marker selbst bleibt exakt) - simuliert
    // die übliche Ungenauigkeit echter GPS-Empfänger. Gilt für beide Engines.
    private val _jitterEnabled = MutableStateFlow(false)
    val jitterEnabled: StateFlow<Boolean> = _jitterEnabled.asStateFlow()
    fun setJitterEnabled(enabled: Boolean) {
        _jitterEnabled.value = enabled
    }

    // Geteilter Zustand statt lokalem UI-State in MapScreen: damit ein Tempo-Wechsel auch
    // WÄHREND einer laufenden Simulation sofort greift (TrackSimulator liest speedMps bei
    // jedem Tick neu), und damit Play sowohl vom Karten-FAB als auch vom Play-Button auf der
    // Wegpunktliste dieselbe zuletzt gewählte Geschwindigkeit verwendet. Gilt nur für die
    // manuelle Engine - die aufgezeichnete Wiedergabe hat ihren eigenen Beschleunigungsfaktor.
    private val _speedPreset = MutableStateFlow(SpeedPreset.WALK)
    val speedPreset: StateFlow<SpeedPreset> = _speedPreset.asStateFlow()

    // -- Manuelle Wegpunkt-Engine (TrackSimulator) - unverändertes Verhalten für bestehende UI --
    fun setTrack(points: List<TrackPoint>) {
        _activeEngine.value = PlaybackEngine.MANUAL
        simulator.setTrack(points)
    }
    fun setSpeedMps(speed: Double) = simulator.setSpeedMps(speed)
    fun setSpeedPreset(preset: SpeedPreset) {
        _speedPreset.value = preset
        simulator.setSpeedPreset(preset)
    }

    // -- Aufgezeichnete Engine (RecordedTrackPlayer) --------------------------------------------
    fun setRecordedTrack(points: List<RecordedPoint>) {
        _activeEngine.value = PlaybackEngine.RECORDED
        recordedPlayer.setTrack(points)
    }
    val accelerationFactor: StateFlow<Double> get() = recordedPlayer.accelerationFactor
    fun setAccelerationFactor(factor: Double) = recordedPlayer.setAccelerationFactor(factor)

    // -- Gemeinsame Steuerung: wirkt auf die zuletzt aktivierte Engine ---------------------------
    fun start() {
        when (_activeEngine.value) {
            PlaybackEngine.MANUAL -> simulator.start()
            PlaybackEngine.RECORDED -> recordedPlayer.start()
        }
    }
    fun stop() {
        // Beide stoppen statt nur die aktive - harmlos (die inaktive läuft ohnehin nicht) und
        // verhindert, dass nach einem Engine-Wechsel irgendwo im Hintergrund noch ein Job tickt.
        simulator.stop()
        recordedPlayer.stop()
    }
}

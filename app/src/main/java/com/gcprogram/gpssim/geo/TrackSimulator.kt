package com.gcprogram.gpssim.geo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
import kotlin.random.Random

/**
 * Simuliert eine Bewegung entlang eines Tracks (einer Liste von Wegpunkten) mit einer um einen
 * Soll-Wert (Geschwindigkeits-Preset) herum leicht schwankenden Geschwindigkeit (oder im Rocket-
 * Modus: Teleport + langsame Endanflug-Strecke, dort bewusst OHNE Rampe/Schwankung - ein Teleport
 * hat kein "sanftes Anfahren"). Läuft als Coroutine mit 1-Sekunden-Updates und bleibt am letzten
 * Punkt stehen (kein Loop).
 *
 * Zwei realitätsnähere Effekte zusätzlich zur reinen Soll-Geschwindigkeit (siehe setSpeedPreset()):
 *  - Beschleunigungsrampe: [currentSpeedMps] nähert sich [targetSpeedMps] nur mit begrenzter,
 *    presetabhängiger Beschleunigung an (z.B. Fußgänger sanfter als Auto), statt sofort zu springen.
 *  - Geschwindigkeits-Rauschen: [speedNoiseFactor] driftet pro Tick mit einem kleinen Zufallsschritt
 *    innerhalb eines Bandes um 1.0 - simuliert das natürliche "mal etwas schneller, mal etwas
 *    langsamer" echter Bewegung statt stur konstantem Tempo.
 */
class TrackSimulator(private val scope: CoroutineScope) {

    companion object {
        /** Ab dieser Segment-Länge teleportiert der Rocket-Modus statt die ganze Strecke abzufliegen. */
        private const val ROCKET_APPROACH_METERS = 100.0

        /** Geschwindigkeit für die letzten 100 m im Rocket-Modus (bzw. für ganz kurze Segmente) - Fahrradtempo. */
        private const val ROCKET_APPROACH_KMH = 18.0

        // Presetabhängige Beschleunigung (m/s²) für die Rampe - grobe Alltagswerte, keine Physik-
        // Simulation: ein Fußgänger braucht ein paar Sekunden bis Marschtempo, ein Auto beschleunigt
        // spürbar zügiger.
        private const val ACCEL_WALK = 0.3
        private const val ACCEL_BIKE = 0.8
        private const val ACCEL_CAR = 2.0
        private const val ACCEL_DEFAULT = 1.0

        // Geschwindigkeits-Rauschen: Band ±8% um die Soll-Geschwindigkeit, pro Tick höchstens um
        // diesen Bruchteil verschoben - ergibt ein sanftes Driften statt eines Zitterns.
        private const val SPEED_NOISE_MIN = 0.92
        private const val SPEED_NOISE_MAX = 1.08
        private const val SPEED_NOISE_MAX_STEP = 0.01
    }

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _currentPosition = MutableStateFlow<SimulatedPosition?>(null)
    val currentPosition: StateFlow<SimulatedPosition?> = _currentPosition.asStateFlow()

    private var track: List<TrackPoint> = emptyList()

    // Soll-Geschwindigkeit (aus dem gewählten Preset) vs. tatsächlich gerade gefahrene
    // Geschwindigkeit (ramped) - advance() nutzt currentSpeedMps * speedNoiseFactor, NICHT
    // targetSpeedMps direkt.
    private var targetSpeedMps: Double = 5.0 / 3.6 // Fußgänger-Default
    private var currentSpeedMps: Double = 0.0
    private var accelMps2: Double = ACCEL_WALK
    private var speedNoiseFactor: Double = 1.0
    private var rocketMode: Boolean = false
    private var job: Job? = null

    // Fortschritt entlang des aktuellen Segments
    private var segmentIndex = 0
    private var distanceIntoSegment = 0.0

    fun setTrack(points: List<TrackPoint>) {
        track = points
        segmentIndex = 0
        distanceIntoSegment = 0.0
        // Neue Tour beginnt bei Stillstand - die Rampe fährt beim nächsten start() sanft hoch,
        // statt sofort mit der vollen Soll-Geschwindigkeit loszuspringen.
        currentSpeedMps = 0.0
        speedNoiseFactor = 1.0
        applyRocketSkipIfNeeded()
        emitCurrentInterpolatedPosition()
    }

    /** Für individuelle/zukünftige Geschwindigkeitseingaben - deaktiviert den Rocket-Modus. */
    fun setSpeedMps(speed: Double) {
        rocketMode = false
        targetSpeedMps = speed.coerceAtLeast(0.1)
        accelMps2 = ACCEL_DEFAULT
    }

    /** Setzt die Geschwindigkeit über eine der vier UI-Voreinstellungen (siehe SpeedPreset). */
    fun setSpeedPreset(preset: SpeedPreset) {
        if (preset == SpeedPreset.ROCKET) {
            // Der Teleport-Sprung selbst ist ohnehin unrealistisch - eine Beschleunigungsrampe
            // oder Tempo-Schwankung auf der kurzen Endanflug-Strecke danach wäre nur verwirrend,
            // daher hier bewusst keine Rampe: Soll- und Ist-Geschwindigkeit sofort gleichsetzen.
            rocketMode = true
            targetSpeedMps = ROCKET_APPROACH_KMH / 3.6
            currentSpeedMps = targetSpeedMps
        } else {
            rocketMode = false
            targetSpeedMps = (preset.kmh ?: 5.0) / 3.6
            accelMps2 = when (preset) {
                SpeedPreset.WALK -> ACCEL_WALK
                SpeedPreset.BIKE -> ACCEL_BIKE
                SpeedPreset.CAR -> ACCEL_CAR
                else -> ACCEL_DEFAULT
            }
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
            val tickSeconds = tickMillis / 1000.0
            while (isActive && _isRunning.value) {
                if (!rocketMode) {
                    currentSpeedMps = moveToward(currentSpeedMps, targetSpeedMps, accelMps2 * tickSeconds)
                    speedNoiseFactor = randomWalk(speedNoiseFactor, SPEED_NOISE_MAX_STEP, SPEED_NOISE_MIN, SPEED_NOISE_MAX)
                } else {
                    currentSpeedMps = targetSpeedMps
                    speedNoiseFactor = 1.0
                }
                advance(currentSpeedMps * speedNoiseFactor * tickSeconds)
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
        currentSpeedMps = 0.0
        speedNoiseFactor = 1.0
        applyRocketSkipIfNeeded()
        emitCurrentInterpolatedPosition()
    }

    /** Nähert `current` an `target` an, höchstens um `maxDelta` pro Aufruf - die Beschleunigungsrampe. */
    private fun moveToward(current: Double, target: Double, maxDelta: Double): Double {
        val diff = target - current
        if (abs(diff) <= maxDelta) return target
        return current + maxDelta * sign(diff)
    }

    /** Kleiner Zufallsschritt um `current`, auf [min, max] begrenzt - sanftes Driften statt Zittern. */
    private fun randomWalk(current: Double, maxStep: Double, min: Double, max: Double): Double {
        val delta = (Random.nextDouble() * 2.0 - 1.0) * maxStep
        return (current + delta).coerceIn(min, max)
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
            speedMps = if (_isRunning.value) (currentSpeedMps * speedNoiseFactor).toFloat() else 0f,
            segmentIndex = segmentIndex
        )
    }
}

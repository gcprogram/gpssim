package com.gcprogram.gpssim.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Foreground-Service, der die von MockLocationController berechnete Position
 * per LocationManager-Test-Provider als GPS-Position ausgibt. Setzt voraus,
 * dass die App in den Entwickleroptionen als "Mock location app" gewählt ist.
 *
 * WICHTIG für das An/Aus-Verhalten der Simulation (gesteuert über den Play/Pause-Button
 * in MapScreen, siehe MockLocationController.start()/stop() + MockLocationService.start()/stop()):
 * addTestProvider()/setTestProviderEnabled() passiert NUR hier in onCreate(), wenn der Service
 * tatsächlich gestartet wird - also erst wenn Play gedrückt wird. removeTestProvider() passiert
 * in onDestroy(), also beim Stoppen (Pause). Solange die Simulation nicht läuft, ist GPS_PROVIDER
 * ganz normal die echte Geräteposition; erst während einer laufenden Simulation "kapert" die App
 * GPS_PROVIDER systemweit für alle Apps. Play/Pause ist damit bereits der An/Aus-Schalter für die
 * eigentliche Mock-Funktion, nicht nur für die Bewegungssimulation.
 */
class MockLocationService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private lateinit var locationManager: LocationManager
    private var providerReady = false

    // Aktueller Genauigkeits-"Radius" (Jitter) - driftet langsam innerhalb von JITTER_MIN/MAX_METERS
    // statt fest bei einem Wert zu stehen (siehe pushMockLocation()/randomWalkJitter()), ähnlich wie
    // sich die gemeldete Genauigkeit eines echten GPS-Empfängers je nach Satellitensicht laufend
    // etwas ändert. Startet in der Mitte des Bandes.
    private var currentJitterRadius = (JITTER_MIN_METERS + JITTER_MAX_METERS) / 2.0

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        startForeground(NOTIFICATION_ID, buildNotification())
        setupMockProvider()

        MockLocationController.currentPosition
            .onEach { position ->
                if (position != null && providerReady) {
                    pushMockLocation(position.latitude, position.longitude, position.bearing, position.speedMps)
                }
            }
            .launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.launch {}.cancel()
        tearDownMockProvider()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setupMockProvider() {
        try {
            @Suppress("DEPRECATION")
            locationManager.addTestProvider(
                LocationManager.GPS_PROVIDER,
                /* requiresNetwork = */ false,
                /* requiresSatellite = */ false,
                /* requiresCell = */ false,
                /* hasMonetaryCost = */ false,
                /* supportsAltitude = */ true,
                /* supportsSpeed = */ true,
                /* supportsBearing = */ true,
                /* powerRequirement = */ Criteria.POWER_LOW,
                /* accuracy = */ Criteria.ACCURACY_FINE
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Kein Mock-Location-Provider erlaubt. Ist die App in den Entwickleroptionen als Mock-App gewählt?", e)
            providerReady = false
            return
        } catch (e: IllegalArgumentException) {
            // Provider existiert evtl. schon (z.B. Service-Restart) - weiter versuchen
        }
        try {
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            providerReady = true
        } catch (e: SecurityException) {
            Log.e(TAG, "setTestProviderEnabled fehlgeschlagen", e)
            providerReady = false
        }
    }

    private fun pushMockLocation(lat: Double, lon: Double, bearing: Float, speed: Float) {
        try {
            val jitterOn = MockLocationController.jitterEnabled.value
            if (jitterOn) {
                // Genauigkeit vor jedem Update leicht weiterdriften lassen statt fest zu stehen -
                // siehe currentJitterRadius oben.
                currentJitterRadius = randomWalkJitter(currentJitterRadius)
            }
            val (reportedLat, reportedLon) = if (jitterOn) applyJitter(lat, lon, currentJitterRadius) else lat to lon
            val location = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = reportedLat
                longitude = reportedLon
                altitude = 0.0
                // Gemeldete Genauigkeit entspricht dem tatsächlich angewandten Jitter-Radius - ein
                // Tool, das den accuracy-Wert anzeigt (z.B. Geocaching-Apps), sieht also eine
                // plausibel schwankende Zahl statt eines stur konstanten Werts.
                accuracy = if (jitterOn) currentJitterRadius.toFloat() else 5f
                this.bearing = bearing
                this.speed = speed
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
        } catch (e: SecurityException) {
            Log.e(TAG, "setTestProviderLocation fehlgeschlagen - Mock-Location-Berechtigung verloren?", e)
        }
    }

    /** Kleiner Zufallsschritt um `current`, auf [JITTER_MIN_METERS, JITTER_MAX_METERS] begrenzt. */
    private fun randomWalkJitter(current: Double): Double {
        val delta = (Random.nextDouble() * 2.0 - 1.0) * JITTER_STEP_METERS
        return (current + delta).coerceIn(JITTER_MIN_METERS, JITTER_MAX_METERS)
    }

    /**
     * Zufälliger Versatz innerhalb eines Kreises mit `radiusMeters` Radius, gleichverteilt über
     * die Fläche (sqrt(random) statt random als Radius-Faktor) - simuliert die übliche
     * Positionsungenauigkeit echter GPS-Empfänger. Wirkt NUR auf die gemeldete Position, der
     * simulierte Track/Marker im UI bleibt exakt auf dem gewählten Weg.
     */
    private fun applyJitter(lat: Double, lon: Double, radiusMeters: Double): Pair<Double, Double> {
        val actualRadius = sqrt(Random.nextDouble()) * radiusMeters
        val angle = Random.nextDouble(0.0, 2 * PI)
        val dLat = (actualRadius * cos(angle)) / METERS_PER_DEGREE_LAT
        val metersPerDegreeLon = METERS_PER_DEGREE_LAT * cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val dLon = (actualRadius * sin(angle)) / metersPerDegreeLon
        return (lat + dLat) to (lon + dLon)
    }

    private fun tearDownMockProvider() {
        try {
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            // Provider war evtl. nie erfolgreich angelegt - ignorieren
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "GPS Simulation", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Simulator aktiv")
            .setContentText("Simulierte Position wird als GPS-Standort ausgegeben")
            .setSmallIcon(com.gcprogram.gpssim.R.drawable.ic_stat_gps_sim)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "MockLocationService"
        private const val CHANNEL_ID = "gps_sim_channel"
        private const val NOTIFICATION_ID = 1001
        // Band, innerhalb dessen die gemeldete Genauigkeit langsam driftet (siehe currentJitterRadius) -
        // 2-12 m deckt die übliche Schwankungsbreite eines Smartphone-GPS-Empfängers unter freiem
        // Himmel ab; JITTER_STEP_METERS begrenzt die Drift pro Positions-Update auf ein sanftes Maß.
        private const val JITTER_MIN_METERS = 2.0
        private const val JITTER_MAX_METERS = 12.0
        private const val JITTER_STEP_METERS = 0.6
        private const val METERS_PER_DEGREE_LAT = 111320.0

        fun start(context: Context) {
            val intent = Intent(context, MockLocationService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MockLocationService::class.java))
        }
    }
}

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
            val (reportedLat, reportedLon) = if (jitterOn) applyJitter(lat, lon) else lat to lon
            val location = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = reportedLat
                longitude = reportedLon
                altitude = 0.0
                accuracy = if (jitterOn) JITTER_MAX_METERS.toFloat() else 5f
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

    /**
     * Zufälliger Versatz innerhalb eines Kreises mit JITTER_MAX_METERS Radius, gleichverteilt
     * über die Fläche (sqrt(random) statt random als Radius-Faktor) - simuliert die übliche
     * Positionsungenauigkeit echter GPS-Empfänger. Wirkt NUR auf die gemeldete Position, der
     * simulierte Track/Marker im UI bleibt exakt auf dem gewählten Weg.
     */
    private fun applyJitter(lat: Double, lon: Double): Pair<Double, Double> {
        val radiusMeters = sqrt(Random.nextDouble()) * JITTER_MAX_METERS
        val angle = Random.nextDouble(0.0, 2 * PI)
        val dLat = (radiusMeters * cos(angle)) / METERS_PER_DEGREE_LAT
        val metersPerDegreeLon = METERS_PER_DEGREE_LAT * cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val dLon = (radiusMeters * sin(angle)) / metersPerDegreeLon
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
        private const val JITTER_MAX_METERS = 5.0
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

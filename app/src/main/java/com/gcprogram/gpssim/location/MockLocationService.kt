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

/**
 * Foreground-Service, der die von MockLocationController berechnete Position
 * per LocationManager-Test-Provider als GPS-Position ausgibt. Setzt voraus,
 * dass die App in den Entwickleroptionen als "Mock location app" gewählt ist.
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
            val location = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = lat
                longitude = lon
                altitude = 0.0
                accuracy = 5f
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
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "MockLocationService"
        private const val CHANNEL_ID = "gps_sim_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, MockLocationService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MockLocationService::class.java))
        }
    }
}

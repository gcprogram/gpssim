package com.gcprogram.gpssim.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gcprogram.gpssim.R
import com.gcprogram.gpssim.geo.RecordedPoint
import com.gcprogram.gpssim.recording.AutosaveStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground-Service für die GPS-Aufzeichnung - läuft unabhängig von der sichtbaren App weiter
 * (analog MockLocationService), damit eine Wanderung/Tour auch bei gesperrtem Bildschirm oder im
 * Hintergrund zuverlässig aufgezeichnet wird. Schreibt eintreffende Fixes in [TrackRecorder];
 * ob sie tatsächlich übernommen werden (nur während RecordingState.RECORDING), entscheidet der
 * Recorder selbst.
 *
 * Schreibt zusätzlich alle paar Minuten eine automatische Zwischensicherung (siehe
 * [AutosaveStore]) sowie einmal beim Beenden - ein Sicherheitsnetz, falls der Prozess vor dem
 * bewussten "Speichern" endet (App "Kraft stoppen", Akku-Management, Absturz).
 */
class TrackRecordingService : Service() {

    private lateinit var locationManager: LocationManager
    private var listener: LocationListener? = null
    private val serviceScope = CoroutineScope(SupervisorJob())
    private var autosaveJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        startForeground(NOTIFICATION_ID, buildNotification())
        registerListener()
        startAutosaveLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        listener?.let { l -> runCatching { locationManager.removeUpdates(l) } }
        // Letzte Sicherung beim regulären Stopp - danach löscht TrackerScreen sie wieder, sobald
        // bewusst exportiert oder verworfen wurde (siehe AutosaveStore.clear()).
        writeAutosave()
        autosaveJob?.cancel()
        serviceScope.cancel()
    }

    private fun startAutosaveLoop() {
        autosaveJob = serviceScope.launch {
            while (isActive) {
                delay(AUTOSAVE_INTERVAL_MS)
                writeAutosave()
            }
        }
    }

    private fun writeAutosave() {
        AutosaveStore.write(this, TrackRecorder.points.value, TrackRecorder.trackName.value)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerListener() {
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return

        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                // Während einer laufenden SIMULATION liefert GPS_PROVIDER systemweit die
                // gefälschte Position (siehe MockLocationService) - solche Punkte nicht
                // aufzeichnen, sonst nimmt sich der Tracker versehentlich selbst auf. Aufnahme
                // und Simulation gleichzeitig ergibt ohnehin keinen Sinn, aber zur Sicherheit.
                if (location.provider == LocationManager.GPS_PROVIDER && MockLocationController.serviceActive.value) {
                    return
                }
                TrackRecorder.addPoint(
                    RecordedPoint(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        timestampMillis = System.currentTimeMillis(),
                        altitude = if (location.hasAltitude()) location.altitude else null,
                        speedMps = if (location.hasSpeed()) location.speed else null
                    )
                )
            }
        }
        listener = l
        // Aufzeichnungsrate kommt aus TrackRecorder (vor dem Start in TrackerScreen gewählt,
        // z.B. 1/5/20 Sekunden) statt eines festen Werts - siehe TrackRecorder.intervalMillis.
        val minTimeMs = TrackRecorder.intervalMillis.value
        try {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, minTimeMs, MIN_DISTANCE_M, l)
                }
            }
        } catch (e: SecurityException) {
            // Berechtigung evtl. noch nicht final erteilt - Aufzeichnung bleibt dann leer
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Track-Aufzeichnung", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Track wird aufgezeichnet")
            .setContentText("Echte GPS-Position wird aufgezeichnet")
            .setSmallIcon(R.drawable.ic_stat_gps_sim)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "TrackRecordingService"
        private const val CHANNEL_ID = "track_recording_channel"
        private const val NOTIFICATION_ID = 1002
        private const val MIN_DISTANCE_M = 3f
        private const val AUTOSAVE_INTERVAL_MS = 3 * 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackRecordingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackRecordingService::class.java))
        }
    }
}

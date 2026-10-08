package com.gcprogram.gpssim.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gcprogram.gpssim.location.MockLocationController
import com.gcprogram.gpssim.location.MockLocationService
import com.gcprogram.gpssim.location.PlaybackEngine
import com.gcprogram.gpssim.location.RecordingState
import com.gcprogram.gpssim.location.TrackRecorder
import com.gcprogram.gpssim.location.TrackRecordingService
import com.gcprogram.gpssim.location.startRecordedPlayback
import com.gcprogram.gpssim.recording.GpxTrackExporter
import com.gcprogram.gpssim.recording.KmlTrackExporter
import com.gcprogram.gpssim.recording.RecordedTrackImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Neue Seite: eingebauter GPS-Tracker. Drei Funktionsblöcke:
 *  1. Aufzeichnen (Start/Pause/Fortsetzen/Stopp) - nutzt die echte GPS-Position über
 *     TrackRecordingService, Zustand/Punkte liegen im Singleton TrackRecorder.
 *  2. Speichern/Laden als GPX bzw. KML (siehe recording/-Package) über die üblichen
 *     SAF-Dialoge (CreateDocument/OpenDocument), analog zum Offline-Karten-Import in MapScreen.
 *  3. Abspielen - mit beschleunigter, aber zeitlich originalgetreuer Wiedergabe (siehe
 *     RecordedTrackPlayer): Beschleunigungsfaktor wählen, dann startRecordedPlayback() wie bei
 *     der manuellen Simulation, und zurück zur Karte wechseln.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerScreen(onBack: () -> Unit, onPlaybackStarted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val recordingState by TrackRecorder.state.collectAsState()
    val points by TrackRecorder.points.collectAsState()
    val recordingIntervalMillis by TrackRecorder.intervalMillis.collectAsState()
    val activeEngine by MockLocationController.activeEngine.collectAsState()
    val playbackRunning by MockLocationController.isRunning.collectAsState()
    val playbackServiceActive by MockLocationController.serviceActive.collectAsState()
    val playbackProgress by MockLocationController.recordedProgress.collectAsState()
    val currentAccelerationFactor by MockLocationController.accelerationFactor.collectAsState()
    val isRecordedActive = activeEngine == PlaybackEngine.RECORDED && playbackServiceActive

    var trackName by remember {
        mutableStateOf("Track_" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault()).format(Date()))
    }
    var accelerationFactor by remember { mutableStateOf(10.0) }
    var statusText by remember { mutableStateOf<String?>(null) }

    // -- Speichern: GPX bzw. KML, jeweils über den System-Dateiauswahldialog ----------------------
    val saveGpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val xml = GpxTrackExporter.export(points, trackName)
                val ok = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(xml.toByteArray()) }
                }.isSuccess
                withContext(Dispatchers.Main) {
                    statusText = if (ok) "Als GPX gespeichert" else "GPX konnte nicht gespeichert werden"
                }
            }
        }
    }
    val saveKmlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val xml = KmlTrackExporter.export(points, trackName)
                val ok = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(xml.toByteArray()) }
                }.isSuccess
                withContext(Dispatchers.Main) {
                    statusText = if (ok) "Als KML gespeichert" else "KML konnte nicht gespeichert werden"
                }
            }
        }
    }

    // -- Laden: GPX mit <trkpt>-Zeitstempeln wieder einlesen (siehe RecordedTrackImporter) --------
    val loadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    // Manche Anbieter erlauben keine dauerhafte Freigabe - unkritisch, wir lesen sofort.
                }
                val result = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { RecordedTrackImporter.parse(it) }
                        ?: emptyList()
                }
                withContext(Dispatchers.Main) {
                    result.onSuccess { loaded ->
                        if (loaded.size < 2) {
                            statusText = "Datei enthält keine abspielbare Aufzeichnung (mind. 2 Punkte mit Zeitstempel nötig)"
                        } else {
                            TrackRecorder.loadExternal(loaded)
                            statusText = "${loaded.size} Punkt(e) geladen"
                        }
                    }.onFailure {
                        statusText = "Datei konnte nicht gelesen werden: ${it.message}"
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GPS-Tracker") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Zurück zur Karte")
                    }
                }
            )
        }
    ) { padding: PaddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            statusText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
            }

            // -- Block 1: Aufzeichnung --------------------------------------------------------------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Aufzeichnung", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (recordingState) {
                            RecordingState.IDLE -> "Bereit"
                            RecordingState.RECORDING -> "Zeichnet auf…"
                            RecordingState.PAUSED -> "Pausiert"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    // Aufzeichnungsrate (Mindestabstand zwischen zwei GPS-Fixes) - nur im Zustand
                    // IDLE wählbar, siehe TrackRecorder.setIntervalMillis(): ein Wechsel während
                    // RECORDING/PAUSED würde den bereits laufenden LocationManager-Request nicht
                    // mehr erreichen.
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf(1000L to "1s", 5000L to "5s", 20000L to "20s").forEach { (ms, label) ->
                            FilledIconToggleButton(
                                checked = recordingIntervalMillis == ms,
                                enabled = recordingState == RecordingState.IDLE,
                                onCheckedChange = { if (it) TrackRecorder.setIntervalMillis(ms) }
                            ) {
                                Text(label)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        when (recordingState) {
                            RecordingState.IDLE -> {
                                Button(onClick = {
                                    TrackRecorder.start()
                                    TrackRecordingService.start(context)
                                }) {
                                    Icon(Icons.Default.FiberManualRecord, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Start")
                                }
                            }
                            RecordingState.RECORDING -> {
                                Button(onClick = { TrackRecorder.pause() }) {
                                    Icon(Icons.Default.Pause, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Pause")
                                }
                                Button(onClick = {
                                    TrackRecorder.stop()
                                    TrackRecordingService.stop(context)
                                }) {
                                    Icon(Icons.Default.Stop, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Stopp")
                                }
                            }
                            RecordingState.PAUSED -> {
                                Button(onClick = { TrackRecorder.resume() }) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Weiter")
                                }
                                Button(onClick = {
                                    TrackRecorder.stop()
                                    TrackRecordingService.stop(context)
                                }) {
                                    Icon(Icons.Default.Stop, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Stopp")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // -- Statistik ---------------------------------------------------------------------------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Aktuelle Tour", style = MaterialTheme.typography.titleMedium)
                    Text("${points.size} Punkt(e)", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Strecke: ${"%.0f".format(TrackRecorder.totalDistanceMeters())} m",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Dauer: ${TrackRecorder.durationMillis() / 1000} s",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // -- Block 2: Speichern/Laden ------------------------------------------------------------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Speichern / Laden", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = trackName,
                        onValueChange = { trackName = it },
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        OutlinedButton(
                            enabled = points.size >= 2,
                            onClick = { saveGpxLauncher.launch("$trackName.gpx") }
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("GPX")
                        }
                        OutlinedButton(
                            enabled = points.size >= 2,
                            onClick = { saveKmlLauncher.launch("$trackName.kml") }
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("KML")
                        }
                        OutlinedButton(onClick = { loadLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.FileOpen, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Laden")
                        }
                    }
                    if (points.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            IconButton(onClick = { TrackRecorder.clear() }) {
                                Icon(Icons.Default.Delete, contentDescription = "Aufzeichnung verwerfen")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // -- Block 3: Beschleunigte Wiedergabe ----------------------------------------------------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Wiedergabe (beschleunigt)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Die echte Zeitdynamik der Aufzeichnung (Pausen, Tempowechsel) bleibt " +
                            "erhalten, läuft aber um den gewählten Faktor schneller ab.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf(1.0, 5.0, 10.0, 30.0, 60.0).forEach { factor ->
                            FilledIconToggleButton(
                                // Solange die Wiedergabe läuft bzw. pausiert ist, zeigt der Chip den
                                // tatsächlich eingestellten Faktor (geteilter Zustand in
                                // MockLocationController) statt des lokalen Vorwahl-Werts.
                                checked = (if (isRecordedActive) currentAccelerationFactor else accelerationFactor) == factor,
                                onCheckedChange = {
                                    if (it) {
                                        accelerationFactor = factor
                                        if (isRecordedActive) MockLocationController.setAccelerationFactor(factor)
                                    }
                                }
                            ) {
                                Text("${factor.toInt()}x")
                            }
                        }
                    }

                    // Fortschritt/Spulen - erst bedienbar, sobald die Wiedergabe mindestens einmal
                    // gestartet wurde (siehe isRecordedActive): erst dann liegt ein Track in
                    // RecordedTrackPlayer, auf den sich ein Sprung überhaupt bezieht.
                    Spacer(Modifier.height(8.dp))
                    Slider(
                        value = if (isRecordedActive) playbackProgress else 0f,
                        onValueChange = { if (isRecordedActive) MockLocationController.seekRecordedTo(it) },
                        enabled = isRecordedActive,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        if (isRecordedActive) "${(playbackProgress * 100).toInt()} % der Aufzeichnung" else "Noch nicht gestartet",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Play/Pause: vor dem ersten Start beginnt dieser Button die Wiedergabe neu
                        // (Track + Faktor übernehmen, Service starten); danach pausiert/setzt er nur
                        // noch fort - identisch zur Play/Pause-Logik auf der Kartenseite, nur direkt
                        // hier bedienbar, ohne erst zur Karte wechseln zu müssen.
                        Button(
                            enabled = points.size >= 2 && recordingState == RecordingState.IDLE,
                            onClick = {
                                if (!isRecordedActive) {
                                    if (!startRecordedPlayback(context, points, accelerationFactor)) {
                                        statusText = "Mindestens 2 Punkte nötig, um abzuspielen"
                                    }
                                } else if (playbackRunning) {
                                    MockLocationController.stop()
                                } else {
                                    MockLocationController.start()
                                }
                            }
                        ) {
                            Icon(
                                if (isRecordedActive && playbackRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (isRecordedActive && playbackRunning) "Pause" else if (isRecordedActive) "Weiter" else "Abspielen")
                        }
                        if (isRecordedActive) {
                            OutlinedButton(onClick = {
                                MockLocationController.stop()
                                MockLocationService.stop(context)
                                MockLocationController.markServiceActive(false)
                                MockLocationController.resetRecordedPlayback()
                            }) {
                                Icon(Icons.Default.RestartAlt, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Stopp")
                            }
                            OutlinedButton(onClick = onPlaybackStarted) {
                                Icon(Icons.Default.Map, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Zur Karte")
                            }
                        }
                    }
                }
            }
        }
    }
}

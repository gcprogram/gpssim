package com.gcprogram.gpssim.ui

import android.content.Intent
import android.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gcprogram.gpssim.geo.CoordinateParser
import com.gcprogram.gpssim.geo.TrackPoint
import com.gcprogram.gpssim.location.MockLocationController
import com.gcprogram.gpssim.location.MockLocationService
import com.gcprogram.gpssim.offline.OfflineMapManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/** Schaltet die Karte auf eine lokale Mapsforge-.map-Datei um (kein Netzwerkzugriff mehr). */
private fun switchToOfflineMap(mapView: MapView, mapFile: File): Boolean {
    return try {
        val tileSource = MapsForgeTileSource.createFromFiles(arrayOf(mapFile), null, null)
        val provider = MapsForgeTileProvider(SimpleRegisterReceiver(mapView.context), tileSource, null)
        mapView.tileProvider.detach()
        mapView.setTileProvider(provider)
        mapView.setUseDataConnection(false)
        mapView.invalidate()
        true
    } catch (e: Exception) {
        false
    }
}

/** Schaltet zurück auf Online-OSM-Tiles (Mapnik). */
private fun switchToOnlineMap(mapView: MapView) {
    mapView.tileProvider.detach()
    mapView.setTileProvider(MapTileProviderBasic(mapView.context))
    mapView.setTileSource(TileSourceFactory.MAPNIK)
    mapView.setUseDataConnection(true)
    mapView.invalidate()
}

@Composable
fun MapScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Track als geordnete Wegpunktliste - jeder Tap auf die Karte und jede
    // erfolgreich geparste Paste fügt hier einen Punkt an.
    val waypoints = remember { mutableStateListOf<TrackPoint>() }

    var pasteText by remember { mutableStateOf("") }
    var speedKmh by remember { mutableStateOf("5.0") }
    var isRunning by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Kein Track gesetzt") }
    var isOfflineMode by remember { mutableStateOf(false) }
    var offlineMapName by remember { mutableStateOf(OfflineMapManager.currentMapDisplayName(context)) }

    val mapView = remember { MapView(context) }

    // Marker/Polyline-Referenzen, damit wir sie gezielt updaten statt die Karte neu zu zentrieren
    val trackPolyline = remember { Polyline() }
    val currentPositionMarker = remember {
        Marker(mapView).apply {
            title = "Simulierte Position"
        }
    }

    fun redrawTrack() {
        trackPolyline.setPoints(waypoints.map { GeoPoint(it.latitude, it.longitude) })
        mapView.overlays.removeAll { it is Marker && it.id == "waypoint" }
        waypoints.forEachIndexed { index, wp ->
            val marker = Marker(mapView).apply {
                position = GeoPoint(wp.latitude, wp.longitude)
                title = "Wegpunkt ${index + 1}"
                id = "waypoint"
            }
            mapView.overlays.add(marker)
        }
        mapView.invalidate()
    }

    fun addWaypoint(point: TrackPoint) {
        waypoints.add(point)
        redrawTrack()
        MockLocationController.setTrack(waypoints.toList())
        statusText = "${waypoints.size} Wegpunkt(e) gesetzt"
    }

    // Long-Press / Tap auf der Karte setzt einen neuen Wegpunkt - kein Auto-Center hier
    val mapEventsOverlay = remember {
        MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p?.let { addWaypoint(TrackPoint(it.latitude, it.longitude)) }
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                p?.let { addWaypoint(TrackPoint(it.latitude, it.longitude)) }
                return true
            }
        })
    }

    // Dateipicker für Mapsforge-.map-Dateien (SAF, kein READ_EXTERNAL_STORAGE nötig).
    // Die Datei wird gestreamt ins App-Verzeichnis kopiert (OfflineMapManager), danach
    // sofort auf Offline-Darstellung umgeschaltet.
    val pickMapLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    // Manche Dokumentanbieter erlauben keine dauerhafte Freigabe - unkritisch,
                    // wir kopieren die Datei ja sofort ins App-Verzeichnis.
                }
                val importedFile = try {
                    OfflineMapManager.importMap(context, uri)
                } catch (e: Exception) {
                    null
                }
                withContext(Dispatchers.Main) {
                    if (importedFile != null) {
                        offlineMapName = OfflineMapManager.currentMapDisplayName(context)
                        val ok = switchToOfflineMap(mapView, importedFile)
                        isOfflineMode = ok
                        statusText = if (ok) {
                            "Offline-Karte aktiv: $offlineMapName"
                        } else {
                            "Offline-Karte konnte nicht geladen werden (Datei evtl. kein gültiges Mapsforge-.map-Format)"
                        }
                    } else {
                        statusText = "Datei konnte nicht importiert werden"
                    }
                }
            }
        }
    }

    // MapView-Lebenszyklus an Compose/Activity koppeln (onResume/onPause), sonst laufen Tile-Downloads
    // weiter bzw. Kartenstatus wird nicht korrekt gespeichert
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Simulierte Position beobachten und NUR den Marker bewegen - map.controller.setCenter()
    // wird hier bewusst nicht aufgerufen, damit die Karte nicht springt
    androidx.compose.runtime.LaunchedEffect(Unit) {
        MockLocationController.currentPosition.collectLatest { pos ->
            if (pos != null) {
                currentPositionMarker.position = GeoPoint(pos.latitude, pos.longitude)
                currentPositionMarker.rotation = pos.bearing
                if (!mapView.overlays.contains(currentPositionMarker)) {
                    mapView.overlays.add(currentPositionMarker)
                }
                mapView.invalidate()
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        MockLocationController.isRunning.collectLatest { running ->
            isRunning = running
        }
    }

    Scaffold(
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                FloatingActionButton(onClick = {
                    // Expliziter Center-Button - die einzige Stelle, an der wir die Karte bewegen
                    mapView.controller.animateTo(currentPositionMarker.position)
                }) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Auf Position zentrieren")
                }
                Spacer(Modifier.height(4.dp))
                FloatingActionButton(onClick = {
                    if (isRunning) {
                        MockLocationController.stop()
                        MockLocationService.stop(context)
                    } else {
                        if (waypoints.size < 2) {
                            statusText = "Mindestens 2 Wegpunkte nötig (Karte antippen oder Koordinaten einfügen)"
                        } else {
                            val speed = speedKmh.replace(',', '.').toDoubleOrNull() ?: 5.0
                            MockLocationController.setSpeedMps(speed / 3.6)
                            MockLocationController.setTrack(waypoints.toList())
                            MockLocationService.start(context)
                            MockLocationController.start()
                        }
                    }
                }) {
                    Icon(
                        if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isRunning) "Pause" else "Start"
                    )
                }
            }
        }
    ) { padding: PaddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    OutlinedTextField(
                        value = pasteText,
                        onValueChange = { pasteText = it },
                        label = { Text("Koordinaten einfügen, z.B. N49° 12.345 E008° 40.123") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Button(onClick = {
                            val parsed = CoordinateParser.parse(pasteText)
                            if (parsed != null) {
                                addWaypoint(TrackPoint(parsed.latitude, parsed.longitude))
                                mapView.controller.animateTo(GeoPoint(parsed.latitude, parsed.longitude))
                                pasteText = ""
                            } else {
                                statusText = "Koordinaten nicht erkannt - Format N dd° mm.mmm E ddd° mm.mmm erwartet"
                            }
                        }) {
                            Text("Als Wegpunkt hinzufügen")
                        }
                        OutlinedTextField(
                            value = speedKmh,
                            onValueChange = { speedKmh = it },
                            label = { Text("km/h") },
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        Button(onClick = {
                            waypoints.clear()
                            redrawTrack()
                            statusText = "Track geleert"
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "Track leeren")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Button(onClick = { pickMapLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.CloudOff, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (offlineMapName != null) "Andere Offline-Karte" else "Offline-Karte wählen")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (isOfflineMode) "Offline" else "Online", style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = isOfflineMode,
                                enabled = offlineMapName != null,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        val file = OfflineMapManager.currentMapFile(context)
                                        if (file != null && switchToOfflineMap(mapView, file)) {
                                            isOfflineMode = true
                                            statusText = "Offline-Karte aktiv: $offlineMapName"
                                        } else {
                                            statusText = "Keine gültige Offline-Karte vorhanden"
                                        }
                                    } else {
                                        switchToOnlineMap(mapView)
                                        isOfflineMode = false
                                        statusText = "Online-Karte aktiv"
                                    }
                                }
                            )
                        }
                    }
                    Text(statusText, style = MaterialTheme.typography.bodySmall)
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = {
                        mapView.apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(16.0)
                            controller.setCenter(GeoPoint(52.5200, 13.4050)) // Default: Berlin, bis erster Punkt gesetzt ist
                            overlays.add(mapEventsOverlay)
                            overlays.add(trackPolyline)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

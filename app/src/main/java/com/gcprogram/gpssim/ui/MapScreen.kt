package com.gcprogram.gpssim.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconToggleButton
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gcprogram.gpssim.geo.CoordinateParser
import com.gcprogram.gpssim.geo.SpeedPreset
import com.gcprogram.gpssim.geo.TrackPoint
import com.gcprogram.gpssim.location.MockLocationController
import com.gcprogram.gpssim.location.MockLocationService
import com.gcprogram.gpssim.offline.OfflineMapManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mapsforge.map.reader.MapFile
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * Liest den geografischen Abdeckungsbereich einer Mapsforge-.map-Datei aus deren Header.
 * Wird gebraucht, um die Karte nach dem Umschalten auf den tatsächlich abgedeckten Bereich
 * zu zentrieren - sonst bleibt die Ansicht z.B. auf Berlin stehen, während eine Österreich-Karte
 * geladen ist, und es sieht so aus als würde nichts angezeigt (die Kacheln existieren dort einfach nicht).
 */
private fun readMapBounds(mapFile: File): BoundingBox? {
    // Hinweis: die Klasse hieß in älteren Mapsforge-Versionen "MapDatabase" mit
    // openFile()/mapFileInfo - in der hier verwendeten Version (0.18+) heißt sie "MapFile"
    // und liefert die Bounding-Box direkt über boundingBox().
    var mapFileHandle: MapFile? = null
    return try {
        mapFileHandle = MapFile(mapFile)
        val bbox = mapFileHandle.boundingBox()
        BoundingBox(bbox.maxLatitude, bbox.maxLongitude, bbox.minLatitude, bbox.minLongitude)
    } catch (e: Exception) {
        null
    } finally {
        mapFileHandle?.close()
    }
}

/** Schaltet die Karte auf eine lokale Mapsforge-.map-Datei um (kein Netzwerkzugriff mehr). */
private fun switchToOfflineMap(mapView: MapView, mapFile: File): Boolean {
    return try {
        val tileSource = MapsForgeTileSource.createFromFiles(arrayOf(mapFile), null, null)
        val provider = MapsForgeTileProvider(SimpleRegisterReceiver(mapView.context), tileSource, null)
        mapView.tileProvider.detach()
        mapView.setTileProvider(provider)
        mapView.setUseDataConnection(false)
        // Auf den Abdeckungsbereich der Karte zentrieren - sonst bleibt ggf. der Default-
        // Kartenausschnitt (Berlin) stehen, obwohl die Kartendatei einen ganz anderen
        // Bereich abdeckt und dort schlicht nichts anzuzeigen ist.
        readMapBounds(mapFile)?.let { bounds -> mapView.zoomToBoundingBox(bounds, false) }
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

/**
 * Letzte bekannte ECHTE Position (GPS/Netzwerk), als Startpunkt für die Karte.
 * Wichtig: das funktioniert nur, solange die Simulation nicht läuft - sobald
 * MockLocationService aktiv ist, liefert GPS_PROVIDER die simulierte statt der echten
 * Position (siehe Kommentar dort). Beim App-Start ist der Service noch nicht gestartet,
 * daher liefert diese Abfrage hier zuverlässig die echte Position.
 */
private fun lastKnownRealLocation(context: Context): GeoPoint? {
    val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasPermission) return null

    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
    var newest: android.location.Location? = null
    for (provider in providers) {
        try {
            if (!locationManager.isProviderEnabled(provider)) continue
            val loc = locationManager.getLastKnownLocation(provider) ?: continue
            if (newest == null || loc.time > newest.time) newest = loc
        } catch (e: SecurityException) {
            // Berechtigung evtl. noch nicht final erteilt (Race mit dem Permission-Dialog beim
            // allerersten App-Start) - dann bleibt es beim Fallback-Zentrum
        }
    }
    return newest?.let { GeoPoint(it.latitude, it.longitude) }
}

@Composable
fun MapScreen(onOpenCacheList: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Track als geordnete Wegpunktliste - jeder Tap auf die Karte und jede
    // erfolgreich geparste Paste fügt hier einen Punkt an.
    val waypoints = remember { mutableStateListOf<TrackPoint>() }

    var pasteText by remember { mutableStateOf("") }
    var selectedSpeedPreset by remember { mutableStateOf(SpeedPreset.WALK) }
    var jitterEnabled by remember { mutableStateOf(false) }
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

    // Zeigt die aus einer GPX importierten Caches auf der Karte: ohne Auswahl (siehe
    // CacheListScreen) einen Marker pro Cache mit Cache-Typ-Icon an dessen Übersichts-
    // koordinaten (Final falls vorhanden, sonst posted); mit Auswahl stattdessen ALLE
    // Wegpunkte dieses einen Caches - der Cache selbst mit Typ-Icon, alle anderen (Final,
    // Parkplatz, Etappen, Original Coordinates, ...) mit dem blauen Wegpunkt-Marker.
    fun redrawGpxMarkers(caches: List<com.gcprogram.gpssim.geo.GeoCache>, selected: com.gcprogram.gpssim.geo.GeoCache?) {
        mapView.overlays.removeAll { it is Marker && it.id == "gpx" }
        val typeIcon = { type: String? ->
            com.gcprogram.gpssim.geo.MapIconFactory.cacheTypeMarker(context, type)
        }
        val wpIcon = { com.gcprogram.gpssim.geo.MapIconFactory.waypointMarker(context) }

        if (selected == null) {
            for (cache in caches) {
                val marker = Marker(mapView).apply {
                    position = GeoPoint(cache.overviewLatitude, cache.overviewLongitude)
                    title = "${cache.gccode} - ${cache.title}"
                    id = "gpx"
                    icon = typeIcon(cache.cacheType)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }
                mapView.overlays.add(marker)
            }
        } else {
            val cacheMarker = Marker(mapView).apply {
                position = GeoPoint(selected.postedLatitude, selected.postedLongitude)
                title = "${selected.gccode} - ${selected.title}"
                id = "gpx"
                icon = typeIcon(selected.cacheType)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            mapView.overlays.add(cacheMarker)
            for (wp in selected.waypoints) {
                val marker = Marker(mapView).apply {
                    position = GeoPoint(wp.latitude, wp.longitude)
                    title = "${wp.type}: ${wp.title}"
                    id = "gpx"
                    icon = wpIcon()
                }
                mapView.overlays.add(marker)
            }
        }
        mapView.invalidate()
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

    // Cache-/Wegpunkt-Marker aus dem GPX-Import neu zeichnen, sobald sich die Liste oder die
    // Auswahl ändert (z.B. nach Import oder nach Auswahl eines Caches in CacheListScreen).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.flow.combine(
            com.gcprogram.gpssim.gpx.GpxRepository.caches,
            com.gcprogram.gpssim.gpx.GpxRepository.selectedCache
        ) { caches, selected -> caches to selected }
            .collectLatest { (caches, selected) -> redrawGpxMarkers(caches, selected) }
    }

    // Startpunkt der Karte: echte GPS/Netzwerk-Position statt eines festen Orts.
    // Läuft einmalig nach dem ersten Aufbau der Karte (siehe lastKnownRealLocation()
    // für die Einschränkung solange die Simulation noch nicht aktiv ist).
    LaunchedEffect(Unit) {
        lastKnownRealLocation(context)?.let { point ->
            mapView.controller.setCenter(point)
            mapView.invalidate()
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
                            // Reihenfolge wichtig: Preset vor Track setzen, damit der Rocket-Modus
                            // den Sprung zum Endanflug schon beim initialen setTrack() berechnet
                            MockLocationController.setSpeedPreset(selectedSpeedPreset)
                            MockLocationController.setTrack(waypoints.toList())
                            MockLocationController.setJitterEnabled(jitterEnabled)
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
                        Button(onClick = {
                            waypoints.clear()
                            redrawTrack()
                            statusText = "Track geleert"
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "Track leeren")
                        }
                        // Neue Seite: aus GPX importierte Caches durchsuchen/auswählen (siehe
                        // CacheListScreen.kt) - Wegpunkte eines gewählten Caches erscheinen dann
                        // auf dieser Karte (redrawGpxMarkers), unabhängig vom manuell eingegebenen Track.
                        Button(onClick = onOpenCacheList) {
                            Icon(androidx.compose.material.icons.filled.List, contentDescription = "Cache-Liste (GPX)")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Geschwindigkeits-Presets statt Zahleneingabe: Fußgänger/Fahrrad/Auto/Rakete
                        // (Rakete = Sonderlogik in TrackSimulator, siehe SpeedPreset.kt)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilledIconToggleButton(
                                checked = selectedSpeedPreset == SpeedPreset.WALK,
                                onCheckedChange = { if (it) selectedSpeedPreset = SpeedPreset.WALK }
                            ) { Icon(Icons.Default.DirectionsWalk, contentDescription = SpeedPreset.WALK.label) }
                            FilledIconToggleButton(
                                checked = selectedSpeedPreset == SpeedPreset.BIKE,
                                onCheckedChange = { if (it) selectedSpeedPreset = SpeedPreset.BIKE }
                            ) { Icon(Icons.Default.DirectionsBike, contentDescription = SpeedPreset.BIKE.label) }
                            FilledIconToggleButton(
                                checked = selectedSpeedPreset == SpeedPreset.CAR,
                                onCheckedChange = { if (it) selectedSpeedPreset = SpeedPreset.CAR }
                            ) { Icon(Icons.Default.DirectionsCar, contentDescription = SpeedPreset.CAR.label) }
                            FilledIconToggleButton(
                                checked = selectedSpeedPreset == SpeedPreset.ROCKET,
                                onCheckedChange = { if (it) selectedSpeedPreset = SpeedPreset.ROCKET }
                            ) { Icon(Icons.Default.RocketLaunch, contentDescription = SpeedPreset.ROCKET.label) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Jitter ±5m", style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = jitterEnabled,
                                onCheckedChange = { checked ->
                                    jitterEnabled = checked
                                    MockLocationController.setJitterEnabled(checked)
                                }
                            )
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

            // WICHTIG: weight(1f) statt fillMaxSize() - sonst bekommt die Karte beim Layout die
            // volle Bildschirmhöhe zugewiesen (ignoriert die Card darüber) und osmdroid zeichnet
            // beim Pannen/Fling über seinen tatsächlich sichtbaren Bereich hinaus, wodurch die
            // Karte optisch über die Bedienelemente rutscht.
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    factory = {
                        mapView.apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(16.0)
                            controller.setCenter(GeoPoint(52.5200, 13.4050)) // Fallback-Default, bis lastKnownRealLocation() (falls verfügbar) übernimmt
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

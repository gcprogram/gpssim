package com.gcprogram.gpssim.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gcprogram.gpssim.geo.CoordinateParser
import com.gcprogram.gpssim.geo.SpeedPreset
import com.gcprogram.gpssim.geo.TrackPoint
import com.gcprogram.gpssim.location.MockLocationController
import com.gcprogram.gpssim.location.MockLocationService
import com.gcprogram.gpssim.location.startSimulation
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
import org.osmdroid.views.overlay.ScaleBarOverlay
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
        // Eigener Cache-Name pro Datei (statt null) - osmdroid legt Kacheln nach Tile-Source-
        // Namen ab; ohne eindeutigen Namen würden zwei verschiedene Offline-Karten denselben
        // Cache-Bereich teilen und ggf. die (leeren/falschen) Kacheln der vorherigen Karte
        // ausliefern, bis dieser Bereich manuell neu geladen wird.
        val cacheName = "mapsforge-" + mapFile.name
        val tileSource = MapsForgeTileSource.createFromFiles(arrayOf(mapFile), null, cacheName)
        val provider = MapsForgeTileProvider(SimpleRegisterReceiver(mapView.context), tileSource, null)
        mapView.tileProvider.detach()
        mapView.tileProvider = provider
        // WICHTIG: ohne diesen Aufruf bleibt die Karte auf der vorherigen Tile-Source projiziert -
        // sie gilt als "aktiv" (Umschalt-Status korrekt), zeichnet aber keine Kacheln, weil
        // MapView intern noch mit der alten Source arbeitet. Siehe GCToolkit-Android, das diesen
        // Aufruf ebenfalls direkt nach setTileProvider() macht.
        mapView.setTileSource(tileSource)
        mapView.setUseDataConnection(false)
        // Auf die aktuelle (echte) GPS-Position zentrieren statt auf die Mitte der gesamten
        // Kartendatei - wer z.B. eine Landkarte für ein späteres Vorhaben lädt, will die Ansicht
        // dort haben, wo er gerade physisch steht, nicht in der geografischen Mitte der Datei.
        // Nur falls kein GPS-Fix vorliegt (z.B. Berechtigung fehlt), auf den Abdeckungsbereich
        // der Karte ausweichen - besser als ein Kartenausschnitt ganz ohne geladene Kacheln.
        val realFix = lastKnownRealLocation(mapView.context)
        if (realFix != null) {
            mapView.controller.setCenter(realFix)
        } else {
            readMapBounds(mapFile)?.let { bounds -> mapView.zoomToBoundingBox(bounds, false) }
        }
        mapView.invalidate()
        true
    } catch (e: Exception) {
        false
    }
}

/** Schaltet zurück auf Online-OSM-Tiles (Mapnik). */
private fun switchToOnlineMap(mapView: MapView) {
    mapView.tileProvider.detach()
    mapView.tileProvider = MapTileProviderBasic(mapView.context)
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
fun MapScreen(onOpenCacheList: () -> Unit, onOpenWaypointList: () -> Unit, onOpenTracker: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Track als geordnete Wegpunktliste - jetzt ein geteilter Singleton (TrackRepository)
    // statt lokalem State, damit CacheListScreen (Long-Press "alle Wegpunkte hinzufügen") und
    // WaypointListScreen (einzeln entfernen) denselben Track sehen und ändern können.
    val waypoints by com.gcprogram.gpssim.geo.TrackRepository.waypoints.collectAsState()

    var pasteText by remember { mutableStateOf("") }
    // Geteilter Zustand statt lokalem State (MockLocationController) - so wirkt ein Tempo-Wechsel
    // auch sofort auf eine laufende Simulation, und Play von der Wegpunktliste aus (siehe
    // startSimulation()) verwendet dieselbe zuletzt gewählte Geschwindigkeit.
    val selectedSpeedPreset by MockLocationController.speedPreset.collectAsState()
    val jitterEnabled by MockLocationController.jitterEnabled.collectAsState()
    var isRunning by remember { mutableStateOf(false) }
    // Drei Zustände statt nur an/aus: "aus" (serviceActive=false), "pausiert" (serviceActive=true,
    // isRunning=false - Mock-Provider bleibt aktiv, Track steht) und "läuft" (beide true). Der
    // Play/Pause-FAB wechselt nur zwischen pausiert/läuft; ein zusätzlicher Stop-FAB (nur sichtbar,
    // wenn serviceActive) beendet den Mock-Provider komplett und kehrt zu "aus" zurück. Geteilter
    // Zustand (MockLocationController), damit ein Start über den Play-Button der Wegpunktliste
    // hier ebenfalls korrekt ankommt.
    val serviceActive by MockLocationController.serviceActive.collectAsState()
    var statusText by remember { mutableStateOf("Kein Track gesetzt") }
    var isOfflineMode by remember { mutableStateOf(false) }
    var offlineMapName by remember { mutableStateOf(OfflineMapManager.currentMapDisplayName(context)) }
    // Letzter bekannter ECHTER (unsimulierter) GPS-Fix - für den "Auf Position zentrieren"-FAB.
    // Wird NICHT während einer laufenden Simulation neu abgefragt, weil GPS_PROVIDER dann
    // systemweit die simulierte Position liefert (siehe lastKnownRealLocation()).
    var lastRealFix by remember { mutableStateOf<GeoPoint?>(null) }

    val mapView = remember { MapView(context) }

    // Zwei Linien statt einer: der bereits "abgefahrene" Teil des Tracks wird grau dargestellt,
    // der noch bevorstehende bleibt blau - so sieht man auf einen Blick, wie weit die Simulation
    // schon ist. Ohne laufende Simulation ist die gesamte Strecke "future" (blau).
    val trackPolylinePast = remember {
        Polyline().apply {
            outlinePaint.color = Color.GRAY
            outlinePaint.strokeWidth = 8f
        }
    }
    val trackPolylineFuture = remember {
        Polyline().apply {
            outlinePaint.color = Color.BLUE
            outlinePaint.strokeWidth = 8f
        }
    }
    // Zeigt den GPS-Tracker-Track (laufende Aufzeichnung ODER geladene/abzuspielende Tour, siehe
    // TrackRecorder) als eigene Linie - unabhängig von der manuellen Wegpunktliste oben, daher
    // eigene Farbe (Orange) und eigener Overlay, keine past/future-Aufteilung (die wäre hier wegen
    // des bekannten Index-Mismatchs zwischen RecordedPoint- und TrackPoint-Listen nicht sinnvoll).
    val recordedTrackPolyline = remember {
        Polyline().apply {
            outlinePaint.color = Color.rgb(255, 140, 0)
            outlinePaint.strokeWidth = 8f
        }
    }
    // Simulierte Position: kleiner Roboter/Androide (siehe MapIconFactory.robotMarker) -
    // unterscheidet sie auf einen Blick von der echten, unsimulierten Position (Männchen-Icon).
    val currentPositionMarker = remember {
        Marker(mapView).apply {
            title = "Simulierte Position"
            icon = com.gcprogram.gpssim.geo.MapIconFactory.robotMarker(context)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
    }
    // Echte (unsimulierte) GPS-Position: kleines Männchen-Icon, live nachgeführt über einen
    // eigenen LocationListener weiter unten (nicht nur der einmalige Fix beim App-Start).
    val realPositionMarker = remember {
        Marker(mapView).apply {
            title = "Echte Position"
            icon = com.gcprogram.gpssim.geo.MapIconFactory.personMarker(context)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
    }

    // Setzt/zeigt den Männchen-Marker an `point` - zentrale Stelle statt den Marker nur im Live-
    // LocationListener weiter unten zu pflegen: VORHER passierte das NUR dort, sodass der Marker
    // direkt nach dem App-Start (und auch nach einem Druck auf "Auf Position zentrieren") fehlte,
    // bis die erste LIVE-Ortung eintraf - das konnte draußen Sekunden dauern und drinnen ganz
    // ausbleiben, obwohl über lastKnownRealLocation() oft längst eine gültige (zwischengespeicherte)
    // Position bekannt war. Jetzt nutzen App-Start, der "Zentrieren"-Button UND der Live-Listener
    // dieselbe Funktion, sodass der Marker so früh wie möglich erscheint.
    fun showRealPositionMarker(point: GeoPoint) {
        realPositionMarker.position = point
        if (!mapView.overlays.contains(realPositionMarker)) {
            mapView.overlays.add(realPositionMarker)
        }
        mapView.invalidate()
    }

    // Teilt die Wegpunktliste an der aktuellen simulierten Position (SimulatedPosition.segmentIndex,
    // siehe TrackSimulator) in einen gefahrenen (grau) und einen bevorstehenden (blau) Abschnitt.
    // Ohne aktive Position (Simulation nie gestartet oder gestoppt/zurückgesetzt) ist alles blau.
    fun updateTrackSplit(points: List<TrackPoint>, pos: com.gcprogram.gpssim.geo.SimulatedPosition?) {
        if (pos == null || points.size < 2) {
            trackPolylinePast.setPoints(emptyList())
            trackPolylineFuture.setPoints(points.map { GeoPoint(it.latitude, it.longitude) })
        } else {
            val segIdx = pos.segmentIndex.coerceIn(0, points.size - 1)
            val currentGeoPoint = GeoPoint(pos.latitude, pos.longitude)
            val pastPts = ArrayList<GeoPoint>(segIdx + 2)
            for (i in 0..segIdx) pastPts.add(GeoPoint(points[i].latitude, points[i].longitude))
            pastPts.add(currentGeoPoint)
            val futurePts = ArrayList<GeoPoint>(points.size - segIdx)
            futurePts.add(currentGeoPoint)
            for (i in (segIdx + 1) until points.size) futurePts.add(GeoPoint(points[i].latitude, points[i].longitude))
            trackPolylinePast.setPoints(pastPts)
            trackPolylineFuture.setPoints(futurePts)
        }
    }

    fun redrawTrack(points: List<TrackPoint>) {
        updateTrackSplit(points, MockLocationController.currentPosition.value)
        mapView.overlays.removeAll { it is Marker && it.id == "waypoint" }
        points.forEachIndexed { index, wp ->
            val marker = Marker(mapView).apply {
                position = GeoPoint(wp.latitude, wp.longitude)
                title = wp.label ?: "Wegpunkt ${index + 1}"
                id = "waypoint"
            }
            mapView.overlays.add(marker)
        }
        mapView.invalidate()
    }

    fun addWaypoint(point: TrackPoint) {
        com.gcprogram.gpssim.geo.TrackRepository.add(point)
    }

    // Track/Karte/Simulator neu abgleichen, sobald sich die geteilte Wegpunktliste ändert -
    // egal ob durch Tap auf die Karte, Paste hier, Long-Press in der Cache-Liste oder Entfernen
    // in der neuen WaypointListScreen.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.gcprogram.gpssim.geo.TrackRepository.waypoints.collectLatest { pts ->
            redrawTrack(pts)
            MockLocationController.setTrack(pts)
            statusText = "${pts.size} Wegpunkt(e) gesetzt"
        }
    }

    // Aufgezeichneten/geladenen Tracker-Track (siehe TrackerScreen) als eigene Linie nachführen -
    // unabhängig davon, ob gerade aufgezeichnet, nur geladen, oder abgespielt wird.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.gcprogram.gpssim.location.TrackRecorder.points.collectLatest { pts ->
            recordedTrackPolyline.setPoints(pts.map { GeoPoint(it.latitude, it.longitude) })
            mapView.invalidate()
        }
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

    // Live-Tracking der ECHTEN GPS-Position fürs Männchen-Icon (nicht nur der einmalige Fix
    // beim App-Start) - läuft die ganze Zeit über NETWORK_PROVIDER (von unserem eigenen
    // Mock-Test-Provider nicht betroffen) und zusätzlich über GPS_PROVIDER, aber NUR solange
    // keine Simulation aktiv ist - während einer laufenden Simulation liefert GPS_PROVIDER
    // systemweit die simulierte statt der echten Position (siehe MockLocationService).
    DisposableEffect(context) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.provider == LocationManager.GPS_PROVIDER &&
                    MockLocationController.serviceActive.value
                ) {
                    return // während der Simulation ist das die gefälschte, nicht die echte Position
                }
                val point = GeoPoint(location.latitude, location.longitude)
                lastRealFix = point
                showRealPositionMarker(point)
            }
        }
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (hasPermission && locationManager != null) {
            try {
                for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
                    if (locationManager.isProviderEnabled(provider)) {
                        locationManager.requestLocationUpdates(provider, 3000L, 5f, listener)
                    }
                }
            } catch (e: SecurityException) {
                // Berechtigung evtl. noch nicht final erteilt - bleibt beim einmaligen Startfix
            }
        }
        onDispose {
            try {
                locationManager?.removeUpdates(listener)
            } catch (e: Exception) {
                // nichts zu tun - Listener war ggf. nie erfolgreich registriert
            }
        }
    }

    // Simulierte Position beobachten und NUR den Marker bewegen - map.controller.setCenter()
    // wird hier bewusst nicht aufgerufen, damit die Karte nicht springt. Zusätzlich füllt jede
    // neue simulierte Position das Koordinatenfeld (DMM-Format) - so zeigt es während einer
    // laufenden Simulation immer die zuletzt simulierte Position statt eines langen Platzhaltertexts.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        MockLocationController.currentPosition.collectLatest { pos ->
            if (pos != null) {
                currentPositionMarker.position = GeoPoint(pos.latitude, pos.longitude)
                currentPositionMarker.rotation = pos.bearing
                if (!mapView.overlays.contains(currentPositionMarker)) {
                    mapView.overlays.add(currentPositionMarker)
                }
                updateTrackSplit(waypoints, pos)
                mapView.invalidate()
                pasteText = CoordinateParser.format(pos.latitude, pos.longitude)
            } else {
                // Zurückgesetzt (z.B. neuer Track) - komplette Strecke wieder blau
                updateTrackSplit(waypoints, null)
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
    // für die Einschränkung solange die Simulation noch nicht aktiv ist). Befüllt zusätzlich
    // das Koordinatenfeld mit der echten Position im DMM-Format (überschrieben, sobald die
    // Simulation läuft - siehe LaunchedEffect zu MockLocationController.currentPosition oben).
    LaunchedEffect(Unit) {
        lastKnownRealLocation(context)?.let { point ->
            lastRealFix = point
            showRealPositionMarker(point)
            mapView.controller.setCenter(point)
            mapView.invalidate()
            pasteText = CoordinateParser.format(point.latitude, point.longitude)
        }
    }

    Scaffold(
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                FloatingActionButton(onClick = {
                    // Zentriert auf die ECHTE, unsimulierte GPS-Position (nicht auf den
                    // Track-Marker, der ohne laufende Simulation auf N0/E0 stünde). Solange die
                    // Simulation nicht läuft, wird dafür ein frischer Fix abgefragt; während sie
                    // läuft, liefert GPS_PROVIDER systemweit nur noch die simulierte Position
                    // (siehe lastKnownRealLocation()), daher dann der zuletzt bekannte echte Fix.
                    val target = if (!isRunning) {
                        lastKnownRealLocation(context)?.also { lastRealFix = it } ?: lastRealFix
                    } else {
                        lastRealFix
                    }
                    target?.let {
                        // Nicht nur die Kamera bewegen, sondern auch den Männchen-Marker dorthin
                        // setzen/anzeigen - vorher blieb er unsichtbar, solange der Live-Listener
                        // noch keine eigene Ortung geliefert hatte, obwohl hier bereits eine
                        // gültige Position vorlag (siehe showRealPositionMarker()).
                        showRealPositionMarker(it)
                        mapView.controller.animateTo(it)
                    } ?: run { statusText = "Noch keine echte GPS-Position bekannt" }
                }) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Auf Position zentrieren")
                }
                Spacer(Modifier.height(4.dp))
                // Stop nur sichtbar, solange der Mock-Provider überhaupt aktiv ist (läuft oder
                // pausiert) - beendet ihn komplett, GPS_PROVIDER liefert danach wieder die echte
                // Position. Play/Pause allein tut das NICHT mehr (siehe unten) - Pause hält nur
                // den Track an, der Mock-Provider bleibt auf der letzten Position stehen.
                if (serviceActive) {
                    FloatingActionButton(onClick = {
                        MockLocationController.stop()
                        MockLocationService.stop(context)
                        MockLocationController.markServiceActive(false)
                    }) {
                        Icon(Icons.Default.Stop, contentDescription = "Simulation beenden")
                    }
                    Spacer(Modifier.height(4.dp))
                }
                FloatingActionButton(onClick = {
                    if (isRunning) {
                        // Pause: nur den Track anhalten, Mock-Provider/Service bleiben aktiv -
                        // GPS liefert weiterhin die (stehende) simulierte Position.
                        MockLocationController.stop()
                    } else if (serviceActive) {
                        // Fortsetzen ab der pausierten Stelle - KEIN erneutes setTrack(), das
                        // würde den Fortschritt auf den Start zurücksetzen.
                        MockLocationController.start()
                    } else {
                        // Geteilte Start-Logik mit dem Play-Button auf der Wegpunktliste, siehe
                        // SimulationActions.kt - Geschwindigkeit/Jitter sind bereits über
                        // MockLocationController synchron, hier wird nur noch der Track übernommen.
                        if (!startSimulation(context)) {
                            statusText = "Mindestens 2 Wegpunkte nötig (Karte antippen oder Koordinaten einfügen)"
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
                    // Kurzes Label statt langem Platzhaltertext - das Feld ist ohnehin ab Start mit
                    // der echten GPS-Position (später: letzte simulierte Position) vorbelegt,
                    // siehe die beiden LaunchedEffect-Blöcke weiter unten.
                    OutlinedTextField(
                        value = pasteText,
                        onValueChange = { pasteText = it },
                        label = { Text("Koordinaten") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // "+📍" statt langem Text "Als Wegpunkt hinzufügen" - AddLocation-Icon
                        // vereint Plus-Symbol und Markernadel in einem.
                        Button(onClick = {
                            val parsed = CoordinateParser.parse(pasteText)
                            if (parsed != null) {
                                addWaypoint(TrackPoint(parsed.latitude, parsed.longitude))
                                mapView.controller.animateTo(GeoPoint(parsed.latitude, parsed.longitude))
                            } else {
                                statusText = "Koordinaten nicht erkannt - Format N dd° mm.mmm E ddd° mm.mmm erwartet"
                            }
                        }) {
                            Icon(Icons.Default.AddLocation, contentDescription = "Als Wegpunkt hinzufügen")
                        }
                        // Öffnet die Wegpunktliste - Route-Icon statt des früheren "X" (das wirkte
                        // wie ein reiner Löschen-Button und war nicht als Listen-Einstieg erkennbar).
                        Button(onClick = onOpenWaypointList) {
                            Icon(Icons.Default.Route, contentDescription = "Wegpunktliste öffnen")
                            Text(" ${waypoints.size}")
                        }
                        // Neue Seite: aus GPX importierte Caches durchsuchen/auswählen (siehe
                        // CacheListScreen.kt) - "+" plus Adventure-Lab-Cache-Icon statt eines
                        // (auf manchen Geräten leer gerenderten) reinen List-Icons.
                        Button(onClick = onOpenCacheList) {
                            Text("+")
                            Spacer(Modifier.width(4.dp))
                            val labIcon = remember { com.gcprogram.gpssim.geo.MapIconFactory.cacheTypeMarker(context, "Lab") }
                            androidx.compose.foundation.Image(
                                bitmap = labIcon.toBitmap().asImageBitmap(),
                                contentDescription = "Cache-Liste (GPX)"
                            )
                        }
                        // Neue Seite: eingebauter GPS-Tracker (Aufzeichnen/Speichern/Laden/
                        // beschleunigt Abspielen) - "Aufnahme"-Punkt-Icon, da auf dieser Seite
                        // eine echte Aufzeichnung gestartet wird (siehe TrackerScreen.kt).
                        Button(onClick = onOpenTracker) {
                            Icon(Icons.Default.FiberManualRecord, contentDescription = "GPS-Tracker öffnen")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Geschwindigkeits-Presets statt Zahleneingabe: Fußgänger/Fahrrad/Auto/Rakete
                        // (Rakete = Sonderlogik in TrackSimulator, siehe SpeedPreset.kt). Wirkt jetzt
                        // über MockLocationController.setSpeedPreset() SOFORT, auch während eine
                        // Simulation bereits läuft - TrackSimulator liest die Geschwindigkeit bei
                        // jedem 1-Sekunden-Tick neu, ein Wechsel bremst/beschleunigt also live.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilledIconToggleButton(
                                checked = selectedSpeedPreset == SpeedPreset.WALK,
                                onCheckedChange = { if (it) MockLocationController.setSpeedPreset(SpeedPreset.WALK) }
                            ) { Icon(Icons.Default.DirectionsWalk, contentDescription = SpeedPreset.WALK.label) }
                            FilledIconToggleButton(
                                checked = selectedSpeedPreset == SpeedPreset.BIKE,
                                onCheckedChange = { if (it) MockLocationController.setSpeedPreset(SpeedPreset.BIKE) }
                            ) { Icon(Icons.Default.DirectionsBike, contentDescription = SpeedPreset.BIKE.label) }
                            FilledIconToggleButton(
                                checked = selectedSpeedPreset == SpeedPreset.CAR,
                                onCheckedChange = { if (it) MockLocationController.setSpeedPreset(SpeedPreset.CAR) }
                            ) { Icon(Icons.Default.DirectionsCar, contentDescription = SpeedPreset.CAR.label) }
                            FilledIconToggleButton(
                                checked = selectedSpeedPreset == SpeedPreset.ROCKET,
                                onCheckedChange = { if (it) MockLocationController.setSpeedPreset(SpeedPreset.ROCKET) }
                            ) { Icon(Icons.Default.RocketLaunch, contentDescription = SpeedPreset.ROCKET.label) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("GPS-Ungenauigkeit (2-12m, variabel)", style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = jitterEnabled,
                                onCheckedChange = { checked -> MockLocationController.setJitterEnabled(checked) }
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
            // volle Bildschirmhöhe zugewiesen (ignoriert die Card darüber). Zusätzlich clipToBounds():
            // osmdroid zeichnet über seinen zugewiesenen Compose-Bereich hinaus (z.B. direkt nach
            // zoomToBoundingBox() beim Umschalten auf eine Offline-Karte, oder beim Pannen/Fling) -
            // clipToBounds() kappt das hart an der Box-Grenze, egal was die native View selbst glaubt.
            Box(modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                AndroidView(
                    factory = {
                        mapView.apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            // Eingebaute Zoom-Buttons (+/-) unten rechts, zusätzlich zur
                            // Pinch-to-Zoom-Geste - ALWAYS statt des Default-"nur kurz nach
                            // Interaktion", damit sie dauerhaft sichtbar/antippbar sind.
                            zoomController.setVisibility(
                                org.osmdroid.views.CustomZoomButtonsController.Visibility.ALWAYS
                            )
                            controller.setZoom(16.0)
                            controller.setCenter(GeoPoint(52.5200, 13.4050)) // Fallback-Default, bis lastKnownRealLocation() (falls verfügbar) übernimmt
                            overlays.add(mapEventsOverlay)
                            overlays.add(trackPolylineFuture)
                            overlays.add(trackPolylinePast)
                            overlays.add(recordedTrackPolyline)
                            // Maßstabsbalken oben links
                            overlays.add(
                                ScaleBarOverlay(this).apply {
                                    setCentred(true)
                                    setScaleBarOffset(20, 20)
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

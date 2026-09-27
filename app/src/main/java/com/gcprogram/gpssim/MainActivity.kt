package com.gcprogram.gpssim

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.gcprogram.gpssim.gpx.GpxRepository
import com.gcprogram.gpssim.ui.CacheListScreen
import com.gcprogram.gpssim.ui.MapScreen
import com.gcprogram.gpssim.ui.WaypointListScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Overlay { CACHE_LIST, WAYPOINT_LIST }

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Ergebnis wird nicht ausgewertet - fehlende Rechte zeigen sich direkt im Kartenverhalten */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    // Zuletzt importierte GPX-Cacheliste beim Start wieder laden (siehe
                    // GpxRepository - Datei liegt bereits im App-Verzeichnis, kein neuer Import
                    // nötig). Einmalig und abseits des Main-Threads, da Datei-I/O beteiligt ist.
                    LaunchedEffect(Unit) {
                        withContext(Dispatchers.IO) { GpxRepository.loadPersisted(context) }
                    }

                    // Bewusst KEIN NavHost: composable()-Routen werden beim Verlassen komplett
                    // verworfen und beim Zurückkehren neu aufgebaut - das hätte MapScreens
                    // gesamten Zustand (MapView, Offline-Kartenmodus, Track) bei jedem Ausflug in
                    // die Cache-/Wegpunktliste zurückgesetzt (Kartensprung, Overlay-Ruckler).
                    // Stattdessen bleibt MapScreen dauerhaft komponiert, die anderen Seiten liegen
                    // als Overlay obenauf.
                    var overlay by remember { mutableStateOf<Overlay?>(null) }
                    Box(modifier = Modifier.fillMaxSize()) {
                        MapScreen(
                            onOpenCacheList = { overlay = Overlay.CACHE_LIST },
                            onOpenWaypointList = { overlay = Overlay.WAYPOINT_LIST }
                        )
                        when (overlay) {
                            Overlay.CACHE_LIST -> CacheListScreen(onBack = { overlay = null })
                            Overlay.WAYPOINT_LIST -> WaypointListScreen(onBack = { overlay = null })
                            null -> {}
                        }
                    }
                }
            }
        }
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}

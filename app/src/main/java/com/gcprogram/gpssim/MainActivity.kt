package com.gcprogram.gpssim

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gcprogram.gpssim.gpx.GpxRepository
import com.gcprogram.gpssim.ui.CacheListScreen
import com.gcprogram.gpssim.ui.MapScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "map") {
                        composable("map") {
                            MapScreen(onOpenCacheList = { navController.navigate("cachelist") })
                        }
                        composable("cachelist") {
                            CacheListScreen(onBack = { navController.popBackStack() })
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

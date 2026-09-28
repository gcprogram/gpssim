package com.gcprogram.gpssim.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gcprogram.gpssim.geo.CoordinateParser
import com.gcprogram.gpssim.geo.TrackRepository
import com.gcprogram.gpssim.location.startSimulation

/**
 * Neue Seite: die Wegpunktliste der aktuellen Simulations-Fahrt, mit Entfernen-Button (X)
 * rechts neben jedem einzelnen Eintrag - statt wie vorher nur "alles auf einmal löschen".
 * Ersetzt den früheren direkten "Track leeren"-Button auf der Kartenseite (siehe MapScreen: der
 * X-Button öffnet jetzt diese Seite statt sofort zu leeren).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaypointListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val waypoints by TrackRepository.waypoints.collectAsState()
    var statusText by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wegpunkte (${waypoints.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Zurück zur Karte")
                    }
                },
                actions = {
                    // Startet die Simulation direkt von hier aus und wechselt zurück zur Karte,
                    // wo sie dann bereits läuft (siehe startSimulation() - geteilte Logik mit dem
                    // Play-FAB auf der Kartenseite).
                    IconButton(onClick = {
                        if (startSimulation(context)) {
                            onBack()
                        } else {
                            statusText = "Mindestens 2 Wegpunkte nötig, um zu starten"
                        }
                    }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Simulation starten")
                    }
                }
            )
        }
    ) { padding: PaddingValues ->
        if (waypoints.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                statusText?.let {
                    Text(it, modifier = Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Noch keine Wegpunkte - auf die Karte tippen, Koordinaten einfügen, oder in " +
                        "der Cache-Liste einen Cache lang drücken, um alle seine Wegpunkte hier " +
                        "hinzuzufügen.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                statusText?.let {
                    Text(it, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(waypoints) { index, wp ->
                        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${index + 1}. ${wp.label ?: "Wegpunkt"}", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        CoordinateParser.format(wp.latitude, wp.longitude),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                IconButton(onClick = { TrackRepository.removeAt(index) }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Wegpunkt entfernen")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

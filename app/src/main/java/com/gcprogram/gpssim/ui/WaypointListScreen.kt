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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcprogram.gpssim.geo.CoordinateParser
import com.gcprogram.gpssim.geo.TrackRepository

/**
 * Neue Seite: die Wegpunktliste der aktuellen Simulations-Fahrt, mit Entfernen-Button (X)
 * rechts neben jedem einzelnen Eintrag - statt wie vorher nur "alles auf einmal löschen".
 * Ersetzt den früheren direkten "Track leeren"-Button auf der Kartenseite (siehe MapScreen: der
 * X-Button öffnet jetzt diese Seite statt sofort zu leeren).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaypointListScreen(onBack: () -> Unit) {
    val waypoints by TrackRepository.waypoints.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wegpunkte (${waypoints.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Zurück zur Karte")
                    }
                }
            )
        }
    ) { padding: PaddingValues ->
        if (waypoints.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(
                    "Noch keine Wegpunkte - auf die Karte tippen, Koordinaten einfügen, oder in " +
                        "der Cache-Liste einen Cache lang drücken, um alle seine Wegpunkte hier " +
                        "hinzuzufügen.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
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

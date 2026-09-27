package com.gcprogram.gpssim.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FileOpen
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gcprogram.gpssim.geo.GeoCache
import com.gcprogram.gpssim.geo.MapIconFactory
import com.gcprogram.gpssim.geo.TrackPoint
import com.gcprogram.gpssim.geo.TrackRepository
import com.gcprogram.gpssim.geo.toTrackPoints
import com.gcprogram.gpssim.gpx.GpxRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Neue Seite: Liste aller aus einer GPX-Datei importierten Caches. Ein Import-Button lädt
 * eine neue GPX/PQ-ZIP-Datei (ersetzt die aktuelle Liste), ein Tap auf einen Cache wählt ihn
 * aus - MapScreen zeigt dann nur noch dessen Wegpunkte (siehe GpxRepository.selectedCache).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val caches by GpxRepository.caches.collectAsState()
    val selected by GpxRepository.selectedCache.collectAsState()
    var statusText by remember { mutableStateOf<String?>(null) }

    val pickGpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    // Manche Anbieter erlauben keine dauerhafte Freigabe - unkritisch, wir kopieren sofort.
                }
                val result = runCatching { GpxRepository.import(context, uri) }
                withContext(Dispatchers.Main) {
                    statusText = result.fold(
                        onSuccess = { "${it.caches.size} Cache(s) geladen" },
                        onFailure = { "GPX konnte nicht gelesen werden: ${it.message}" }
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cache-Liste") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Zurück zur Karte")
                    }
                },
                actions = {
                    IconButton(onClick = { pickGpxLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.FileOpen, contentDescription = "GPX laden")
                    }
                    if (selected != null) {
                        IconButton(onClick = { GpxRepository.selectCache(null) }) {
                            Icon(Icons.Default.Clear, contentDescription = "Auswahl aufheben")
                        }
                    }
                }
            )
        }
    ) { padding: PaddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            statusText?.let {
                Text(it, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (caches.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Noch keine Caches geladen - oben rechts eine GPX-Datei auswählen\n" +
                            "(Export aus der offiziellen Geocaching-App, c:geo oder eine Pocket Query).",
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                Text(
                    "Tippen: nur diesen Cache auf der Karte zeigen · Lang drücken: alle seine " +
                        "Wegpunkte zur Simulations-Wegpunktliste hinzufügen",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(caches, key = { it.gccode }) { cache ->
                        CacheRow(
                            cache = cache,
                            isSelected = selected?.gccode == cache.gccode,
                            onClick = {
                                GpxRepository.selectCache(cache)
                                onBack()
                            },
                            onLongClick = {
                                val added = cache.toTrackPoints()
                                TrackRepository.addAll(added)
                                statusText = "${added.size} Wegpunkt(e) von ${cache.gccode} zur Simulationsliste hinzugefügt"
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CacheRow(cache: GeoCache, isSelected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = remember(cache.cacheType) { MapIconFactory.cacheTypeMarker(context, cache.cacheType) }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    bitmap = icon.toBitmap().asImageBitmap(),
                    contentDescription = cache.cacheType
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(cache.gccode, style = MaterialTheme.typography.titleSmall)
                    Text(cache.cacheType, style = MaterialTheme.typography.bodySmall)
                }
                Text(cache.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                if (cache.waypoints.isNotEmpty()) {
                    Text(
                        "${cache.waypoints.size} zusätzliche Wegpunkt(e)" +
                            (cache.finalWaypoint?.let { " · Final vorhanden" } ?: ""),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

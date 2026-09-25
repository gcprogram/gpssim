# GPS Simulator (Geocaching)

Android-App zum Simulieren von GPS-Positionen für Geocaching-Zwecke: Koordinaten
im GC-Freitextformat einfügen, Track auf der Karte antippen, Simulation starten.

## Setup (GitHub)

1. Neues leeres Repository auf GitHub anlegen (ohne README/gitignore-Vorbelegung).
2. Dieses Zip lokal entpacken und den Inhalt ins Repo hochladen (per GitHub-Web-UI
   "Upload files" oder `git add`/`commit`/`push`, je nachdem was gerade am Handy geht).
3. Der Workflow unter `.github/workflows/build.yml` läuft automatisch bei jedem Push
   und baut eine Debug-APK. Ergebnis unter dem Tab "Actions" → Workflow-Run →
   Artifacts → `gps-simulator-debug-apk` herunterladen und installieren.
4. Auf dem Handy: Entwickleroptionen → "Mock location app" → GPS Simulator auswählen
   (laut dir schon erledigt).

## Bedienung (aktueller Stand)

- Koordinaten im Format `N49° 12.345 E008° 40.123` (oder ohne °, mit Komma) ins
  obere Feld einfügen → "Als Wegpunkt hinzufügen".
- Alternativ: auf die Karte tippen, um einen Wegpunkt zu setzen.
- Mind. 2 Wegpunkte ergeben einen linearen Track. Geschwindigkeit in km/h eintragen.
- Play/Pause-FAB startet/stoppt die Simulation, ohne die Kartenansicht zu verschieben.
- Der Zielscheiben-FAB zentriert die Karte manuell auf die aktuelle simulierte Position.
- "Track leeren" (X-Button) setzt die Wegpunktliste zurück.

## Offene Punkte / nächste Schritte

- Bisher nur Online-OSM-Tiles (Mapnik). Offline-Kartenunterstützung (mapsforge,
  wie in GCToolkit-Android) ist vorbereitet über osmdroid, aber noch nicht verdrahtet.
- Wegpunkte lassen sich aktuell nur hinzufügen, nicht einzeln per Tap wieder entfernen
  oder verschieben - bei Bedarf ergänzen.
- Kein Geschwindigkeits-Rauschen/GPS-Jitter simuliert (bewusst einfach gehalten).
- Icon ist ein Platzhalter (System-Icon), kein eigenes App-Icon.

## Architektur

- `geo/CoordinateParser.kt` - Parser für GC-Freitextformat WGS84 (DMM)
- `geo/TrackSimulator.kt` - lineare Bewegung entlang der Wegpunktliste, 1s-Ticks
- `location/MockLocationController.kt` - prozessweiter Singleton, verbindet UI und Service
- `location/MockLocationService.kt` - Foreground-Service, schreibt Position über
  `LocationManager`-Test-Provider auf `GPS_PROVIDER`
- `ui/MapScreen.kt` - Compose-Screen mit osmdroid-`MapView` via `AndroidView`

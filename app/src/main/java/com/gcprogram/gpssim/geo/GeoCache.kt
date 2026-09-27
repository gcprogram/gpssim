package com.gcprogram.gpssim.geo

/**
 * Ein zusätzlicher Wegpunkt eines Caches (Parkplatz, Referenzpunkt, Final, Original Coordinates,
 * Etappe eines Multis, ...). Alles außer dem Cache selbst - der wird separat in [GeoCache]
 * gehalten, damit er in der Übersicht sein eigenes Cache-Typ-Icon bekommt statt den blauen
 * Marker der übrigen Wegpunkte.
 */
data class CacheWaypoint(
    val name: String,
    val title: String,
    val latitude: Double,
    val longitude: Double,
    /** GPX-Wegpunkt-Typ, z.B. "Final Location", "Parking Area", "Original Coordinates". */
    val type: String
)

/** Ein aus einer Geocaching-GPX-Datei importierter Cache samt seiner zusätzlichen Wegpunkte. */
data class GeoCache(
    val gccode: String,
    val title: String,
    /** Kurzer Typ-Name, siehe CacheTypes.normaliseTypeLong() - z.B. "Tradi", "Mystery". */
    val cacheType: String,
    val postedLatitude: Double,
    val postedLongitude: Double,
    val waypoints: List<CacheWaypoint> = emptyList()
) {
    /** Erster "Final Location"-Wegpunkt, falls vorhanden - sonst null. */
    val finalWaypoint: CacheWaypoint?
        get() = waypoints.firstOrNull { it.type.equals("Final Location", ignoreCase = true) }

    /** Für die Cache-Übersicht auf der Karte: Final-Koordinaten falls vorhanden, sonst posted. */
    val overviewLatitude: Double get() = finalWaypoint?.latitude ?: postedLatitude
    val overviewLongitude: Double get() = finalWaypoint?.longitude ?: postedLongitude
}

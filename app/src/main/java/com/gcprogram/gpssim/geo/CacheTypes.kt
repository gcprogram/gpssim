package com.gcprogram.gpssim.geo

/**
 * Zuordnung "langer" GPX-Cache-Typ-String (z.B. "Traditional Cache") -> kurzer Name, der
 * für das Icon-Lookup in [CacheIcons] genutzt wird. 1:1 aus GCToolkit-Android
 * (com.gctoolkit.data.CacheTypes) übernommen, damit derselbe Typ immer auf dasselbe Icon
 * zeigt wie in der Schwester-App.
 */
object CacheTypes {

    private val longToShort: Map<String, String> = mapOf(
        "cache in trash out event" to "CITO",
        "community celebration event" to "CCE",
        "earthcache" to "Earth",
        "event cache" to "Event",
        "gps adventures exhibit" to "Maze",
        "gps adventures maze exhibit" to "Maze",
        "geocaching hq block party" to "Block Party",
        "geocaching hq celebration" to "HQEvent",
        "giga-event cache" to "Giga",
        "groundspeak hq" to "HQ",
        "letterbox hybrid" to "Letter",
        "locationless (reverse) cache" to "Reverse",
        "mega-event cache" to "Mega",
        "multi-cache" to "Multi",
        "project ape cache" to "APE",
        "traditional cache" to "Tradi",
        "unknown cache" to "Mystery",
        "unknown (mystery) cache" to "Mystery",
        "virtual cache" to "Virtual",
        "webcam cache" to "Webcam",
        "wherigo cache" to "Wherigo",
        "lab cache" to "Lab",
        "adventure lab" to "Lab"
    )

    fun normaliseTypeLong(raw: String): String {
        val key = raw.trim().lowercase()
        return longToShort[key] ?: raw.trim()
    }
}

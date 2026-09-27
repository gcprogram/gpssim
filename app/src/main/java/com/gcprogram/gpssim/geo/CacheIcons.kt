package com.gcprogram.gpssim.geo

/**
 * Icon-/Farbzuordnung pro Cache-Typ, aus GCToolkit-Android (com.gctoolkit.data.CacheIcons)
 * übernommen. Die SVGs liegen unter assets/cache_icons/<svgId>.svg (1:1 aus GCToolkit kopiert)
 * und werden zur Laufzeit über androidsvg gerendert (siehe MapIconFactory.kt).
 */
object CacheIcons {

    private const val UNKNOWN_ID = "type_unknown"
    private val unknownColor = 0xFF616161.toInt()

    private val svgIdByType: Map<String, String> = mapOf(
        "Tradi" to "type_traditional",
        "Multi" to "type_multi",
        "Virtual" to "type_virtual",
        "Letter" to "type_letterbox",
        "Event" to "type_event",
        "Mystery" to "type_mystery",
        "APE" to "type_ape",
        "Webcam" to "type_webcam",
        "Reverse" to "type_unknown",
        "CITO" to "type_cito",
        "Earth" to "type_earth",
        "Mega" to "type_mega",
        "Maze" to "type_maze",
        "Wherigo" to "type_wherigo",
        "CCE" to "type_specialevent",
        "HQ" to "type_hq",
        "HQEvent" to "type_specialevent",
        "Block Party" to "type_specialevent",
        "Giga" to "type_giga",
        "Lab" to "type_advlab",
    )

    private val colorByType: Map<String, Int> = mapOf(
        "Tradi" to 0xFF388E3C.toInt(),
        "Multi" to 0xFFF57C00.toInt(),
        "Virtual" to 0xFF0288D1.toInt(),
        "Letter" to 0xFF303F9F.toInt(),
        "Event" to 0xFFD32F2F.toInt(),
        "Mystery" to 0xFF303F9F.toInt(),
        "APE" to 0xFFAFB42B.toInt(),
        "Webcam" to 0xFF0288D1.toInt(),
        "Reverse" to 0xFF616161.toInt(),
        "CITO" to 0xFFD32F2F.toInt(),
        "Earth" to 0xFF0288D1.toInt(),
        "Mega" to 0xFFD32F2F.toInt(),
        "Maze" to 0xFFAFB42B.toInt(),
        "Wherigo" to 0xFF303F9F.toInt(),
        "CCE" to 0xFFD32F2F.toInt(),
        "HQ" to 0xFFAFB42B.toInt(),
        "HQEvent" to 0xFFD32F2F.toInt(),
        "Block Party" to 0xFFD32F2F.toInt(),
        "Giga" to 0xFFD32F2F.toInt(),
        "Lab" to 0xFF7B1FA2.toInt(),
    )

    /** Asset-Dateiname (inkl. Ordner) für das Icon eines Cache-Typs. */
    fun svgAsset(cacheType: String?): String =
        "cache_icons/" + (svgIdByType[cacheType] ?: UNKNOWN_ID) + ".svg"

    /** ARGB-Farbe für einen Cache-Typ (Fallback, falls SVG nicht geladen werden kann). */
    fun color(cacheType: String?): Int = colorByType[cacheType] ?: unknownColor
}

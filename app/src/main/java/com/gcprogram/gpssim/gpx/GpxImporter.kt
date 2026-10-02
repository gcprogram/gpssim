package com.gcprogram.gpssim.gpx

import com.gcprogram.gpssim.geo.CacheTypes
import com.gcprogram.gpssim.geo.CacheWaypoint
import com.gcprogram.gpssim.geo.GeoCache
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Liest eine Geocaching-GPX-Datei (Export vom offiziellen Client, c:geo, GSAK-PQ) und liefert
 * je Cache seine Grunddaten plus alle zusätzlichen Wegpunkte (Parkplatz, Final, Etappen, ...).
 *
 * Streaming via XmlPullParser statt DOM (analog GCToolkit-Android/GpxImporter.kt) - eine
 * Pocket-Query kann mehrere MB groß sein, es wird nie mehr als ein <wpt>-Teilbaum im Speicher
 * gehalten. Namespace-Präfixe werden ignoriert (nur der lokale Elementname zählt), damit
 * unterschiedliche GPX-Erzeuger toleriert werden.
 */
object GpxImporter {

    data class Result(val caches: List<GeoCache>)

    private val GC_RE = Regex("^GC[0-9A-Z]{1,5}$")

    fun parseStream(input: InputStream): Result {
        val bytes = input.readBytes()
        val isZip = bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
        return if (isZip) parseZip(bytes) else parseSingleGpx(bytes)
    }

    fun parseSingleGpx(bytes: ByteArray): Result {
        val caches = LinkedHashMap<String, MutableCache>()
        val extraWpts = ArrayList<Node>()
        streamWaypoints(bytes) { wpt ->
            if (!collectCache(wpt, caches)) extraWpts.add(wpt)
        }
        associateWaypoints(extraWpts, caches)
        return Result(caches.values.map { it.toGeoCache() })
    }

    /** GSAK-Pocket-Query-ZIP: <id>.gpx (Caches) + <id>-wpts.gpx (zusätzliche Wegpunkte). */
    fun parseZip(bytes: ByteArray): Result {
        val entries = ArrayList<ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.lowercase().endsWith(".gpx")) entries.add(zis.readBytes())
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        val caches = LinkedHashMap<String, MutableCache>()
        val extraWpts = ArrayList<Node>()
        for (data in entries) {
            streamWaypoints(data) { wpt ->
                if (!collectCache(wpt, caches)) extraWpts.add(wpt)
            }
        }
        associateWaypoints(extraWpts, caches)
        return Result(caches.values.map { it.toGeoCache() })
    }

    // -- XML-Baum, minimal (siehe GCToolkit-Android/GpxImporter.kt) -----------

    private class Node(val name: String, val attrs: Map<String, String>) {
        val children = ArrayList<Node>()
        val text = StringBuilder()
    }

    private fun streamWaypoints(bytes: ByteArray, onWpt: (Node) -> Unit) {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(ByteArrayInputStream(bytes), null)
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.depth == 2 && local(parser.name) == "wpt") {
                onWpt(readNode(parser))
            }
            event = parser.next()
        }
    }

    private fun readNode(p: XmlPullParser): Node {
        val attrs = HashMap<String, String>(p.attributeCount)
        for (i in 0 until p.attributeCount) attrs[local(p.getAttributeName(i))] = p.getAttributeValue(i)
        val node = Node(local(p.name), attrs)
        val depth = p.depth
        var ev = p.next()
        while (!(ev == XmlPullParser.END_TAG && p.depth == depth)) {
            when (ev) {
                XmlPullParser.START_TAG -> node.children.add(readNode(p))
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> node.text.append(p.text)
                XmlPullParser.END_DOCUMENT -> return node
            }
            ev = p.next()
        }
        return node
    }

    private fun local(name: String?): String = name?.substringAfterLast(':') ?: ""

    private fun firstChildByLocal(parent: Node, local: String): Node? =
        parent.children.firstOrNull { it.name == local }

    private fun childText(parent: Node, local: String): String =
        firstChildByLocal(parent, local)?.let { deepText(it) }?.trim() ?: ""

    private fun deepText(n: Node): String {
        if (n.children.isEmpty()) return n.text.toString()
        val sb = StringBuilder(n.text)
        for (c in n.children) sb.append(deepText(c))
        return sb.toString()
    }

    private fun deepChildText(parent: Node, path: List<String>): String {
        var cur: Node? = parent
        for ((idx, seg) in path.withIndex()) {
            cur = cur?.let { firstChildByLocal(it, seg) }
            if (cur == null) return ""
            if (idx == path.lastIndex) return deepText(cur).trim()
        }
        return ""
    }

    // -- Caches / Wegpunkte -----------------------------------------------------

    /** Extrahiert einen Cache aus einem <wpt>. Liefert false, wenn es kein Cache ist (-> Wegpunkt). */
    private fun collectCache(wpt: Node, out: LinkedHashMap<String, MutableCache>): Boolean {
        val name = childText(wpt, "name")
        val gs = firstChildByLocal(wpt, "cache")
        // Ein <wpt> ist der Cache selbst (nicht einer seiner zusätzlichen Wegpunkte), wenn er
        // einen <groundspeak:cache>-Block besitzt - das ist der zuverlässige Indikator aus der
        // GPX-Spezifikation selbst und funktioniert für ALLE Cache-Typen, auch Adventure Labs,
        // deren <name> KEIN "GCxxxxx"-Format hat, sondern eine GUID-artige Kennung wie
        // "AL81F2D2B7-F86E-4E9D-A5ED-C8A144240A52" (das GC_RE-Muster hat solche Labs bisher
        // komplett verworfen, wodurch auch ihre Stationen - ohne zugehörigen Cache - verloren
        // gingen). Nur als Fallback für GPX-Dateien ganz ohne diesen Block greift weiterhin das
        // klassische GC-Code-Format.
        if (gs == null && !GC_RE.matches(name)) return false

        val lat = wpt.attrs["lat"]?.toDoubleOrNull() ?: 0.0
        val lon = wpt.attrs["lon"]?.toDoubleOrNull() ?: 0.0

        val rawType = if (gs != null) childText(gs, "type")
        else childText(wpt, "type").substringAfterLast("|").trim()
        val cacheType = CacheTypes.normaliseTypeLong(rawType)

        val title = if (gs != null) childText(gs, "name").ifEmpty { name }
        else childText(wpt, "urlname").ifEmpty { name }

        out[name] = MutableCache(gccode = name, title = title, cacheType = cacheType, latitude = lat, longitude = lon)
        return true
    }

    /** Ordnet zusätzliche Wegpunkte ihrem Cache zu - per gsak:Parent, sonst (Ein-Cache-GPX) dem einzigen Cache. */
    private fun associateWaypoints(wpts: List<Node>, caches: LinkedHashMap<String, MutableCache>) {
        val suffixes: Map<String, String> = caches.keys.associateBy { it.substring(2) }

        fun resolveParent(name: String, parentGc: String?): String? {
            if (parentGc != null && caches.containsKey(parentGc)) return parentGc
            suffixes[name.substring(2.coerceAtMost(name.length))]?.let { return it }
            if (caches.size == 1) return caches.keys.first()
            return null
        }

        for (wpt in wpts) {
            val wname = childText(wpt, "name")
            if (wname.isEmpty()) continue

            val wtype = childText(wpt, "type").substringAfterLast("|").trim()
            val wtitle = childText(wpt, "urlname").ifEmpty { wname }
            val parentGc = deepChildText(wpt, listOf("wptExtension", "Parent")).ifEmpty { null }
            val parent = resolveParent(wname, parentGc) ?: continue
            val c = caches[parent] ?: continue

            val wlat = wpt.attrs["lat"]?.toDoubleOrNull() ?: 0.0
            val wlon = wpt.attrs["lon"]?.toDoubleOrNull() ?: 0.0
            if (wlat == 0.0 && wlon == 0.0) continue

            c.waypoints.add(
                CacheWaypoint(
                    name = wname, title = wtitle, latitude = wlat, longitude = wlon,
                    type = wtype.ifEmpty { "Waypoint" }
                )
            )
        }
    }

    private class MutableCache(
        val gccode: String, val title: String, val cacheType: String,
        val latitude: Double, val longitude: Double
    ) {
        val waypoints = ArrayList<CacheWaypoint>()
        fun toGeoCache() = GeoCache(
            gccode = gccode, title = title, cacheType = cacheType,
            postedLatitude = latitude, postedLongitude = longitude,
            waypoints = waypoints.toList()
        )
    }
}

package com.gcprogram.gpssim.geo

import java.util.regex.Pattern

/**
 * Parst Geocaching-typische WGS84-Koordinaten im Freitext-Format
 * N dd° mm.mmm  E ddd° mm.mmm  (bzw. S/W), wie sie z.B. aus GC-Listings
 * kopiert werden. Toleriert Leerzeichen zwischen Buchstabe/Grad/Minuten,
 * Komma statt Punkt als Dezimaltrenner, und Text drumherum.
 */
data class ParsedCoordinate(val latitude: Double, val longitude: Double)

object CoordinateParser {

    // Beispiel: N 49° 12.345 E 008° 40.123  oder  N49 12.345 E008 40.123
    private val PATTERN: Pattern = Pattern.compile(
        "([NnSs])\\s*(\\d{1,2})\\s*°?\\s*(\\d{1,2}(?:[.,]\\d+)?)\\s*[' ]*" +
            "[,;\\s]+" +
            "([EeWw])\\s*(\\d{1,3})\\s*°?\\s*(\\d{1,2}(?:[.,]\\d+)?)\\s*[' ]*"
    )

    fun parse(text: String): ParsedCoordinate? {
        val matcher = PATTERN.matcher(text.trim())
        if (!matcher.find()) return null

        val latHem = matcher.group(1)!!.uppercase()
        val latDeg = matcher.group(2)!!.toInt()
        val latMin = matcher.group(3)!!.replace(',', '.').toDouble()

        val lonHem = matcher.group(4)!!.uppercase()
        val lonDeg = matcher.group(5)!!.toInt()
        val lonMin = matcher.group(6)!!.replace(',', '.').toDouble()

        if (latMin >= 60.0 || lonMin >= 60.0) return null

        var lat = latDeg + latMin / 60.0
        var lon = lonDeg + lonMin / 60.0

        if (latHem == "S") lat = -lat
        if (lonHem == "W") lon = -lon

        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null

        return ParsedCoordinate(lat, lon)
    }

    /** Formatiert Dezimalgrad zurück ins Geocaching-Anzeigeformat, z.B. für Debug/Anzeige. */
    fun format(lat: Double, lon: Double): String {
        val latHem = if (lat >= 0) "N" else "S"
        val lonHem = if (lon >= 0) "E" else "W"
        val absLat = Math.abs(lat)
        val absLon = Math.abs(lon)
        val latDeg = absLat.toInt()
        val latMin = (absLat - latDeg) * 60.0
        val lonDeg = absLon.toInt()
        val lonMin = (absLon - lonDeg) * 60.0
        return "%s%02d° %06.3f %s%03d° %06.3f".format(latHem, latDeg, latMin, lonHem, lonDeg, lonMin)
    }
}

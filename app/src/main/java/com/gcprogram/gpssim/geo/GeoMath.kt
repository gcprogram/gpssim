package com.gcprogram.gpssim.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    private const val EARTH_RADIUS_M = 6371000.0

    /** Haversine-Distanz in Metern. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    /** Linear interpoliert zwischen zwei Punkten bei Fraction t in [0,1]. Für kurze Strecken ausreichend genau. */
    fun interpolate(lat1: Double, lon1: Double, lat2: Double, lon2: Double, t: Double): Pair<Double, Double> {
        val lat = lat1 + (lat2 - lat1) * t
        val lon = lon1 + (lon2 - lon1) * t
        return lat to lon
    }

    /** Peilung (Bearing) in Grad von Punkt 1 nach Punkt 2, für die Mock-Location "bearing"-Angabe. */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        val theta = atan2(y, x)
        return ((Math.toDegrees(theta) + 360) % 360).toFloat()
    }
}

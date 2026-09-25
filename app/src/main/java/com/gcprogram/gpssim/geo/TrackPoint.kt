package com.gcprogram.gpssim.geo

data class TrackPoint(val latitude: Double, val longitude: Double)

data class SimulatedPosition(
    val latitude: Double,
    val longitude: Double,
    val bearing: Float,
    val speedMps: Float
)

package com.gcprogram.gpssim.geo

/**
 * Geschwindigkeits-Voreinstellungen für die Track-Simulation, wählbar über die
 * Icon-Buttons im MapScreen statt einer numerischen Eingabe.
 *
 * ROCKET ist ein Sonderfall ohne feste km/h-Angabe: TrackSimulator "teleportiert" bei
 * jedem Segment praktisch sofort bis 100 m vor den Zielpunkt und legt nur die letzten
 * 100 m in Fahrradgeschwindigkeit zurück (ist ein Segment kürzer als 100 m, wird es
 * komplett in Fahrradgeschwindigkeit zurückgelegt statt zu teleportieren).
 */
enum class SpeedPreset(val label: String, val kmh: Double?) {
    WALK("Fußgänger", 5.0),
    BIKE("Fahrrad", 18.0),
    CAR("Auto", 60.0),
    ROCKET("Rakete", null)
}

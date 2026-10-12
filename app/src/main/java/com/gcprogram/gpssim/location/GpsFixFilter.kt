package com.gcprogram.gpssim.location

import com.gcprogram.gpssim.geo.GeoMath
import com.gcprogram.gpssim.geo.RecordedPoint

/**
 * Entscheidet, ob ein neu eintreffender GPS-/Netz-Fix glaubwürdig genug ist, um in den
 * aufgezeichneten Track aufgenommen zu werden - oder ob er ein Ausreißer ist, der verworfen
 * werden soll (siehe TrackRecordingService.onLocationChanged()).
 *
 * HINTERGRUND (echter Vergleich mit Geo Tracker für Android an einem Tag in Budapest): Unsere
 * Aufzeichnung kam auf eine aufsummierte Weglänge von ~168 km, Geo Tracker für dieselbe Route nur
 * auf ~32 km - der Unterschied bestand aus GPS-/Netz-Rauschen, u.a. mehrfache Sprünge von
 * 600-1660 m innerhalb von unter 8 Sekunden (teils über 700 km/h implizierte Geschwindigkeit).
 * GPS_PROVIDER und NETWORK_PROVIDER liefen dabei beide gleichzeitig (siehe
 * TrackRecordingService.registerListener()) und wurden ungeprüft übernommen.
 *
 * NETWORK_PROVIDER bleibt bewusst aktiv (WLAN-Fixes drinnen sind oft 20 m oder besser und wichtig,
 * wenn GPS z.B. in Gebäuden keinen Empfang hat) - statt also eine Quelle pauschal auszuschließen,
 * prüft [isPlausible] für JEDEN Fix (unabhängig vom Provider), ob die Positionsänderung seit dem
 * letzten akzeptierten Punkt überhaupt physikalisch möglich ist. Ein Fix, der das nicht erfüllt,
 * wird verworfen (weder in TrackRecorder.points noch in die Autosave-Datei übernommen) - der
 * zuletzt akzeptierte Punkt bleibt bestehen, bis ein plausibler Nachfolger eintrifft.
 */
object GpsFixFilter {

    // Großzügig genug für Bahn/Tram/Auto (126 km/h), liegt aber weit unter den beobachteten
    // Ausreißern (>700 km/h) - fängt also die eindeutigen Teleports ab, ohne echte schnelle
    // Fortbewegung fälschlich zu verwerfen.
    private const val MAX_PLAUSIBLE_SPEED_MPS = 35.0

    // Genauigkeits-Toleranz: beide Fixes dürfen jeweils um ihren eigenen Ungenauigkeitsradius
    // vom "wahren" Punkt abweichen - das darf nicht zusätzlich als "Bewegung" angerechnet werden,
    // sonst würde man knapp-plausible, aber ungenaue Fixes systematisch verwerfen. Fehlt die
    // Genauigkeit (hasAccuracy() == false), wird konservativ 0 angenommen statt der Fix bevorzugt
    // durchgelassen.
    private const val DEFAULT_ACCURACY_SLACK_M = 0.0

    /**
     * @param last letzter akzeptierter Punkt dieser Aufzeichnung, oder null für den allerersten
     *             Fix (der wird immer akzeptiert - ohne Referenzpunkt lässt sich nichts prüfen).
     */
    fun isPlausible(
        last: RecordedPoint?,
        candidateLat: Double,
        candidateLon: Double,
        candidateTimeMillis: Long,
        candidateAccuracyMeters: Float?
    ): Boolean {
        if (last == null) return true
        val dtSeconds = (candidateTimeMillis - last.timestampMillis) / 1000.0
        // Ein Fix, der zeitlich nicht nach dem letzten liegt (doppelter/verspäteter Fix von einem
        // der beiden parallel laufenden Provider), hat keine sinnvolle "Geschwindigkeit" - sicherheitshalber
        // verwerfen statt durch Division durch ~0 einen riesigen oder undefinierten Wert zu erzeugen.
        if (dtSeconds <= 0.0) return false

        val distance = GeoMath.distanceMeters(last.latitude, last.longitude, candidateLat, candidateLon)
        val accuracySlack = (last.accuracyMeters ?: DEFAULT_ACCURACY_SLACK_M.toFloat()) +
            (candidateAccuracyMeters ?: DEFAULT_ACCURACY_SLACK_M.toFloat())
        val maxPlausibleDistance = MAX_PLAUSIBLE_SPEED_MPS * dtSeconds + accuracySlack
        return distance <= maxPlausibleDistance
    }
}

package com.gcprogram.gpssim.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * VORARBEIT für das geplante Mini-Karten-Overlay (schwebendes Fenster über anderen Apps, das
 * mit der simulierten Position mitwandert) - siehe Chat-Notiz. Diese Datei ist bewusst nur die
 * Berechtigungs-Vorbereitung, NICHT die eigentliche Aktivierung: nichts hier fragt die
 * Berechtigung von sich aus an oder startet einen Overlay-Service. Der letzte Schritt (Service
 * bauen + aus der UI heraus anfordern + in der Manifest registrieren) folgt erst, wenn das
 * Overlay tatsächlich umgesetzt werden soll.
 *
 * "Über anderen Apps einblenden" (SYSTEM_ALERT_WINDOW) ist eine "special permission" - sie kann
 * NICHT über den normalen Laufzeit-Dialog angefragt werden, sondern nur über eine eigene
 * Systemeinstellungsseite, die der Nutzer manuell bestätigt (siehe [requestIntent]).
 */
object OverlayPermission {

    /** true, wenn die App bereits Fenster über anderen Apps zeichnen darf. */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * Intent zur Systemeinstellungsseite, auf der der Nutzer die Berechtigung manuell erteilt.
     * Noch NICHT mit startActivity() aufgerufen - das ist bewusst Teil des späteren, noch nicht
     * gebauten letzten Schritts (z.B. ein Button "Mini-Karte aktivieren" in den Einstellungen).
     */
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
}

package com.gcprogram.gpssim.location

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Prüft, ob Android für diese App noch "Energiesparen"/Akku-Optimierung anwendet, und bietet den
 * direkten System-Dialog zum Ausschließen an (siehe REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in
 * AndroidManifest). Relevant für TrackerScreen: Android selbst nimmt einen laufenden Foreground-
 * Service zwar von Doze/App-Standby aus, aber manche Hersteller (Xiaomi/Huawei/Samsung &co.)
 * setzen zusätzlich eigene, aggressivere Akku-Manager drauf, die auch Foreground-Services
 * trotzdem beenden können - der Hinweis kann das nicht verhindern, aber zumindest den Android-
 * seitigen Teil davon ausschließen und den Nutzer auf das Restrisiko hinweisen.
 */
object BatteryOptimization {

    fun isIgnoring(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Öffnet den System-Dialog, der direkt nach einer Bestätigung die App von der
     *  Akku-Optimierung ausnimmt - kein Umweg über die Einstellungen-Liste nötig. */
    fun requestIgnoreIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
}

package com.gcprogram.gpssim.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import com.gcprogram.gpssim.location.MockLocationController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * VORARBEIT für das geplante Mini-Karten-Overlay (siehe Chat-Notiz vom 29.09.2026) - ein kleines
 * schwebendes Kartenfenster über anderen Apps, das zentrisch mit der simulierten Position
 * mitwandert, mit einem X zum Schließen (die Simulation läuft dabei im Hintergrund weiter).
 *
 * ABSICHTLICH NOCH NICHT AKTIV: dieser Service ist
 *  - NICHT in AndroidManifest.xml registriert,
 *  - wird von NIRGENDS aus der App gestartet,
 *  - fragt NICHT die "Über anderen Apps anzeigen"-Berechtigung an (siehe OverlayPermission.kt).
 *
 * Das ist bewusst nur das Gerüst für den TrackRepository/MockLocationController-Datenzugriff,
 * den ein solches Fenster braucht - die eigentliche Aktivierung (Manifest-Eintrag, Berechtigungs-
 * Dialog aus der UI anstoßen, WindowManager.addView() mit einer echten kleinen MapView statt
 * eines Platzhalters) ist der noch offene letzte Schritt.
 *
 * Geplanter Ablauf, sobald umgesetzt:
 * 1. UI fragt [OverlayPermission.canDrawOverlays] ab, zeigt ggf. einen Button, der
 *    [OverlayPermission.requestIntent] via startActivity() öffnet.
 * 2. Nach Bestätigung startet die UI diesen Service (dann in der Manifest registriert).
 * 3. onCreate() hängt eine kleine [ComposeView] (oder eine eigene Mini-osmdroid-MapView) via
 *    WindowManager als TYPE_APPLICATION_OVERLAY ein, mit demselben Zoomfaktor wie die Hauptkarte
 *    und einem X-Button, der nur diese Ansicht schließt (stopSelf()) - die Simulation
 *    (MockLocationController/MockLocationService) läuft komplett unabhängig weiter.
 * 4. onDestroy() entfernt die View wieder aus dem WindowManager.
 */
class MiniMapOverlayService : Service() {

    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager

        // Bereits verfügbar (kein weiterer Vorbereitungsschritt nötig): die simulierte Position,
        // die das Overlay anzeigen würde. Läuft schon jetzt mit, sobald der Service tatsächlich
        // gestartet wird - der letzte Schritt ist nur noch die WindowManager-View selbst.
        MockLocationController.currentPosition
            .onEach { pos ->
                // TODO (letzter Schritt): Mini-Karte auf pos zentrieren, sobald die eigentliche
                // Overlay-View existiert. Aktuell bewusst ohne Wirkung.
            }
            .launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO (letzter Schritt): WindowManager.LayoutParams mit TYPE_APPLICATION_OVERLAY,
        // FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT, Gravity.CENTER, feste kleine Breite/Höhe -
        // dann windowManager?.addView(overlayView, layoutParams).
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        // Referenz für die spätere Fenstergröße - unbenutzt, bis addView() tatsächlich passiert.
        private const val OVERLAY_WIDTH_DP = 160
        private const val OVERLAY_HEIGHT_DP = 160
        private const val OVERLAY_FORMAT = PixelFormat.TRANSLUCENT
        private const val OVERLAY_GRAVITY = Gravity.CENTER
    }
}

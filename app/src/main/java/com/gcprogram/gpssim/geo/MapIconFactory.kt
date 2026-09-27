package com.gcprogram.gpssim.geo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.caverock.androidsvg.SVG

/**
 * Baut Marker-Drawables für die Karte: das Cache-Typ-Icon (SVG aus assets/cache_icons, wie in
 * GCToolkit-Android) für den Cache selbst, und einen einfachen blauen "umgekehrten Tropfen"
 * für alle übrigen Wegpunkte eines Caches (Parkplatz, Final, Etappen, ...).
 *
 * Gerenderte Bitmaps werden pro Schlüssel zwischengespeichert, damit bei vielen Markern nicht
 * jedes Mal neu gerendert wird.
 */
object MapIconFactory {

    private val cache = HashMap<String, Drawable>()

    /** Cache-Typ-Icon (rund, mit weißem Ring), analog GCToolkit-Android MapIconFactory.typeIcon(). */
    fun cacheTypeMarker(context: Context, cacheType: String?): Drawable {
        val key = "type|$cacheType"
        return cache.getOrPut(key) { renderTypeIcon(context, cacheType) }
    }

    /** Blauer, nach unten spitz zulaufender Marker (Standard-Kartennadel-Form) für sonstige Wegpunkte. */
    fun waypointMarker(context: Context): Drawable {
        val key = "waypoint"
        return cache.getOrPut(key) { renderTeardrop(context, 0xFF1565C0.toInt()) }
    }

    private fun renderTypeIcon(context: Context, cacheType: String?): Drawable {
        val density = context.resources.displayMetrics.density
        val size = (34 * density).toInt().coerceAtLeast(28)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val svg = runCatching {
            context.assets.open(CacheIcons.svgAsset(cacheType)).use { SVG.getFromInputStream(it) }
        }.getOrNull()

        if (svg != null) {
            svg.setDocumentWidth(size.toFloat())
            svg.setDocumentHeight(size.toFloat())
            svg.renderToCanvas(canvas)
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CacheIcons.color(cacheType) }
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2, p)
        }
        return BitmapDrawable(context.resources, bmp)
    }

    /** Klassische Kartennadel-Silhouette (Kreis + Spitze nach unten), einfarbig gefüllt mit weißem Rand. */
    private fun renderTeardrop(context: Context, color: Int): Drawable {
        val density = context.resources.displayMetrics.density
        val w = (26 * density).toInt().coerceAtLeast(20)
        val h = (34 * density).toInt().coerceAtLeast(26)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val cx = w / 2f
        val r = w / 2f - density
        val cy = r + density

        val path = android.graphics.Path().apply {
            addCircle(cx, cy, r, android.graphics.Path.Direction.CW)
        }
        // Spitze unten: Dreieck von den unteren Kreisrändern zur Marker-Spitze.
        val tip = android.graphics.Path().apply {
            moveTo(cx - r * 0.72f, cy + r * 0.55f)
            lineTo(cx, h.toFloat())
            lineTo(cx + r * 0.72f, cy + r * 0.55f)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawPath(path, fill)
        canvas.drawPath(tip, fill)

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = density * 1.2f
        }
        canvas.drawCircle(cx, cy, r, stroke)

        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFFFFFFF.toInt() }
        canvas.drawCircle(cx, cy, r * 0.32f, dot)

        return BitmapDrawable(context.resources, bmp)
    }
}

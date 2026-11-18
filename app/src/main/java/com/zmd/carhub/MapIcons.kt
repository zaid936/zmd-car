package com.zmd.carhub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser
import kotlin.math.min

/**
 * The map screen's icons, drawn from path data on a 24 x 24 grid (thin rounded lines, in the
 * style of the open "Feather" icon set, MIT licence), plus the turn arrows and the map pins.
 */
object MapIcons {
    const val SEARCH = "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM21 21l-4.35-4.35"
    const val CLOSE = "M18 6L6 18M6 6l12 12"
    const val PLUS = "M12 5v14M5 12h14"
    const val MINUS = "M5 12h14"
    const val LOCATE = "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM12 2v4M12 18v4M2 12h4M18 12h4"
    const val LAYERS = "M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5"
    const val DOWNLOAD = "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3"
    const val VIDEO = "M23 7l-7 5 7 5V7zM3 5h11a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z"
    const val GEAR = "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h0a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h0a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v0a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"
    const val STAR = "M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"
    const val HOME = "M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM9 22V12h6v10"
    const val WORK = "M4 7h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2zM16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16"
    const val VOLUME = "M11 5L6 9H2v6h4l5 4V5zM19.07 4.93a10 10 0 0 1 0 14.14M15.54 8.46a5 5 0 0 1 0 7.07"
    const val MUTE = "M11 5L6 9H2v6h4l5 4V5zM23 9l-6 6M17 9l6 6"
    const val PLAY = "M6 4l13 8-13 8V4z"
    const val NAVIGATE = "M3 11l19-9-9 19-2-8-8-2z"
    const val CLOCK = "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 6v6l4 2"
    const val PIN = "M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0zM12 13a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"
    const val COMPASS = "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM16.24 7.76l-2.12 6.36-6.36 2.12 2.12-6.36 6.36-2.12z"
    const val OVERVIEW = "M8 3H5a2 2 0 0 0-2 2v3M21 8V5a2 2 0 0 0-2-2h-3M16 21h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3"
    const val CHECK = "M20 6L9 17l-5-5"
    const val TRASH = "M3 6h18M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6M10 11v6M14 11v6M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"
    const val FLAG = "M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1zM4 22v-7"
    const val GLOBE = "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM2 12h20M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"
    const val CUBE = "M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16zM3.27 6.96L12 12.01l8.73-5.05M12 22.08V12"
    const val ROAD = "M5 21L9 3M19 21L15 3M12 5v2M12 11v2M12 17v2"

    // Categories.
    const val FUEL = "M3 22V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v18M2 22h14M6 6h6v5H6zM15 10h2a2 2 0 0 1 2 2v5a1.5 1.5 0 0 0 3 0V8l-3-3"
    const val BOLT = "M13 2L3 14h9l-1 8 10-12h-9l1-8z"
    const val FOOD = "M6 2v7a2 2 0 0 0 4 0V2M8 11v11M17 2c-2 2-3 4-3 7 0 2 1 3 3 3v10"
    const val COFFEE = "M18 8h1a4 4 0 0 1 0 8h-1M2 8h16v9a4 4 0 0 1-4 4H6a4 4 0 0 1-4-4V8zM6 1v3M10 1v3M14 1v3"
    const val CART = "M9 22a1 1 0 1 0 0-2 1 1 0 0 0 0 2zM20 22a1 1 0 1 0 0-2 1 1 0 0 0 0 2zM1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72a2 2 0 0 0 2-1.61L23 6H6"
    const val PARKING = "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM9 17V7h4a3 3 0 0 1 0 6H9"
    const val HOSPITAL = "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM12 8v8M8 12h8"
    const val PHARMACY = "M9 3h6v6h6v6h-6v6H9v-6H3V9h6z"
    const val MOON = "M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"
    const val BANK = "M3 5h18a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM1 10h22"
    const val TOOL = "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"
    const val BED = "M2 4v16M2 8h18a2 2 0 0 1 2 2v10M2 17h20M6 8v9"
    const val SHIELD = "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"

    // Turn arrows (the car comes from the bottom of the grid).
    private const val A_STRAIGHT = "M12 21V4M6 10l6-6 6 6"
    private const val A_RIGHT = "M7 21v-8a4 4 0 0 1 4-4h8M15 4l5 5-5 5"
    private const val A_LEFT = "M17 21v-8a4 4 0 0 0-4-4H5M9 4L4 9l5 5"
    private const val A_SLIGHT_RIGHT = "M9 21v-6.5c0-1.6.6-3.1 1.8-4.2L17 4M11 4h6v6"
    private const val A_SLIGHT_LEFT = "M15 21v-6.5c0-1.6-.6-3.1-1.8-4.2L7 4M13 4H7v6"
    private const val A_SHARP_RIGHT = "M7 21V9a3 3 0 0 1 5.1-2.1L18 14M18 8v6h-6"
    private const val A_SHARP_LEFT = "M17 21V9a3 3 0 0 0-5.1-2.1L6 14M6 8v6h6"
    private const val A_KEEP_RIGHT = "M10 21v-8M10 13L5 6M10 13l7-8M12 5h5v5"
    private const val A_KEEP_LEFT = "M14 21v-8M14 13l5-7M14 13L7 5M12 5H7v5"
    private const val A_UTURN = "M7 21V9a5 5 0 0 1 10 0v7M13 12l4 4 4-4"
    private const val A_ROUNDABOUT = "M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM12 21v-5M15 9l4-5M15 4h4v4"

    fun arrow(sign: Int): String = when (sign) {
        MapRoute.LEFT -> A_LEFT
        MapRoute.SLIGHT_LEFT -> A_SLIGHT_LEFT
        MapRoute.SHARP_LEFT -> A_SHARP_LEFT
        MapRoute.RIGHT -> A_RIGHT
        MapRoute.SLIGHT_RIGHT -> A_SLIGHT_RIGHT
        MapRoute.SHARP_RIGHT -> A_SHARP_RIGHT
        MapRoute.KEEP_LEFT -> A_KEEP_LEFT
        MapRoute.KEEP_RIGHT -> A_KEEP_RIGHT
        MapRoute.ROUNDABOUT, MapRoute.LEAVE_ROUNDABOUT -> A_ROUNDABOUT
        MapRoute.U_TURN, MapRoute.U_TURN_LEFT, MapRoute.U_TURN_RIGHT -> A_UTURN
        MapRoute.FINISH, MapRoute.VIA -> FLAG
        else -> A_STRAIGHT
    }

    /** The icon for a kind of place in search results. */
    fun ofKind(kind: String): String = when (kind) {
        "fuel" -> FUEL
        "charging" -> BOLT
        "restaurant" -> FOOD
        "cafe" -> COFFEE
        "market", "mall", "shop" -> CART
        "parking" -> PARKING
        "hospital" -> HOSPITAL
        "pharmacy" -> PHARMACY
        "mosque", "church", "worship" -> MOON
        "bank", "atm" -> BANK
        "car" -> TOOL
        "hotel" -> BED
        "police", "gov" -> SHIELD
        "street" -> ROAD
        "city", "town", "village", "state", "province", "region", "district", "county" -> GLOBE
        "home" -> HOME
        "work" -> WORK
        "saved" -> STAR
        "recent" -> CLOCK
        else -> PIN
    }

    /** A drawable of one icon. [stroke] is in grid units (the grid is 24 wide). */
    fun icon(data: String, color: Int, stroke: Float = 2f, fill: Boolean = false): Drawable = PathIcon(data, color, stroke, fill)

    private class PathIcon(data: String, color: Int, private val stroke: Float, fill: Boolean) : Drawable() {
        private val path: Path = runCatching { PathParser.createPathFromPathData(data) }.getOrNull()
            ?: Path().apply { addCircle(12f, 12f, 7f, Path.Direction.CW) }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = if (fill) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val s = min(b.width(), b.height()) / 24f
            if (s <= 0f) return
            canvas.save()
            canvas.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f)
            canvas.scale(s, s)
            paint.strokeWidth = stroke
            canvas.drawPath(path, paint)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = -1
        override fun getIntrinsicHeight(): Int = -1
    }

    /** A teardrop map pin with a white centre, as a bitmap for the map style. */
    fun pinBitmap(ctx: Context, color: Int, heightDp: Int = 46): Bitmap {
        val h = Ui.dp(ctx, heightDp)
        val w = (h * 0.72f).toInt()
        val bmp = Bitmap.createBitmap(w, h + Ui.dp(ctx, 4), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = w / 2f - Ui.dp(ctx, 2)
        val cx = w / 2f
        val cy = r + Ui.dp(ctx, 2)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0x55000000 }
        c.drawOval(RectF(cx - r * 0.55f, h - Ui.dp(ctx, 3).toFloat(), cx + r * 0.55f, h + Ui.dp(ctx, 3).toFloat()), shadow)
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val p = Path()
        p.moveTo(cx, h.toFloat())
        p.cubicTo(cx - r * 0.25f, h - r * 0.9f, cx - r, cy + r * 0.95f, cx - r, cy)
        p.arcTo(RectF(cx - r, cy - r, cx + r, cy + r), 180f, 180f)
        p.cubicTo(cx + r, cy + r * 0.95f, cx + r * 0.25f, h - r * 0.9f, cx, h.toFloat())
        p.close()
        c.drawPath(p, body)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = Ui.dp(ctx, 2).toFloat()
        }
        c.drawPath(p, rim)
        c.drawCircle(cx, cy, r * 0.38f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFFFFFFF.toInt() })
        return bmp
    }
}

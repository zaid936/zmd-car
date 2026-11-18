package com.zmd.carhub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * What is drawn on the map where the car is: one of several navigation arrows, or a real car.
 *
 * The cars are real 3D models (CC BY, credits in assets/vehicles/credits.json), rendered on the PC
 * from straight above with studio light and a soft shadow, in every paint colour
 * (A:\byd\MapData\vehicles\render.py). They are pictures here, so the map can turn them like the arrow.
 */
object VehicleArt {

    enum class Kind { ARROW, PHOTO }

    /** [style] = arrow look (for arrows) or the picture's file prefix (for cars). */
    class Model(val id: String, val name: String, val kind: Kind, val style: String)

    val models = listOf(
        Model("arrow", "سهم ثلاثي", Kind.ARROW, "3d"),
        Model("arrow-waze", "سهم دائري", Kind.ARROW, "bubble"),
        Model("arrow-chevron", "سهم كلاسيكي", Kind.ARROW, "chevron"),
        Model("arrow-neon", "سهم مضيء", Kind.ARROW, "neon"),
        Model("byd-dolphin", "BYD Dolphin", Kind.PHOTO, "byd-dolphin"),
        Model("byd-seal", "BYD Seal", Kind.PHOTO, "byd-seal"),
        Model("toyota-camry", "Toyota Camry", Kind.PHOTO, "toyota-camry"),
        Model("kia-picanto", "Kia Picanto", Kind.PHOTO, "kia-picanto"),
    )

    class Paintwork(val key: String, val name: String, val color: Int)

    val colors = listOf(
        Paintwork("white", "أبيض", 0xFFF2F4F7.toInt()),
        Paintwork("black", "أسود", 0xFF1B1E24.toInt()),
        Paintwork("grey", "رصاصي", 0xFF6B7380.toInt()),
        Paintwork("silver", "فضي", 0xFFB9C0CA.toInt()),
        Paintwork("blue", "أزرق", 0xFF2F6FE0.toInt()),
        Paintwork("red", "أحمر", 0xFFD23B3B.toInt()),
        Paintwork("green", "أخضر", 0xFF3E8F6A.toInt()),
        Paintwork("sand", "رملي", 0xFFC9B48E.toInt()),
    )

    fun model(id: String?): Model = models.firstOrNull { it.id == id } ?: models[0]

    private fun paintwork(color: Int): Paintwork = colors.firstOrNull { it.color == color } ?: colors[0]

    /**
     * The marker as a bitmap, pointing up, centred on the vehicle's middle (it turns about its centre).
     * [lengthDp]: how long the vehicle looks. For arrows, [color] is the arrow colour.
     */
    fun bitmap(ctx: Context, m: Model, color: Int, glowColor: Int, lengthDp: Float = 64f): Bitmap {
        val d = ctx.resources.displayMetrics.density
        if (m.kind == Kind.PHOTO) {
            val pw = paintwork(color)
            val src = runCatching {
                ctx.assets.open("vehicles/${m.style}-${pw.key}.webp").use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (src != null) {
                // The car is 80% of the picture's height (the rest is room for its shadow).
                val side = (lengthDp * d / 0.8f).toInt().coerceAtLeast(24)
                return Bitmap.createScaledBitmap(src, side, side, true)
            }
        }
        val len = lengthDp * d
        val side = (len * 1.7f).toInt()
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = side / 2f
        val cy = side / 2f
        val arrowColor = if (m.kind == Kind.ARROW) color else Ui.ACCENT
        when (m.style) {
            "bubble" -> bubbleArrow(c, cx, cy, len, arrowColor)
            "chevron" -> chevronArrow(c, cx, cy, len, arrowColor)
            "neon" -> neonArrow(c, cx, cy, len, glowColor)
            else -> arrow3d(c, cx, cy, len, arrowColor, glowColor)
        }
        return bmp
    }

    // ------------------------------------------------------------------ arrows

    /** Solid arrow: lit and shaded faces meeting at a ridge, white rim, shadow and glow. */
    private fun arrow3d(c: Canvas, cx: Float, cy: Float, len: Float, color: Int, glow: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(cx, cy, len * 0.7f, intArrayOf(withAlpha(glow, 90), withAlpha(glow, 0)), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, len * 0.7f, p)
        p.shader = null
        val s = len * 0.42f
        val tip = cy - s
        val back = cy + s * 0.78f
        val notch = cy + s * 0.32f
        val half = s * 0.82f
        val outline = Path().apply { moveTo(cx, tip); lineTo(cx + half, back); lineTo(cx, notch); lineTo(cx - half, back); close() }
        p.color = 0x73000000
        p.maskFilter = BlurMaskFilter(len * 0.06f, BlurMaskFilter.Blur.NORMAL)
        c.save(); c.translate(len * 0.03f, len * 0.06f); c.drawPath(outline, p); c.restore()
        p.maskFilter = null
        p.color = Color.WHITE
        p.style = Paint.Style.STROKE
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = len * 0.07f
        c.drawPath(outline, p)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(cx - half, tip, cx, back, shade(color, 0.35f), shade(color, 0.05f), Shader.TileMode.CLAMP)
        c.drawPath(Path().apply { moveTo(cx, tip); lineTo(cx, notch); lineTo(cx - half, back); close() }, p)
        p.shader = LinearGradient(cx, tip, cx + half, back, shade(color, -0.12f), shade(color, -0.42f), Shader.TileMode.CLAMP)
        c.drawPath(Path().apply { moveTo(cx, tip); lineTo(cx + half, back); lineTo(cx, notch); close() }, p)
        p.shader = null
        p.color = 0x99FFFFFF.toInt()
        p.strokeWidth = len * 0.018f
        p.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx, tip + s * 0.12f, cx, notch - s * 0.06f, p)
    }

    /** A round white bubble with a coloured arrow inside, like the big community navigation apps. */
    private fun bubbleArrow(c: Canvas, cx: Float, cy: Float, len: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = len * 0.42f
        p.color = 0x59000000
        p.maskFilter = BlurMaskFilter(len * 0.07f, BlurMaskFilter.Blur.NORMAL)
        c.drawCircle(cx + len * 0.02f, cy + len * 0.05f, r, p)
        p.maskFilter = null
        p.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.4f, r * 1.4f, intArrayOf(Color.WHITE, 0xFFE3E9F2.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = null
        p.color = shade(color, 0.1f)
        p.style = Paint.Style.STROKE
        p.strokeWidth = len * 0.035f
        c.drawCircle(cx, cy, r - len * 0.03f, p)
        p.style = Paint.Style.FILL
        val s = r * 0.62f
        p.shader = LinearGradient(cx, cy - s, cx, cy + s, shade(color, 0.25f), shade(color, -0.2f), Shader.TileMode.CLAMP)
        c.drawPath(Path().apply {
            moveTo(cx, cy - s)
            lineTo(cx + s * 0.78f, cy + s * 0.72f)
            quadTo(cx, cy + s * 0.25f, cx - s * 0.78f, cy + s * 0.72f)
            close()
        }, p)
        p.shader = null
    }

    /** The classic: a blue dot with a white ring and a pointed heading cone. */
    private fun chevronArrow(c: Canvas, cx: Float, cy: Float, len: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Heading cone (soft, wide).
        p.shader = LinearGradient(cx, cy - len * 0.75f, cx, cy, withAlpha(color, 0), withAlpha(color, 140), Shader.TileMode.CLAMP)
        c.drawPath(Path().apply { moveTo(cx, cy); lineTo(cx - len * 0.36f, cy - len * 0.72f); quadTo(cx, cy - len * 0.85f, cx + len * 0.36f, cy - len * 0.72f); close() }, p)
        p.shader = null
        p.color = 0x4D000000
        p.maskFilter = BlurMaskFilter(len * 0.05f, BlurMaskFilter.Blur.NORMAL)
        c.drawCircle(cx, cy + len * 0.03f, len * 0.24f, p)
        p.maskFilter = null
        p.color = Color.WHITE
        c.drawCircle(cx, cy, len * 0.24f, p)
        p.color = color
        c.drawCircle(cx, cy, len * 0.18f, p)
        p.color = Color.WHITE
        val s = len * 0.11f
        c.drawPath(Path().apply { moveTo(cx, cy - s); lineTo(cx + s * 0.85f, cy + s * 0.8f); lineTo(cx, cy + s * 0.35f); lineTo(cx - s * 0.85f, cy + s * 0.8f); close() }, p)
    }

    /** A glowing outline arrow (looks best on the night map). */
    private fun neonArrow(c: Canvas, cx: Float, cy: Float, len: Float, glow: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val s = len * 0.42f
        val path = Path().apply { moveTo(cx, cy - s); lineTo(cx + s * 0.8f, cy + s * 0.78f); lineTo(cx, cy + s * 0.34f); lineTo(cx - s * 0.8f, cy + s * 0.78f); close() }
        p.style = Paint.Style.STROKE
        p.strokeJoin = Paint.Join.ROUND
        for ((w, a) in listOf(0.2f to 50, 0.12f to 90, 0.06f to 200)) {
            p.color = withAlpha(glow, a)
            p.strokeWidth = len * w
            if (w > 0.1f) p.maskFilter = BlurMaskFilter(len * w * 0.6f, BlurMaskFilter.Blur.NORMAL) else p.maskFilter = null
            c.drawPath(path, p)
        }
        p.maskFilter = null
        p.style = Paint.Style.FILL
        p.color = withAlpha(glow, 70)
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.color = Color.WHITE
        p.strokeWidth = len * 0.025f
        c.drawPath(path, p)
    }

    // ------------------------------------------------------------------ colour helpers

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    /** Lighter (+) or darker (-) version of [c]; amount is -1..1. */
    private fun shade(c: Int, amount: Float): Int {
        fun ch(v: Int) = if (amount >= 0) (v + (255 - v) * amount).toInt() else (v * (1 + amount)).toInt()
        return Color.argb(Color.alpha(c), ch(Color.red(c)).coerceIn(0, 255), ch(Color.green(c)).coerceIn(0, 255), ch(Color.blue(c)).coerceIn(0, 255))
    }

    @Suppress("unused")
    private fun rr(l: Float, t: Float, r: Float, b: Float) = RectF(l, t, r, b)
}

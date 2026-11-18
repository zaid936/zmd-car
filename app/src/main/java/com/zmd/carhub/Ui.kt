package com.zmd.carhub

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/**
 * The look of the app, in the spirit of Apple's "Liquid Glass": clear, white-tinted glass with a
 * bright rim and a soft sheen, over a deep, colourful background (or over the map).
 */
object Ui {
    const val BLUE = 0xFF1F5AA8.toInt()
    const val MID = 0xFF1B6E90.toInt()
    const val TEAL = 0xFF177F74.toInt()
    const val INK = 0xFF0C2A45.toInt()          // dark text on a white surface
    const val WHITE = 0xFFFFFFFF.toInt()
    const val SUB = 0xCCFFFFFF.toInt()          // secondary text: 80% white
    const val GLASS = 0x1FFFFFFF                // card fill: 12% white
    const val GLASS_HI = 0x38FFFFFF             // raised fill: 22% white
    const val STROKE = 0x4DFFFFFF               // rim: 30% white
    /** Accent of the chosen theme (buttons, selection, the route...). */
    val ACCENT: Int get() = active.accent
    const val OK = 0xFF8DF5CB.toInt()           // mint, readable on the gradient
    const val BAD = 0xFFFFC2B8.toInt()          // soft red, readable on the gradient
    val CARPLAY = intArrayOf(0xFF26CF93.toInt(), 0xFF119C8C.toInt())
    val AUTO = intArrayOf(0xFF5A99FF.toInt(), 0xFF2A63D6.toInt())

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()
    fun dp(c: Context, v: Int): Int = dp(c, v.toFloat())

    // ---------------------------------------------------------------- themes

    /** A glow of light on the background: colour, centre (0..1 of the screen) and size. */
    class Glow(val color: Int, val x: Float, val y: Float, val r: Float)

    /**
     * One look for the whole app: background, its lights, the accent, and the glass used on top of
     * the map and other apps ([floatTop]/[floatBottom], [solidTop]/[solidBottom] when it must be
     * readable), with how bright the sheen and the rim are.
     */
    class Theme(
        val id: String, val name: String, val base: IntArray, val glows: List<Glow>, val accent: Int,
        val floatTop: Int, val floatBottom: Int, val solidTop: Int, val solidBottom: Int,
        val sheen: Int = 0x33, val rim: Int = 0x66, val card: Int = 0x1FFFFFFF, val blur: Int = 24,
    )

    val themes = listOf(
        Theme("night", "أزرق ليلي", intArrayOf(0xFF0B1230.toInt(), 0xFF111A44.toInt(), 0xFF0A2236.toInt()),
            listOf(Glow(0x997B3FE4.toInt(), 0.12f, 0.05f, 0.55f), Glow(0x8012C2E9.toInt(), 0.95f, 0.95f, 0.6f), Glow(0x55F64F59, 0.85f, 0.1f, 0.35f)),
            0xFF0A84FF.toInt(), 0x99262B38.toInt(), 0xA6161A24.toInt(), 0xE6262B38.toInt(), 0xEB161A24.toInt()),
        Theme("liquid", "زجاج سائل", intArrayOf(0xFF0E1A3A.toInt(), 0xFF1B1446.toInt(), 0xFF0B2A3E.toInt()),
            listOf(Glow(0xAA4F8BFF.toInt(), 0.1f, 0.1f, 0.6f), Glow(0x99FF5FA2.toInt(), 0.9f, 0.15f, 0.45f),
                Glow(0x8840E0D0.toInt(), 0.85f, 0.95f, 0.55f), Glow(0x55FFC857, 0.3f, 0.85f, 0.35f)),
            0xFF4FA3FF.toInt(), 0x6B3A4256, 0x80202636.toInt(), 0xE0343C50.toInt(), 0xEA1C2232.toInt(),
            sheen = 0x5C, rim = 0xB3, card = 0x2EFFFFFF, blur = 40),
        Theme("black", "أسود فخم", intArrayOf(0xFF050505.toInt(), 0xFF0B0B0D.toInt(), 0xFF101012.toInt()),
            listOf(Glow(0x40D4A84B, 0.9f, 0.05f, 0.45f), Glow(0x26FFFFFF, 0.1f, 0.95f, 0.5f)),
            0xFFD4A84B.toInt(), 0xB3101012.toInt(), 0xC0060607.toInt(), 0xF0141416.toInt(), 0xF5080809.toInt(), sheen = 0x22, rim = 0x55),
        Theme("byd", "BYD أحمر", intArrayOf(0xFF140608.toInt(), 0xFF1E0A0E.toInt(), 0xFF0E0F16.toInt()),
            listOf(Glow(0x99D71920.toInt(), 0.85f, 0.08f, 0.55f), Glow(0x55FF6B3D, 0.1f, 0.9f, 0.45f)),
            0xFFE5313A.toInt(), 0x9E2E1418.toInt(), 0xAD1A0B0E.toInt(), 0xE62E1418.toInt(), 0xEB1A0B0E.toInt()),
        Theme("forest", "غابة", intArrayOf(0xFF061612.toInt(), 0xFF0A211C.toInt(), 0xFF0B1A22.toInt()),
            listOf(Glow(0x8817B890.toInt(), 0.15f, 0.1f, 0.55f), Glow(0x663DD9C1, 0.9f, 0.9f, 0.5f)),
            0xFF2BD49C.toInt(), 0x9E14302A.toInt(), 0xAD0A1C18.toInt(), 0xE614302A.toInt(), 0xEB0A1C18.toInt()),
        Theme("sunset", "غروب", intArrayOf(0xFF1A0B26.toInt(), 0xFF2A0F2E.toInt(), 0xFF1A1030.toInt()),
            listOf(Glow(0x99FF7A2F.toInt(), 0.9f, 0.85f, 0.55f), Glow(0x88C13BFF.toInt(), 0.1f, 0.1f, 0.5f), Glow(0x55FFD24A, 0.5f, 0.5f, 0.3f)),
            0xFFFF8A3D.toInt(), 0x9E2E1636.toInt(), 0xAD1A0E24.toInt(), 0xE62E1636.toInt(), 0xEB1A0E24.toInt()),
        Theme("graphite", "رمادي", intArrayOf(0xFF16181C.toInt(), 0xFF1D2026.toInt(), 0xFF22262E.toInt()),
            listOf(Glow(0x33FFFFFF, 0.85f, 0.1f, 0.5f)),
            0xFF8FA3BF.toInt(), 0xA62A2D33.toInt(), 0xB31C1E22.toInt(), 0xEB2A2D33.toInt(), 0xF01C1E22.toInt(), sheen = 0x26, rim = 0x4D),
    )

    /** The theme in use, cached for code that has no Context at hand (e.g. [ACCENT]). */
    @Volatile var active: Theme = themes[0]
        private set

    /** The theme the user picked (Settings → المظهر). */
    fun theme(c: Context): Theme {
        val id = c.getSharedPreferences("hub", Context.MODE_PRIVATE).getString("ui_theme", null)
        return (themes.firstOrNull { it.id == id } ?: themes[0]).also { active = it }
    }

    fun setTheme(c: Context, id: String) {
        c.getSharedPreferences("hub", Context.MODE_PRIVATE).edit().putString("ui_theme", id).apply()
        theme(c)
    }

    /** The background of the app's screens in [t] (default: the picked theme). */
    fun appBackground(c: Context, t: Theme = theme(c)): Drawable {
        val dm = c.resources.displayMetrics
        val big = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
        fun blob(g: Glow) = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(g.color, g.color and 0x00FFFFFF)
            setGradientCenter(g.x, g.y)
            gradientRadius = big * g.r
        }
        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, t.base)
        return LayerDrawable((listOf<Drawable>(base) + t.glows.map { blob(it) }).toTypedArray())
    }

    /** The sheen across the top half of a glass surface. */
    private fun shine(radius: Float, alpha: Int = active.sheen): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf((alpha shl 24) or 0xFFFFFF, ((alpha / 5) shl 24) or 0xFFFFFF, 0x00FFFFFF)).apply {
            cornerRadius = radius
        }

    /** Liquid-glass light along the bottom edge (light bending through the glass). */
    private fun underGlow(radius: Float, alpha: Int): Drawable =
        GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf((alpha shl 24) or 0xFFFFFF, 0x00FFFFFF, 0x00FFFFFF)).apply {
            cornerRadius = radius
        }

    /** A thin bright rim, lighter on top like light catching the edge of glass. */
    private fun rim(c: Context, radius: Float, alpha: Int): Drawable =
        GradientDrawable().apply {
            setColor(0)
            cornerRadius = radius
            setStroke(dp(c, 1.2f), (alpha shl 24) or 0xFFFFFF)
        }

    /** Glass card for screens that already sit on the gradient background. */
    fun glass(c: Context, radiusDp: Float, fill: Int = GLASS): Drawable {
        val t = theme(c)
        val r = dp(c, radiusDp).toFloat()
        if (fill == GLASS) {
            val base = GradientDrawable().apply { setColor(t.card); cornerRadius = r }
            val layers = mutableListOf(base, shine(r, t.sheen), rim(c, r, t.rim))
            if (t.id == "liquid") layers.add(1, underGlow(r, 0x26))
            return LayerDrawable(layers.toTypedArray())
        }
        val base = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = r
        }
        return LayerDrawable(arrayOf(base, shine(r), rim(c, r, 0x4D)))
    }

    /**
     * Glass for windows that float over other apps and over the map (the bar, panels, sheets).
     * Overlay windows can't blur what is behind them, so the glass is a smoky, see-through grey
     * that stays readable on a bright or dark map, with the same sheen and bright rim.
     */
    fun floatingGlass(c: Context, radiusDp: Float, opaque: Boolean = false): Drawable {
        val t = theme(c)
        val r = dp(c, radiusDp).toFloat()
        val tint = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (opaque) intArrayOf(t.solidTop, t.solidBottom) else intArrayOf(t.floatTop, t.floatBottom)
        ).apply { cornerRadius = r }
        val layers = mutableListOf(tint, shine(r, t.sheen), rim(c, r, t.rim))
        if (t.id == "liquid") layers.add(2, underGlow(r, 0x2E))
        return LayerDrawable(layers.toTypedArray())
    }

    /** Solid coloured tile for a connected icon. */
    fun tile(c: Context, colors: IntArray, radiusDp: Float): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(c, radiusDp).toFloat()
            setStroke(dp(c, 1f), 0x99FFFFFF.toInt())
        }

    fun solid(c: Context, color: Int, radiusDp: Float): Drawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(c, radiusDp).toFloat()
        }

    /** Touch feedback on top of [content] (which may be null for a plain row). */
    fun ripple(c: Context, content: Drawable?, radiusDp: Float): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), content, solid(c, WHITE, radiusDp))

    fun label(c: Context, text: CharSequence, sp: Float, color: Int = WHITE, bold: Boolean = false): TextView =
        TextView(c).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }

    /** Glass pill button. primary = white pill with dark text, for the one main action on a screen. */
    fun button(c: Context, text: CharSequence, primary: Boolean = false, onClick: () -> Unit): TextView =
        label(c, text, 16f, if (primary) INK else WHITE, true).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            val content = if (primary) solid(c, 0xF2FFFFFF.toInt(), 22f) else glass(c, 22f, GLASS_HI)
            background = ripple(c, content, 22f)
            setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12))
            minHeight = dp(c, 48)
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** Three-way (or n-way) switch. Keeps its own highlight in sync when tapped. */
    fun segmented(c: Context, options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val box = hbox(c).apply {
            background = glass(c, 22f)
            val p = dp(c, 4)
            setPadding(p, p, p, p)
        }
        val cells = ArrayList<TextView>()
        fun paint(sel: Int) {
            cells.forEachIndexed { i, t ->
                t.background = if (i == sel) solid(c, 0xF2FFFFFF.toInt(), 18f) else null
                t.setTextColor(if (i == sel) INK else WHITE)
            }
        }
        options.forEachIndexed { i, s ->
            val t = label(c, s, 15f, WHITE, true).apply {
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10))
                minHeight = dp(c, 44)
                isClickable = true
                setOnClickListener {
                    paint(i)
                    onSelect(i)
                }
            }
            cells += t
            box.addView(t, lp(0, WRAP, 1f))
        }
        paint(selected)
        return box
    }

    fun vbox(c: Context): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    fun hbox(c: Context): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }

    fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight)

    /** Size and style an Activity that uses the HubDialog theme as a floating glass sheet. */
    fun dialogWindow(a: Activity, widthDp: Int, heightFraction: Float) {
        val dm = a.resources.displayMetrics
        val w = min(dp(a, widthDp), (dm.widthPixels * 0.94f).toInt())
        val h = (dm.heightPixels * heightFraction).toInt()
        a.window.setLayout(w, h)
        a.window.setBackgroundDrawable(floatingGlass(a, 30f, opaque = true))
        a.window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        if (Build.VERSION.SDK_INT >= 31) {
            // Real frosted blur behind the sheet where the screen supports it; ignored elsewhere.
            runCatching { a.window.setBackgroundBlurRadius(dp(a, theme(a).blur)) }
        }
    }
}

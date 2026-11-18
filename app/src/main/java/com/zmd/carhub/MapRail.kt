package com.zmd.carhub

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The side bar on the map: at the top the time, date, weather (and a place for the car's battery,
 * which needs an OBD-II Bluetooth adapter: this screen has no access to the car's data); under it
 * the user's favourite apps, one tap to open, long-press to remove, «+» to add.
 */
class MapRail(
    private val ctx: Context,
    private val host: android.widget.FrameLayout,
    private val onAdd: () -> Unit,
    private val onBattery: () -> Unit,
    /** Shortcuts shown at the top of the apps window: (icon, title, action). */
    private val shortcuts: List<Triple<String, String, () -> Unit>> = emptyList(),
    /** Told when the apps window opens (true) or has closed (false). */
    private val onDrawer: (Boolean) -> Unit = {},
) {
    private val sp = ctx.getSharedPreferences("map", Context.MODE_PRIVATE)
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private fun dp(v: Int) = Ui.dp(ctx, v)

    val view: LinearLayout = Ui.vbox(ctx).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        background = Ui.floatingGlass(ctx, 26f, opaque = true)
        setPadding(dp(6), dp(10), dp(6), dp(10))
    }
    private val time = Ui.label(ctx, "", 26f, bold = true).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER }
    private val date = Ui.label(ctx, "", 12f, Ui.SUB).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER }
    private val weather = Ui.label(ctx, "", 15f).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER }
    private val battery = Ui.label(ctx, "🔋 —", 14f, Ui.SUB).apply {
        gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
        setOnClickListener { onBattery() }
    }
    private val apps = Ui.vbox(ctx).apply { gravity = Gravity.CENTER_HORIZONTAL }

    var shown: Boolean
        get() = sp.getBoolean("rail_shown", true)
        set(v) { sp.edit().putBoolean("rail_shown", v).apply() }

    init {
        view.addView(time, Ui.lp(Ui.MATCH, Ui.WRAP))
        view.addView(date, Ui.lp(Ui.MATCH, Ui.WRAP))
        view.addView(weather, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6) })
        view.addView(battery, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(4) })
        view.addView(View(ctx).apply { setBackgroundColor(0x33FFFFFF) }, Ui.lp(dp(56), dp(1)).apply { topMargin = dp(10); bottomMargin = dp(8) })
        view.addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(apps) }, Ui.lp(Ui.MATCH, 0, 1f))
        // Fixed at the bottom: every installed app, in a window that grows out to the right.
        view.addView(ImageView(ctx).apply {
            setImageBitmap(gridIcon())
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "كل البرامج"
            // The theme's glass, like the bar around it: follows every theme (and liquid glass).
            background = Ui.ripple(ctx, Ui.glass(ctx, 20f), 20f)
            elevation = dp(2).toFloat()
            isClickable = true
            setOnClickListener { toggleDrawer() }
        }, Ui.lp(dp(64), dp(64)).apply { topMargin = dp(8) })
        paintApps()
    }

    // ---------------------------------------------------------------- clock

    private val tick = object : Runnable {
        override fun run() {
            val cal = Calendar.getInstance()
            time.text = String.format(Locale.US, "%d:%02d", (cal.get(Calendar.HOUR) + 11) % 12 + 1, cal.get(Calendar.MINUTE))
            val days = arrayOf("الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت")
            date.text = "${days[cal.get(Calendar.DAY_OF_WEEK) - 1]} ${cal.get(Calendar.DAY_OF_MONTH)}"
            ui.postDelayed(this, 15_000)
        }
    }

    init { tick.run() }

    // ---------------------------------------------------------------- weather (Open-Meteo, no key)

    private var weatherAt = 0L

    /** Called with the car's position; refreshes the weather every 30 minutes when online. */
    fun place(lat: Double, lon: Double, online: Boolean) {
        val now = System.currentTimeMillis()
        if (!online || now - weatherAt < 30 * 60_000) return
        weatherAt = now
        io.execute {
            val text = runCatching {
                val url = URL(String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code", lat, lon))
                val con = url.openConnection() as HttpURLConnection
                con.connectTimeout = 8000; con.readTimeout = 8000
                val body = con.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                con.disconnect()
                val cur = JSONObject(body).getJSONObject("current")
                val t = Math.round(cur.getDouble("temperature_2m"))
                "${icon(cur.optInt("weather_code"))} $t°"
            }.getOrNull()
            if (text != null) ui.post { weather.text = text }
            else weatherAt = 0
        }
    }

    private fun icon(code: Int): String = when (code) {
        0 -> "☀️"
        1, 2 -> "🌤"
        3 -> "☁️"
        45, 48 -> "🌫"
        in 51..67, in 80..82 -> "🌧"
        in 71..77, 85, 86 -> "❄️"
        in 95..99 -> "⛈"
        else -> "🌡"
    }

    // ---------------------------------------------------------------- favourite apps

    /** CarPlay / Android Auto receivers never go on the side bar: they open by themselves when a phone connects. */
    fun isProjection(pkg: String): Boolean {
        val prefs = Prefs(ctx)
        // Android Auto is built in (autoPackage == our package): our own package is the map, not a receiver.
        return pkg == prefs.carplayPackage || (pkg == prefs.autoPackage && pkg != ctx.packageName) || pkg in PROJECTION
    }

    fun favourites(): List<String> {
        val saved = sp.getString("rail_apps", null)
        val list = saved?.split(',')?.filter { it.isNotBlank() }
            ?: listOf("com.google.android.youtube", "com.spotify.music", "com.waze", "com.google.android.apps.maps")
                .filter { Apps.isInstalled(ctx, it) }
        return list.filter { !isProjection(it) }
    }

    companion object {
        // Not Prefs.DEFAULT_AUTO: that is our own package now (Android Auto built in).
        val PROJECTION = setOf(Prefs.DEFAULT_CARPLAY, Prefs.SEPARATE_AUTO, Prefs.OLD_AUTO)
    }

    private fun save(list: List<String>) = sp.edit().putString("rail_apps", list.joinToString(",")).apply()

    fun add(pkg: String) {
        val l = favourites().toMutableList()
        if (pkg !in l) { l += pkg; save(l) }
        paintApps()
    }

    fun paintApps() {
        apps.removeAllViews()
        val list = favourites()
        save(list)
        for (pkg in list) {
            val icon = Apps.icon(ctx, pkg) ?: continue
            val cell = Ui.vbox(ctx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(6), dp(4), dp(6))
                background = Ui.ripple(ctx, null, 16f)
                isClickable = true
                setOnClickListener { if (Perms.freeformOn(ctx)) openBesideMap(ctx, pkg) else Apps.launch(ctx, pkg) }
                setOnLongClickListener {
                    save(favourites().filter { it != pkg })
                    paintApps()
                    android.widget.Toast.makeText(ctx, "انشال من الشريط", android.widget.Toast.LENGTH_SHORT).show()
                    true
                }
            }
            cell.addView(ImageView(ctx).apply { setImageDrawable(icon) }, Ui.lp(dp(48), dp(48)))
            cell.addView(Ui.label(ctx, Apps.label(ctx, pkg), 11f, Ui.SUB).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
            }, Ui.lp(dp(76), Ui.WRAP))
            apps.addView(cell, Ui.lp(Ui.WRAP, Ui.WRAP).apply { bottomMargin = dp(2) })
        }
        // Apps are added from the apps window (long-press), so the bar has no "+" of its own.
        if (list.isEmpty()) apps.addView(Ui.label(ctx, "دوس مطوّل على برنامج بالقائمة حتى يطلع هنا", 11f, Ui.SUB).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
        }, Ui.lp(dp(80), Ui.WRAP))
    }

    /**
     * The "all apps" button's picture: nine soft dots in the theme's accent (a lighter top-left
     * to a deeper bottom-right, like light falling on glass), over a faint accent glow. It sits on
     * the theme's own glass tile, so it changes with the theme like the rest of the bar.
     */
    private fun gridIcon(): android.graphics.Bitmap {
        val s = dp(56)
        val b = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val acc = Ui.ACCENT
        p.shader = android.graphics.RadialGradient(s / 2f, s / 2f, s * 0.48f,
            intArrayOf((acc and 0x00FFFFFF) or 0x55000000, (acc and 0x00FFFFFF) or 0x18000000, 0),
            floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
        c.drawCircle(s / 2f, s / 2f, s * 0.48f, p)
        val r = s * 0.075f
        val gap = s * 0.235f
        val o = s / 2f - gap
        for (i in 0..2) for (j in 0..2) {
            val cx = o + j * gap
            val cy = o + i * gap
            p.shader = android.graphics.LinearGradient(cx - r, cy - r, cx + r, cy + r,
                lighter(acc), if ((i + j) % 2 == 0) acc else lighter(acc), android.graphics.Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p)
        }
        p.shader = null
        return b
    }

    private fun lighter(c: Int) = android.graphics.Color.rgb(
        (android.graphics.Color.red(c) + 255) / 2, (android.graphics.Color.green(c) + 255) / 2, (android.graphics.Color.blue(c) + 255) / 2)

    private fun darker(c: Int) = android.graphics.Color.rgb(
        android.graphics.Color.red(c) * 6 / 10, android.graphics.Color.green(c) * 6 / 10, android.graphics.Color.blue(c) * 6 / 10)

    // ---------------------------------------------------------------- all apps drawer

    private var drawer: View? = null

    fun toggleDrawer() { if (drawer != null) closeDrawer() else openDrawer() }

    private fun openDrawer() {
        onDrawer(true)
        val panel = Ui.vbox(ctx).apply {
            background = Ui.floatingGlass(ctx, 26f, opaque = true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            isClickable = true
        }
        val head = Ui.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.label(ctx, "كل البرامج", 20f, bold = true), Ui.lp(0, Ui.WRAP, 1f))
        head.addView(Ui.button(ctx, "سدّ") { closeDrawer() })
        panel.addView(head)
        panel.addView(Ui.label(ctx, "دوسة تفتح البرنامج · دوسة مطوّلة تضيفه للشريط", 13f, Ui.SUB),
            Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(8) })
        // Our own shortcuts first: store, settings, map settings, offline maps.
        if (shortcuts.isNotEmpty()) {
            val row = Ui.hbox(ctx).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
            for ((icon, title, go) in shortcuts) {
                val cell = Ui.vbox(ctx).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    background = Ui.ripple(ctx, Ui.glass(ctx, 18f), 18f)
                    isClickable = true
                    setOnClickListener { closeDrawer(); go() }
                }
                cell.addView(Ui.label(ctx, icon, 30f).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER })
                cell.addView(Ui.label(ctx, title, 13f, bold = true).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER })
                row.addView(cell, Ui.lp(0, Ui.WRAP, 1f).apply { marginEnd = dp(8) })
            }
            panel.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(10) })
        }
        val grid = Ui.vbox(ctx)
        val all = Apps.launchable(ctx).filter { !isProjection(it.pkg) }
        val cols = 5
        for (row in all.chunked(cols)) {
            val line = Ui.hbox(ctx).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
            for (a in row) {
                val cell = Ui.vbox(ctx).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    background = Ui.ripple(ctx, null, 16f)
                    isClickable = true
                    setOnClickListener { closeDrawer(); if (Perms.freeformOn(ctx)) openBesideMap(ctx, a.pkg) else Apps.launch(ctx, a.pkg) }
                    setOnLongClickListener {
                        add(a.pkg)
                        android.widget.Toast.makeText(ctx, "انضاف ${a.label} للشريط", android.widget.Toast.LENGTH_SHORT).show()
                        true
                    }
                }
                cell.addView(ImageView(ctx).apply { setImageDrawable(a.icon) }, Ui.lp(dp(56), dp(56)))
                cell.addView(Ui.label(ctx, a.label, 12f).apply {
                    maxLines = 2; gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
                })
                line.addView(cell, Ui.lp(0, Ui.WRAP, 1f))
            }
            repeat(cols - row.size) { line.addView(View(ctx), Ui.lp(0, 1, 1f)) }
            grid.addView(line)
        }
        panel.addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(grid) }, Ui.lp(Ui.MATCH, 0, 1f))

        // Grows out of the side bar towards the right.
        val railLp = view.layoutParams as? android.widget.FrameLayout.LayoutParams
        val left = (railLp?.leftMargin ?: 0) + view.width + dp(8)
        // The host may still be growing (the floating bar's window): fall back to the screen width.
        val hw = if (host.width > view.width * 3) host.width else ctx.resources.displayMetrics.widthPixels
        val w = minOf(dp(620), hw - left - dp(110))
        host.addView(panel, android.widget.FrameLayout.LayoutParams(w, Ui.MATCH, Gravity.TOP or Gravity.LEFT).apply {
            setMargins(left, railLp?.topMargin ?: dp(18), 0, railLp?.bottomMargin ?: dp(18))
        })
        panel.pivotX = 0f
        panel.scaleX = 0.15f
        panel.alpha = 0f
        panel.translationX = -dp(40).toFloat()
        panel.animate().scaleX(1f).alpha(1f).translationX(0f).setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        drawer = panel
    }

    fun closeDrawer() {
        val d = drawer ?: return
        drawer = null
        d.animate().scaleX(0.15f).alpha(0f).translationX(-dp(40).toFloat()).setDuration(200)
            .withEndAction { host.removeView(d); if (drawer == null) onDrawer(false) }.start()
    }

    fun release() {
        drawer?.let { host.removeView(it) }
        drawer = null
        ui.removeCallbacks(tick)
        io.shutdownNow()
    }
}

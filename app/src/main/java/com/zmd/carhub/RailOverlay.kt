package com.zmd.carhub

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.location.LocationManager
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast

/**
 * The side bar (clock, weather, favourite apps, all-apps window) floating over the maps app the
 * user chose instead of ours (Waze, Google Maps...), so that map feels part of ZMD Car. Android
 * gives normal apps no way to run another app inside their own screen, so the bar is a window
 * on top of it, like the hub's top bar.
 */
class RailOverlay(private val ctx: Context) {
    companion object {
        /** Where the side bar floats over other apps: "all" (default), "map" (the chosen maps app only), "off". */
        fun mode(ctx: Context): String =
            ctx.getSharedPreferences("map", Context.MODE_PRIVATE).getString("rail_over", "all") ?: "all"

        fun setMode(ctx: Context, m: String) =
            ctx.getSharedPreferences("map", Context.MODE_PRIVATE).edit().putString("rail_over", m).apply()

        fun wanted(ctx: Context, pkg: String, mapPkg: String?): Boolean = when (mode(ctx)) {
            "all" -> true
            "map" -> pkg == mapPkg
            else -> false
        }
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val host = FrameLayout(ctx)
    // Same place and size as the bar on our own map, so it lines up with the map behind it.
    private val edge = Ui.dp(ctx, 18)
    private val railW = Ui.dp(ctx, 92)
    private var rail: MapRail? = null
    private var lp: WindowManager.LayoutParams? = null
    private var themeId: String? = null
    val showing get() = lp != null

    init {
        // A tap beside the open apps window closes it.
        host.setOnClickListener { rail?.closeDrawer() }
    }

    private fun build(): MapRail {
        host.removeAllViews()
        rail?.release()
        themeId = Ui.theme(ctx).id
        val r = MapRail(ctx, host, onAdd = { rail?.toggleDrawer() }, onBattery = {
            Toast.makeText(ctx, "شحن السيارة يحتاج قطعة OBD بلوتوث (ELM327)", Toast.LENGTH_LONG).show()
        }, shortcuts = listOf(
            Triple("🛍", "المتجر") { open(Intent(ctx, StoreActivity::class.java)) },
            Triple("⚙", "الإعدادات") { open(Intent(ctx, MainActivity::class.java)) },
            Triple("🗺", "خريطة ZMD") { open(Intent(ctx, MapActivity::class.java).putExtra(EXTRA_FROM_HOME, true)) },
        ), onDrawer = { open -> widen(open) })
        host.addView(r.view, FrameLayout.LayoutParams(railW, Ui.MATCH, Gravity.TOP or Gravity.LEFT).apply {
            setMargins(edge, edge, 0, edge)
        })
        rail = r
        return r
    }

    private fun open(i: Intent) {
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    @SuppressLint("MissingPermission")
    private fun placeWeather(r: MapRail) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val loc = runCatching {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        }.getOrNull() ?: return
        r.place(loc.latitude, loc.longitude, true)
    }

    /** Width the bar takes from the left of the screen. */
    val width get() = barWidth(ctx)

    private var lastPlaced = 0L

    /**
     * With resizable windows on, the maps app [pkg] is moved to the space right of the bar so
     * nothing of it is covered. Without them it stays full screen under the bar.
     */
    fun makeRoom(pkg: String) {
        if (!Perms.freeformOn(ctx) || lp == null) return
        val left = HubA11yService.appWindowLeft(pkg)
        if (left != null && left >= width - Ui.dp(ctx, 4)) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPlaced < 4000) return
        lastPlaced = now
        // Our map goes behind first, so the strip around the bar is map, not black.
        runCatching {
            ctx.startActivity(Intent(ctx, MapActivity::class.java).putExtra(EXTRA_FROM_HOME, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ launchBeside(ctx, pkg, width) }, 250)
    }

    fun show() {
        if (!android.provider.Settings.canDrawOverlays(ctx)) return
        if (lp != null) return
        val r = if (rail == null || themeId != Ui.theme(ctx).id) build() else rail!!
        if (!r.shown) return
        r.paintApps()
        placeWeather(r)
        val p = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            // Stay between the status bar and the bottom bar: never cover the screen's own bars.
            fitInsetsTypes = android.view.WindowInsets.Type.systemBars()
        }
        if (runCatching { wm.addView(host, p) }.isSuccess) {
            lp = p
            // Our map's own bar (behind) would show twice: this one takes its place.
            RailHost.mapRail?.let { if (it.visibility == android.view.View.VISIBLE) { it.visibility = android.view.View.INVISIBLE; hidMapRail = true } }
        }
    }

    private var hidMapRail = false

    fun hide() {
        rail?.closeDrawer()
        if (lp != null) runCatching { wm.removeViewImmediate(host) }
        lp = null
        if (hidMapRail) { RailHost.mapRail?.visibility = android.view.View.VISIBLE; hidMapRail = false }
    }

    /** The window covers only the bar, and the whole screen while the apps window is open. */
    private fun widen(open: Boolean) {
        val p = lp ?: return
        p.width = if (open) WindowManager.LayoutParams.MATCH_PARENT else width
        runCatching { wm.updateViewLayout(host, p) }
    }

    fun release() {
        hide()
        rail?.release()
        rail = null
    }
}

/**
 * Opens [pkg] as a window filling the screen right of the side bar ([barWidth] wide), when the
 * screen allows resizable windows. Returns false if it couldn't.
 */
fun launchBeside(ctx: Context, pkg: String, barWidth: Int): Boolean {
    if (!Perms.freeformOn(ctx)) return false
    val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
    // A fresh window: Android only applies the size when the app's window is created, not when an
    // already open one is brought back.
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val full = wm.maximumWindowMetrics.bounds
    val insets = wm.maximumWindowMetrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
    val r = android.graphics.Rect(full.left + barWidth, full.top + insets.top, full.right, full.bottom - insets.bottom)
    val o = android.app.ActivityOptions.makeBasic().setLaunchBounds(r)
    // The "free window" mode itself (hidden setter; bounds alone are enough on most screens).
    runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 28) org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityOptions;")
        android.app.ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(o, 5)
    }
    return runCatching { ctx.startActivity(i, o.toBundle()); true }.getOrDefault(false)
}

/** Width from the screen's left edge that the side bar takes (our map's bar: 18 + 92 + 8 dp). */
fun barWidth(ctx: Context): Int = Ui.dp(ctx, 18) + Ui.dp(ctx, 92) + Ui.dp(ctx, 8)

/** Our map's own side bar, so the floating one can stand in for it. */
object RailHost {
    var mapRail: android.view.View? = null
}

/** Opens [pkg]: beside the side bar when windows can be resized, else as usual. */
fun launchApp(ctx: Context, pkg: String): Boolean =
    (Perms.freeformOn(ctx) && launchBeside(ctx, pkg, barWidth(ctx))) || Apps.launch(ctx, pkg)

/** Our map full screen behind, then [pkg] beside the side bar: the strip under the bar is map. */
fun openBesideMap(ctx: Context, pkg: String) {
    runCatching {
        ctx.startActivity(Intent(ctx, MapActivity::class.java).setAction(Intent.ACTION_MAIN).putExtra(EXTRA_FROM_HOME, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ launchApp(ctx, pkg) }, 250)
}

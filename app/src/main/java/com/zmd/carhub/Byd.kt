package com.zmd.carhub

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * BYD DiLink screens only (everything here does nothing elsewhere):
 * - [battery]: the car's battery % and electric range, read through BYD's own vehicle SDK
 *   (android.hardware.bydauto, present only on BYD screens), for the floating bar.
 * - [navUpdate] / [navStop]: our own map's turn-by-turn on the instrument cluster / HUD, through the
 *   Android Auto engine's BYD outputs (ZmdHubNavigation in the ":auto" process).
 *
 * BYD documents none of this publicly, so the vehicle SDK is reached by reflection and every call
 * may fail on a given model or firmware: then nothing is shown. [report] lists what this screen
 * offers, for the «دعم فني» report, so the names can be fixed from a real car's report.
 */
object Byd {
    private const val TAG = "ZmdByd"
    private const val SDK = "android.hardware.bydauto"
    private const val INSTRUMENT = "$SDK.instrument.BYDAutoInstrumentDevice"

    /** Candidate (class, battery % method, range km method) in BYD's vehicle SDK. */
    private val BATTERY_APIS = listOf(
        Triple("$SDK.statistic.BYDAutoStatisticDevice", "getElecPercentageValue", "getEVMileageValue"),
        Triple("$SDK.energy.BYDAutoEnergyDevice", "getElecPercentageValue", "getEVMileageValue"),
        Triple("$SDK.statistic.BYDAutoStatisticDevice", "getSOC", "getElecDrivingRangeValue"),
    )

    @Volatile private var isBydCache: Boolean? = null

    /** This is a BYD screen with BYD's vehicle SDK. */
    fun isByd(ctx: Context): Boolean = isBydCache ?: run {
        val sdk = runCatching { Class.forName(INSTRUMENT) }.isSuccess
        val brand = "${Build.MANUFACTURER} ${Build.BRAND}".contains("byd", ignoreCase = true)
        (sdk || brand).also { isBydCache = it }
    }

    /** Battery % and electric range in km (null when the car doesn't tell, or this isn't a BYD). */
    class Battery(val percent: Int, val rangeKm: Int?)

    fun battery(ctx: Context): Battery? {
        if (!isByd(ctx)) return null
        for ((cls, pctName, rangeName) in BATTERY_APIS) {
            val device = device(ctx, cls) ?: continue
            val pct = number(device, pctName)?.toInt()?.takeIf { it in 0..100 } ?: continue
            val range = number(device, rangeName)?.toInt()?.takeIf { it in 0..1500 }
            return Battery(pct, range)
        }
        return null
    }

    private fun device(ctx: Context, cls: String): Any? = runCatching {
        Class.forName(cls).getMethod("getInstance", Context::class.java).invoke(null, ctx.applicationContext)
    }.getOrNull()

    private fun number(obj: Any, method: String): Double? = runCatching {
        (obj.javaClass.getMethod(method).invoke(obj) as? Number)?.toDouble()
    }.getOrNull()

    // ------------------------------------------------------------ cluster navigation

    private var lastNavAt = 0L
    private var navSent = false

    /** Our map's next manoeuvre to the BYD cluster (at most once a second). */
    fun navUpdate(ctx: Context, nav: NavEngine) {
        if (!isByd(ctx)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastNavAt < 1000) return
        lastNavAt = now
        val step = nav.next
        val type = aaType(step.sign) ?: return
        val i = Intent("com.zmd.auto.NAV").setPackage(ctx.packageName)
            .putExtra("type", type)
            .putExtra("exit", step.exit)
            .putExtra("distance", nav.distToNextM.toInt())
            .putExtra("road", step.street)
            .putExtra("remaining", nav.remainingM.toInt())
            .putExtra("seconds", (nav.remainingMs / 1000).toInt())
        runCatching { ctx.sendBroadcast(i, ZmdAuto.PERMISSION) }
        navSent = true
    }

    /** The route is over: clear the cluster. */
    fun navStop(ctx: Context) {
        if (!navSent) return
        navSent = false
        runCatching { ctx.sendBroadcast(Intent("com.zmd.auto.NAV_STOP").setPackage(ctx.packageName), ZmdAuto.PERMISSION) }
    }

    /** GraphHopper's turn sign as Android Auto's Maneuver type (what the engine's BYD mapper reads). */
    private fun aaType(sign: Int): Int? = when (sign) {
        MapRoute.U_TURN, MapRoute.U_TURN_LEFT -> 11
        MapRoute.U_TURN_RIGHT -> 12
        MapRoute.KEEP_LEFT -> 3
        MapRoute.KEEP_RIGHT -> 4
        MapRoute.SHARP_LEFT -> 9
        MapRoute.SHARP_RIGHT -> 10
        MapRoute.LEFT -> 7
        MapRoute.RIGHT -> 8
        MapRoute.SLIGHT_LEFT -> 5
        MapRoute.SLIGHT_RIGHT -> 6
        MapRoute.STRAIGHT -> 36
        MapRoute.FINISH, MapRoute.VIA -> 39
        MapRoute.ROUNDABOUT -> 34 // traffic keeps right in Iraq: counter-clockwise, with the exit number
        MapRoute.LEAVE_ROUNDABOUT -> 31
        else -> null
    }

    // ------------------------------------------------------------ auto-start page

    /** Words in the class name of an «auto-start management» screen (BYD, and other car-screen makers). */
    private val AUTOSTART = Regex("auto.?start|autorun|auto.?launch|boot.?(manage|start)|startup.?manage|selfstart", RegexOption.IGNORE_CASE)

    /** Exported screens on this device that look like an auto-start manager, best first. */
    fun autoStartScreens(ctx: Context): List<android.content.ComponentName> {
        val pm = ctx.packageManager
        val pkgs = runCatching { pm.getInstalledPackages(0).map { it.packageName } }.getOrDefault(emptyList())
            .filter { it == "com.android.settings" || it.contains("byd", true) || it.contains("settings", true) ||
                it.contains("security", true) || it.contains("permission", true) }
        val out = ArrayList<android.content.ComponentName>()
        for (p in pkgs) {
            val acts = runCatching {
                pm.getPackageInfo(p, android.content.pm.PackageManager.GET_ACTIVITIES).activities
            }.getOrNull() ?: continue
            for (a in acts) if (a.exported && AUTOSTART.containsMatchIn(a.name)) out += android.content.ComponentName(p, a.name)
        }
        // BYD's own screens first.
        return out.sortedByDescending { it.packageName.contains("byd", true) }
    }

    /**
     * Opens the screen's auto-start manager, where ZMD Car must be allowed to start by itself
     * (on BYD it's a BLOCK list: ZMD Car's switch OFF = allowed). If none is found: this app's
     * info page, else the apps list, so the driver can reach it by hand.
     */
    fun openAutoStart(ctx: Context): Boolean {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        for (c in autoStartScreens(ctx)) {
            if (runCatching { ctx.startActivity(Intent().setComponent(c).addFlags(flags)) }.isSuccess) return true
        }
        val fallbacks = listOf(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")),
            Intent(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
            Intent(android.provider.Settings.ACTION_SETTINGS),
        )
        for (i in fallbacks) if (runCatching { ctx.startActivity(i.addFlags(flags)) }.isSuccess) return false
        return false
    }

    // ------------------------------------------------------------ support report

    /** What this screen offers of BYD's vehicle SDK (classes and battery/range-looking methods). */
    fun report(ctx: Context): List<String> {
        val autoStart = "auto-start screens: " + autoStartScreens(ctx).joinToString { it.flattenToShortString() }.ifEmpty { "none found" }
        if (!isByd(ctx)) return listOf("not a BYD screen", autoStart)
        val out = ArrayList<String>()
        out += autoStart
        out += "battery read: " + (battery(ctx)?.let { "${it.percent}% ${it.rangeKm ?: "?"} km" } ?: "none")
        val classes = listOf(
            INSTRUMENT, "$SDK.statistic.BYDAutoStatisticDevice", "$SDK.energy.BYDAutoEnergyDevice",
            "$SDK.speed.BYDAutoSpeedDevice", "$SDK.gearbox.BYDAutoGearboxDevice",
        )
        for (cls in classes) {
            val c = runCatching { Class.forName(cls) }.getOrNull()
            if (c == null) { out += "$cls: missing"; continue }
            val names = runCatching { c.methods.map { it.name } }.getOrDefault(emptyList())
                .filter { n -> listOf("percent", "mileage", "soc", "range", "elec", "speed", "gear").any { n.contains(it, true) } }
                .distinct().sorted().take(25)
            out += "$cls: ${names.joinToString()}"
        }
        return out.also { Log.i(TAG, it.joinToString(" | ")) }
    }
}

package com.zmd.carhub

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

class AppEntry(val pkg: String, val label: String, val icon: Drawable?)

/** Finding and opening other apps installed on the car screen. */
object Apps {
    fun launchable(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != ctx.packageName }
            .map { AppEntry(it, label(ctx, it), icon(ctx, it)) }
            .sortedBy { it.label.lowercase() }
    }

    fun isInstalled(ctx: Context, pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        return runCatching { ctx.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)
    }

    fun label(ctx: Context, pkg: String?): String {
        if (pkg.isNullOrEmpty()) return ""
        return runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    fun icon(ctx: Context, pkg: String?): Drawable? {
        if (pkg.isNullOrEmpty()) return null
        return runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull()
    }

    /**
     * Opens an app's main screen. adjacent = ask the system to put it in the other half of a
     * split screen (only has an effect while the screen is already split).
     */
    fun launch(ctx: Context, pkg: String?, adjacent: Boolean = false): Boolean {
        if (pkg.isNullOrEmpty()) return false
        // Android Auto is built into ZMD Car now (ZmdAuto.PKG == our package), so our package here
        // means ZMD Car's own map; the separate ZMD Auto has a launcher icon of its own.
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (adjacent) i.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        return runCatching { ctx.startActivity(i); true }.getOrDefault(false)
    }

    /**
     * Opens the receiver app on its own settings page where it supports that. DiPlay's launcher
     * screen reads a "page" extra ("settings", "connection", "about"); other apps just open normally.
     */
    fun openSettingsOf(ctx: Context, pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        // Only ever called for the receiver apps: our package = the built-in Android Auto's settings.
        if (pkg == ZmdAuto.PKG) return ZmdAuto.openSettings(ctx)
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg == Prefs.DEFAULT_CARPLAY) i.putExtra("page", "settings")
        return runCatching { ctx.startActivity(i); true }.getOrDefault(false)
    }

    // Screens of the bundled receiver apps that other apps are allowed to open (exported).
    private const val DIPLAY_HOST = "com.shilapi.xcertplay.CarPlayHostActivity"
    private const val DIAUTO_AUTOMATION = "com.andrerinas.openheadunit.main.AutomationActivity"
    private const val DIAUTO_CONNECT = "com.andrerinas.openheadunit.ACTION_CONNECT"

    /**
     * Opens CarPlay / Android Auto itself, skipping the receiver app's own home screen:
     * - DiPlay: its CarPlay screen directly. For a cable connection it is opened the same way the
     *   system opens it for a USB device, which tells DiPlay to use the cable.
     * - DiAuto, cable: its "connect" command (no screen of its own); it shows Android Auto once
     *   the phone answers.
     * Anything else (or if that fails): the app's normal main screen.
     */
    fun launchProjection(ctx: Context, pkg: String?, cable: Boolean?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        val direct = when (pkg) {
            // Same as DiPlay's own «Connect with USB» button: cable mode + its CarPlay screen, which
            // then shows its detailed connection stages. Wireless can't be chosen from outside, so
            // a wireless iPhone goes through DiPlay's home screen («Connect when DiPlay opens»).
            Prefs.DEFAULT_CARPLAY -> if (cable == false) null else Intent().setClassName(pkg, DIPLAY_HOST).apply {
                if (cable == true) action = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
            }
            Prefs.OLD_AUTO -> if (cable == true) Intent(DIAUTO_CONNECT).setClassName(pkg, DIAUTO_AUTOMATION) else null
            // ZMD Auto: its own "connect" for a cable, otherwise bring its screen up.
            ZmdAuto.PKG -> return if (cable == true) ZmdAuto.connect(ctx) else ZmdAuto.show(ctx)
            else -> null
        }
        if (direct != null) {
            direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(direct); true }.getOrDefault(false)) return true
        }
        return launch(ctx, pkg)
    }
}

package com.zmd.carhub

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Every permission the hub needs, in one place, so the setup screen (MainActivity) and the
 * floating "permission missing" warning (HubService / Overlay) use the same wording and open
 * the same settings page.
 *
 * Required ones gate normal use: without them the setup screen opens instead of the map.
 * The rest are recommended: the app works, but a feature is missing, so the user is warned.
 */
enum class Perm(val title: String, val why: String, val required: Boolean) {
    OVERLAY("الظهور فوق البرامج", "حتى يطلع الشريط فوق الخريطة وفوق CarPlay", true),
    LOCATION("الموقع", "حتى الخريطة تبين وين إنت", true),
    BLUETOOTH("البلوتوث", "حتى يكشف التلفون من يتصل لاسلكي", false),
    ACCESSIBILITY("إمكانية الوصول", "لتقسيم الشاشة، ومعرفة البرنامج المفتوح", false),
    INSTALL("تنصيب البرامج", "حتى المتجر ينصّب ويحدّث البرامج", false),
    NOTIFICATIONS("الإشعارات", "حتى البرنامج يبقى شغّال وينبّهك", false),
    BATTERY("التشغيل بالخلفية", "حتى النظام ما يطفّي البرنامج وقت ما تشوفه", false),
    LAUNCHER("الشاشة الرئيسية (لانچر)", "حتى زر البيت يرجعك لـ ZMD Car بدل شاشة السيارة", false),
}

object Perms {
    /** Extra on MainActivity: open it as the first-run / missing-permission setup screen. */
    const val EXTRA_SETUP = "setup"

    /** Manifest.permission.BLUETOOTH_CONNECT / POST_NOTIFICATIONS, spelled out so older SDK constants aren't needed. */
    const val BT_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val POST_NOTIFS = "android.permission.POST_NOTIFICATIONS"

    /**
     * The runtime permissions behind [p] that an Activity can ask for with the system dialog, or
     * empty when [p] is only granted from a settings page.
     */
    fun runtimeOf(p: Perm): Array<String> = when (p) {
        Perm.LOCATION -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        Perm.BLUETOOTH -> if (Build.VERSION.SDK_INT >= 31) arrayOf(BT_CONNECT) else emptyArray()
        Perm.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) arrayOf(POST_NOTIFS) else emptyArray()
        else -> emptyArray()
    }

    fun granted(ctx: Context, p: Perm): Boolean = runCatching {
        when (p) {
            Perm.OVERLAY -> Settings.canDrawOverlays(ctx)
            Perm.LOCATION ->
                ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            // Android 12+ asks for "nearby devices" before the app may see Bluetooth phones.
            Perm.BLUETOOTH -> Build.VERSION.SDK_INT < 31 ||
                ctx.checkSelfPermission(BT_CONNECT) == PackageManager.PERMISSION_GRANTED
            Perm.ACCESSIBILITY -> a11yEnabled(ctx)
            Perm.INSTALL -> ctx.packageManager.canRequestPackageInstalls()
            Perm.NOTIFICATIONS -> NotificationManagerCompat.from(ctx).areNotificationsEnabled()
            Perm.BATTERY -> (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(ctx.packageName)
            Perm.LAUNCHER -> isHome(ctx)
        }
    }.getOrDefault(true) // if a car screen can't even answer, don't nag about it

    /** One line for the floating warning card, e.g. «إمكانية الوصول» مطفّية: لتقسيم الشاشة... */
    @Suppress("UNUSED_PARAMETER")
    fun alertLine(ctx: Context, p: Perm): String = "«${p.title}» مطفّية: ${p.why}"

    /**
     * The permissions that apply to this build. LAUNCHER only when ZMD Car declares a home screen:
     * builds for screens that refuse home apps leave that out (see HomeActivity in the manifest),
     * and then there is nothing the user could switch on.
     */
    fun all(ctx: Context): List<Perm> = Perm.values().filter {
        (it != Perm.LAUNCHER || canBeHome(ctx)) && (it != Perm.ACCESSIBILITY || hasA11yService(ctx))
    }

    /**
     * This build declares the accessibility helper. Only the "full" build does (installed from the
     * PC): Play Protect blocks hand installs of any APK with an accessibility service, so the normal
     * build leaves it out and there is nothing for the user to switch on.
     */
    fun hasA11yService(ctx: Context): Boolean = runCatching {
        ctx.packageManager.queryIntentServices(
            Intent("android.accessibilityservice.AccessibilityService").setPackage(ctx.packageName), 0
        ).isNotEmpty()
    }.getOrDefault(false)

    /** This build has an activity that can be the screen's home app. */
    fun canBeHome(ctx: Context): Boolean = runCatching {
        ctx.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(ctx.packageName), 0
        ).isNotEmpty()
    }.getOrDefault(false)

    /** Missing permissions, required ones first. */
    fun missing(ctx: Context): List<Perm> =
        all(ctx).filter { !granted(ctx, it) }.sortedBy { if (it.required) 0 else 1 }

    /**
     * Required ones that stop normal use. «الظهور فوق البرامج» only while the screen has a page to
     * allow it: some car screens (BYD DiLink) hide that page, and then the setup screen must not
     * hold the driver forever on something that can't be switched on.
     */
    fun missingRequired(ctx: Context): List<Perm> =
        missing(ctx).filter { it.required && (it != Perm.OVERLAY || overlayGrantable(ctx)) }

    /** This screen has a settings page where «display over other apps» can be allowed. */
    fun overlayGrantable(ctx: Context): Boolean = runCatching {
        val pm = ctx.packageManager
        listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        ).any { it.resolveActivity(pm) != null }
    }.getOrDefault(true)

    /**
     * This screen can install APKs from inside an app (Android's installer screen exists). Newer
     * BYD DiLink firmware removes it: there apps go in only through the USB «Third Party Apps» folder.
     */
    fun canInstallApks(ctx: Context): Boolean = runCatching {
        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://${ctx.packageName}.files/x.apk"),
            "application/vnd.android.package-archive").resolveActivity(ctx.packageManager) != null
    }.getOrDefault(true)

    /**
     * The exact settings page for [p] (already has FLAG_ACTIVITY_NEW_TASK). Callers wrap
     * startActivity in runCatching and fall back to [appDetails], because some car screens hide
     * these pages. LOCATION is a normal runtime permission: an Activity should ask with
     * requestPermissions first; this page is the fallback after "don't ask again".
     */
    fun settingsIntent(ctx: Context, p: Perm): Intent {
        val pkg = Uri.parse("package:${ctx.packageName}")
        val i = when (p) {
            Perm.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            Perm.LOCATION, Perm.BLUETOOTH -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            Perm.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            Perm.INSTALL -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg)
            Perm.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            // The direct "let ZMD Car run in the background?" dialog for this app
            // (needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in the manifest).
            Perm.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            // Android's "choose the home app" page.
            Perm.LAUNCHER -> Intent(Settings.ACTION_HOME_SETTINGS)
        }
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Second try when the first page doesn't exist on this screen: the closest list page. */
    private fun secondIntent(p: Perm): Intent? = when (p) {
        Perm.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        Perm.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        Perm.LAUNCHER -> Intent(Settings.ACTION_SETTINGS)
        else -> null
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** This app's info page: the fallback when a settings page doesn't exist on the car screen. */
    fun appDetails(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Opens [p]'s own page; if the car screen doesn't have it, the closest list page; and as a
     * last resort this app's info page. Only pages that really exist are started, so the user
     * never lands on the general settings screen by accident.
     */
    fun open(ctx: Context, p: Perm): Boolean {
        val pm = ctx.packageManager
        val tries = listOfNotNull(settingsIntent(ctx, p), secondIntent(p), appDetails(ctx))
        for (i in tries) {
            if (i.resolveActivity(pm) == null) continue
            if (runCatching { ctx.startActivity(i) }.isSuccess) return true
        }
        return false
    }

    /** MainActivity as the setup screen. */
    fun setupIntent(ctx: Context): Intent =
        Intent(ctx, MainActivity::class.java)
            .putExtra(EXTRA_SETUP, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** ZMD Car is the screen's home app. */
    fun isHome(ctx: Context): Boolean =
        ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName == ctx.packageName

    /** Free-floating (resizable) windows are on, so another maps app can sit beside the side bar. */
    fun freeformOn(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) ||
            Settings.Global.getInt(ctx.contentResolver, "enable_freeform_support", 0) != 0

    /**
     * BYD screens hide the accessibility page, and a reinstall turns the service off. When the PC
     * script has granted WRITE_SECURE_SETTINGS, put our service back in the enabled list ourselves.
     */
    fun selfHeal(ctx: Context) {
        if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return
        if (!hasA11yService(ctx) || a11yEnabled(ctx)) return
        runCatching {
            val cr = ctx.contentResolver
            val me = ComponentName(ctx, HubA11yService::class.java).flattenToString()
            val on = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                if (on.isBlank()) me else "$on:$me")
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
    }

    private fun a11yEnabled(ctx: Context): Boolean {
        val on = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val mine = ComponentName(ctx, HubA11yService::class.java)
        return on.split(':').any { ComponentName.unflattenFromString(it) == mine }
    }
}

package com.zmd.carhub

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * ZMD Auto (com.zmd.auto): our own build of DiAuto, the Android Auto receiver. It runs behind the
 * hub with no icon, screens or messages of its own: the hub asks it to connect, it reports every
 * stage back ([ACTION_STATUS]), and Android Auto's own screen comes up only once the phone has
 * accepted. Both apps are signed with the ZMD key, and they only talk through [PERMISSION]
 * (signature level), so no other app can send these reports or drive ZMD Auto's private screens.
 */
object ZmdAuto {
    /** Where the engine runs: ZMD Car itself (built in, ":auto" process). See Prefs.DEFAULT_AUTO. */
    const val PKG = Prefs.DEFAULT_AUTO
    const val PERMISSION = "com.zmd.carhub.permission.AUTO_LINK"
    const val ACTION_STATUS = "com.zmd.auto.STATUS"
    const val EXTRA_STATE = "state"
    const val EXTRA_REASON = "reason"
    const val EXTRA_DETAIL = "detail"
    const val EXTRA_SHOWN = "shown"

    // States (same names as zmd/ZmdLink.kt in ZMD Auto), in the order a cable connection goes.
    const val START = "start"
    /** Wireless: the engine is calling the phone it saw on Bluetooth and waits for it on Wi-Fi. */
    const val WIRELESS = "wireless"
    const val PERMISSION_ASK = "permission"
    const val SWITCHING = "switching"
    const val CONNECTING = "connecting"
    const val HANDSHAKE = "handshake"
    const val READY = "ready"
    const val RUNNING = "running"
    const val ENDED = "ended"
    const val FAILED = "failed"

    // Reasons
    const val NO_PHONE = "no_phone"
    const val PERMISSION_DENIED = "permission_denied"
    const val PERMISSION_ERROR = "permission_error"
    const val SWITCH_FAILED = "switch_failed"
    const val NO_RESPONSE = "no_response"
    const val CONNECT_FAILED = "connect_failed"
    const val SILENT = "silent"
    const val HANDSHAKE_FAILED = "handshake"
    const val USER_EXIT = "user_exit"
    const val PHONE_ENDED = "phone"
    const val LOST = "lost"
    const val WIRELESS_TIMEOUT = "wireless_timeout"

    private const val ACTION_CONNECT = "com.andrerinas.openheadunit.ACTION_CONNECT"
    private const val ACTION_SET_MODES = "com.zmd.auto.SET_MODES"
    private const val EXTRA_MAC = "zmd_mac"
    private const val AUTOMATION = "com.andrerinas.openheadunit.main.AutomationActivity"
    private const val PROJECTION = "com.andrerinas.openheadunit.aap.AapProjectionActivity"
    private const val SETTINGS = "com.andrerinas.openheadunit.main.MainActivity"
    private const val ASK_MIC = "com.andrerinas.openheadunit.zmd.ZmdPermissionActivity"

    fun isInstalled(ctx: Context): Boolean = Apps.isInstalled(ctx, PKG)

    /**
     * Connect over the cable in the background (or, if Android Auto is already up, bring its screen
     * back). An invisible screen of ZMD Auto's that closes at once; the answer comes as reports.
     */
    fun connect(ctx: Context): Boolean = start(ctx, Intent(ACTION_CONNECT).setClassName(PKG, AUTOMATION))

    /**
     * Connect WIRELESSLY to the phone that just connected over Bluetooth ([mac]): the engine makes
     * its own Wi-Fi (Wi-Fi Direct) and calls the phone, so nothing has to be picked or pressed.
     */
    fun connectWireless(ctx: Context, mac: String): Boolean =
        start(ctx, Intent(ACTION_CONNECT).setClassName(PKG, AUTOMATION).putExtra(EXTRA_MAC, mac))

    /** The driver's cable / wireless choice for Android, applied inside the engine too. */
    fun setModes(ctx: Context, usb: Boolean, wifi: Boolean) {
        runCatching {
            ctx.sendBroadcast(Intent(ACTION_SET_MODES).setPackage(PKG).putExtra("usb", usb).putExtra("wifi", wifi))
        }
    }

    /** Android Auto's own screen, once ZMD Auto said READY (the hub may open screens; it may not). */
    fun show(ctx: Context): Boolean = start(
        ctx,
        Intent().setClassName(PKG, PROJECTION)
            .putExtra("focus", true)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    )

    /** ZMD Auto's own settings (it has no icon, so it can only be opened from the hub). */
    fun openSettings(ctx: Context): Boolean = start(ctx, Intent(Intent.ACTION_MAIN).setClassName(PKG, SETTINGS))

    /** ZMD Auto may use the microphone (Google Assistant inside Android Auto). */
    fun micAllowed(ctx: Context): Boolean =
        runCatching { ctx.packageManager.checkPermission(Manifest.permission.RECORD_AUDIO, PKG) }
            .getOrDefault(PackageManager.PERMISSION_DENIED) == PackageManager.PERMISSION_GRANTED

    /** Android's own «allow microphone» prompt for ZMD Auto (or its Settings page if refused for good). */
    fun askMic(ctx: Context): Boolean = start(ctx, Intent().setClassName(PKG, ASK_MIC))

    private fun start(ctx: Context, i: Intent): Boolean {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(i); true }.getOrDefault(false)
    }

    /** What the driver sees while ZMD Auto works (null = nothing new to show). */
    fun stageText(state: String): String? = when (state) {
        START -> "دا ندوّر على التلفون بالكيبل"
        WIRELESS -> "دا نتصل بالتلفون لاسلكي (بلوتوث ثم واي فاي)، ابقى على الخريطة"
        PERMISSION_ASK -> "طلع طلب سماح USB لـ ZMD Auto: دوس «موافق» (وعلّم «دائماً» إذا موجودة)"
        SWITCHING -> "دا نحوّل التلفون لوضع Android Auto (ينفصل ويرجع لحظة)"
        CONNECTING -> "التلفون صار بوضع Android Auto، دا نتصل بيه"
        HANDSHAKE -> "اتصلنا بالتلفون. إذا طلعت رسالة على شاشة التلفون وافق عليها"
        else -> null
    }

    /** The exact cause of a failure, in the driver's words, and what to do about it. */
    fun failText(reason: String?, detail: String?): String = when (reason) {
        NO_PHONE -> "ما لگيت تلفون أندرويد على الكيبل. تأكد الكيبل ينقل بيانات (مو بس شحن)"
        PERMISSION_DENIED -> "انرفض سماح USB لـ ZMD Auto. شيل الكيبل ورجّعه ودوس «موافق»"
        PERMISSION_ERROR -> "الشاشة ما گدرت تطلب سماح USB. شيل الكيبل ورجّعه"
        SWITCH_FAILED -> "التلفون رفض يتحول لوضع Android Auto. افتح قفل التلفون وشيل الكيبل ورجّعه"
        NO_RESPONSE -> "التلفون ما رجع بوضع Android Auto. افتح قفله، وتأكد تطبيق Android Auto منصّب ومحدّث بالتلفون، أو جرّب كيبل ثاني"
        CONNECT_FAILED -> "ما انفتح الاتصال ويا التلفون. يمكن برنامج ثاني ماسك الكيبل (مثل QuickShare). شيل الكيبل ورجّعه"
        SILENT -> "التلفون ما رد على Android Auto. افتح قفل التلفون ووافق على أي رسالة بيه، أو شيل الكيبل ورجّعه"
        HANDSHAKE_FAILED -> "فشل التعارف الآمن ويا التلفون. شيل الكيبل ورجّعه"
        LOST -> "انقطع الاتصال ويا التلفون أثناء التشغيل. تأكد من الكيبل"
        WIRELESS_TIMEOUT -> "التلفون ما اتصل لاسلكي. بالتلفون: شغّل الواي فاي، وبإعدادات Android Auto فعّل «Android Auto اللاسلكي»، أو شبّكه بالكيبل"
        else -> "صار خطأ بـ Android Auto" + (detail?.takeIf { it.isNotBlank() }?.let { ": ${it.take(80)}" } ?: "")
    }
}

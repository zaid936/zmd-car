package com.zmd.carhub

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «دعم فني»: a plain-text report of this screen (versions, permissions, settings, what Android Auto
 * last said, recent problems shown on screen, ZMD Car's own log) that the driver sends with
 * Android's share sheet (WhatsApp, Telegram...) to whoever installed ZMD Car. Nothing is sent by
 * itself and there is no server: the driver picks the app and the person.
 */
object Support {
    /** The installer's support line (phone + WhatsApp), given by the user on 2026-11-18. */
    const val PHONE = "+9647737399996"
    private const val PHONE_SHOWN = "+964 773 739 9996"
    private const val WHATSAPP = "com.whatsapp"
    private const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    private val recent = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    /**
     * The «دعم فني» window: the support number, «ارسل التقرير بالواتساب» (when WhatsApp is on this
     * screen), another way to send it, and a QR code the driver scans with the phone to open a
     * WhatsApp chat with the support line, already holding a short summary.
     */
    fun show(a: Activity) {
        val pad = Ui.dp(a, 16)
        val box = Ui.vbox(a).apply {
            layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
            setPadding(pad, pad, pad, pad)
        }
        val dialog = android.app.AlertDialog.Builder(a).setView(box).create()
        box.addView(Ui.label(a, "الدعم الفني", 22f, bold = true))
        box.addView(Ui.label(a, "رقم الدعم والواتساب: $PHONE_SHOWN", 17f),
            Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(a, 6) })
        val wa = whatsappOnScreen(a)
        if (wa != null) {
            box.addView(Ui.button(a, "ارسل التقرير بالواتساب", primary = true) {
                dialog.dismiss()
                share(a, wa)
            }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(a, 12) })
        }
        box.addView(Ui.button(a, "ارسل التقرير بطريقة ثانية") {
            dialog.dismiss()
            share(a, null)
        }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(a, 8) })
        // QR: wa.me link with a short summary (the full report doesn't fit in a QR code).
        qr(waLink(summary(a)), Ui.dp(a, 220))?.let { bmp ->
            box.addView(Ui.label(a, "امسح الرمز بتلفونك حتى تراسلنا بالواتساب", 14f, Ui.SUB),
                Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(a, 14) })
            box.addView(android.widget.ImageView(a).apply { setImageBitmap(bmp) },
                Ui.lp(Ui.dp(a, 220), Ui.dp(a, 220)).apply { topMargin = Ui.dp(a, 6) })
        }
        dialog.show()
    }

    private fun whatsappOnScreen(ctx: Context): String? =
        listOf(WHATSAPP, WHATSAPP_BUSINESS).firstOrNull { Apps.isInstalled(ctx, it) }

    /** A few lines that fit a WhatsApp link: versions, screen, the last problem. */
    private fun summary(ctx: Context): String {
        val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        val last = notes().lastOrNull()?.take(120)
        return "مرحبا، أحتاج دعم فني لـ ZMD Car $ver\nالشاشة: ${Build.MANUFACTURER} ${Build.MODEL}، أندرويد ${Build.VERSION.RELEASE}" +
            (last?.let { "\nآخر مشكلة: $it" } ?: "")
    }

    private fun waLink(text: String): String =
        "https://wa.me/${PHONE.removePrefix("+")}?text=" + java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun qr(text: String, size: Int): android.graphics.Bitmap? = runCatching {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
            mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8", com.google.zxing.EncodeHintType.MARGIN to 1))
        android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.RGB_565).apply {
            for (x in 0 until size) for (y in 0 until size) setPixel(x, y, if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
    }.getOrNull()

    /** Remember a problem or event the driver saw (the last 40 go into the report). */
    @Synchronized
    fun note(text: String) {
        recent.addLast("${clock.format(Date())}  $text")
        while (recent.size > 40) recent.removeFirst()
    }

    @Synchronized
    private fun notes(): List<String> = recent.toList()

    /**
     * Builds the report and sends it: straight to the support line in WhatsApp when [whatsapp] is
     * the WhatsApp package on this screen, otherwise through the share sheet.
     */
    fun share(a: Activity, whatsapp: String? = null) {
        Thread {
            val text = runCatching { build(a) }.getOrElse { "report failed: $it" }
            val dir = File(a.getExternalFilesDir(null) ?: a.filesDir, "support").apply { mkdirs() }
            val f = File(dir, "ZMD-Car-report.txt").apply { writeText(text) }
            a.runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "تقرير ZMD Car")
                    .putExtra(Intent.EXTRA_TEXT, text.lineSequence().take(25).joinToString("\n"))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { send.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(a, "${a.packageName}.files", f)) }
                if (whatsapp != null) {
                    // WhatsApp opens the chat with the support number directly ("jid").
                    val direct = Intent(send).setPackage(whatsapp)
                        .putExtra("jid", "${PHONE.removePrefix("+")}@s.whatsapp.net")
                    if (runCatching { a.startActivity(direct) }.isSuccess) return@runOnUiThread
                }
                runCatching { a.startActivity(Intent.createChooser(send, "ارسل التقرير للدعم الفني ($PHONE_SHOWN)")) }
            }
        }.start()
    }

    private fun build(ctx: Context): String = buildString {
        val pm = ctx.packageManager
        fun ver(pkg: String): String = runCatching {
            val p = pm.getPackageInfo(pkg, 0)
            "${p.versionName} (${PackageInfoCompat.getLongVersionCode(p)})"
        }.getOrDefault("not installed")
        val dm = ctx.resources.displayMetrics
        appendLine("تقرير ZMD Car  ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}")
        appendLine("ZMD Car: ${ver(ctx.packageName)}  hand-install=${!Perms.hasA11yService(ctx)}")
        appendLine("Screen: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.HARDWARE})")
        appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${dm.widthPixels}x${dm.heightPixels} @${dm.densityDpi}dpi")
        appendLine("CarPlay (DiPlay): ${ver(Prefs.DEFAULT_CARPLAY)}")
        appendLine("Separate ZMD Auto: ${ver(Prefs.SEPARATE_AUTO)}   old DiAuto: ${ver(Prefs.OLD_AUTO)}   QuickShare: ${ver("com.bozee.usbdisplay")}")
        appendLine()
        appendLine("== Permissions")
        for (p in Perms.all(ctx)) appendLine("${if (Perms.granted(ctx, p)) "OK " else "NO "} ${p.title}")
        appendLine()
        appendLine("== Settings")
        val prefs = Prefs(ctx)
        for (t in PhoneType.values()) appendLine("${t.name}: ${prefs.mode(t)}")
        appendLine("auto-open=${prefs.autoOpenReceiver} back-to-map=${prefs.backToMap} map=${prefs.mapPackage ?: "built-in"}")
        appendLine("known phones: ${PhoneType.values().joinToString { t -> "${t.name}=${prefs.phones().count { it.type == t }}" }}")
        appendLine()
        appendLine("== Android Auto")
        appendLine(HubService.instance?.autoReport() ?: "hub service not running")
        appendLine()
        appendLine("== BYD")
        Byd.report(ctx).forEach { appendLine(it) }
        appendLine()
        appendLine("== Recent problems on screen")
        notes().ifEmpty { listOf("none") }.forEach { appendLine(it) }
        appendLine()
        appendLine("== ZMD Car log (last lines)")
        appendLine(ownLog())
    }

    /** This app's own log lines (apps may read their own log, both of ZMD Car's processes). */
    private fun ownLog(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "400").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().also { p.destroy() }
    }.getOrDefault("(log not readable)")
}

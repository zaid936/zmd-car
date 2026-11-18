package com.zmd.carhub

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import com.andrerinas.openheadunit.utils.Settings as AaSettings

/**
 * Android Auto's settings inside ZMD Car, in ZMD Car's own look (replaces the engine's original
 * settings screens, which the driver should never see). Runs in the ":auto" process, where the
 * built-in engine lives, so it reads and writes the engine's own settings directly. Most changes
 * apply from the next connection.
 */
class AutoSettingsActivity : Activity() {

    private lateinit var s: AaSettings
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = AaSettings(this)
        val t = Ui.theme(this)
        window.setBackgroundDrawable(Ui.appBackground(this, t))
        list = Ui.vbox(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            val p = dp(16)
            setPadding(p, p + dp(8), p, p)
        }
        setContentView(ScrollView(this).apply { addView(list) })
        render()
    }

    private fun render() {
        list.removeAllViews()
        val head = Ui.hbox(this).apply { gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val titles = Ui.vbox(this)
        titles.addView(Ui.label(this, "إعدادات Android Auto", 26f, bold = true))
        titles.addView(Ui.label(this, "مدمج داخل ZMD Car · التغييرات تشتغل من الاتصال الجاي", 13f, Ui.SUB))
        head.addView(titles, Ui.lp(0, Ui.WRAP, 1f))
        head.addView(Ui.button(this, "رجوع", primary = true) { finish() })
        list.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(14) })

        // ---- picture
        card("الصورة").also { box ->
            val res = listOf(0, 1, 2, 3)
            segmentedRow(box, "الدقة", "أعلى = أوضح بس يحتاج تلفون وشاشة أقوى",
                listOf("تلقائي", "480p", "720p", "1080p"), res.indexOf(s.resolutionId).coerceAtLeast(0)) { s.resolutionId = res[it] }
            val dpis = listOf(0, 120, 160, 200, 240)
            segmentedRow(box, "حجم الواجهة (DPI)", "رقم أقل = أشياء أصغر وأكثر بالشاشة",
                listOf("تلقائي", "120", "160", "200", "240"), dpis.indexOf(s.dpiPixelDensity).coerceAtLeast(0)) { s.dpiPixelDensity = dpis[it] }
            val fps = listOf(30, 60)
            segmentedRow(box, "سرعة الصورة", null, listOf("30", "60"), fps.indexOf(s.fpsLimit).let { if (it < 0) 1 else it }) { s.fpsLimit = fps[it] }
            val codecs = listOf("Auto", "H.264", "H.265")
            segmentedRow(box, "نوع الفيديو", "إذا الصورة تقطّع جرّب H.264",
                listOf("تلقائي", "H.264", "H.265"), codecs.indexOf(s.videoCodec).coerceAtLeast(0)) { s.videoCodec = codecs[it] }
            switchRow(box, "يملي الشاشة كلها", "بدون أطراف سودة", s.stretchToFill) { s.stretchToFill = it }
            switchRow(box, "فك الفيديو بالبرنامج", "شغّلها بس إذا الصورة تطلع سودة", s.forceSoftwareDecoding) { s.forceSoftwareDecoding = it }
        }

        // ---- sound
        card("الصوت").also { box ->
            switchRow(box, "الأغاني من بلوتوث السيارة", "الأغاني تطلع بالبلوتوث، والملاحة والمكالمات من Android Auto", s.musicViaBluetooth) { s.musicViaBluetooth = it }
            switchRow(box, "فصل صوت الملاحة عن الأغاني", null, s.separateAudioStreams) { s.separateAudioStreams = it }
            switchRow(box, "صوت AAC", "جودة أعلى إذا التلفون يدعمه", s.useAacAudio) { s.useAacAudio = it }
            val lat = listOf(4, 8, 16)
            segmentedRow(box, "تأخير الصوت", "إذا الصوت يقطّع اختار «أكثر»",
                listOf("أقل", "عادي", "أكثر"), lat.indexOf(s.audioLatencyMultiplier).let { if (it < 0) 1 else it }) { s.audioLatencyMultiplier = lat[it] }
        }

        // ---- connection
        card("الاتصال").also { box ->
            box.addView(Ui.label(this, "كيبل / لاسلكي تختاره من «طريقة الاتصال» بإعدادات ZMD Car", 14f, Ui.SUB),
                Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(6) })
            val ap = listOf(0, 1)
            segmentedRow(box, "الواي فاي باللاسلكي", "Wi-Fi Direct ما يحتاج إعداد. الهوتسبوت يحتاج اسم ورمز",
                listOf("Wi-Fi Direct", "هوتسبوت الشاشة"), ap.indexOf(s.nativeApTransport).coerceAtLeast(0)) {
                s.nativeApTransport = ap[it]
                render()
            }
            if (s.nativeApTransport == 1) {
                textRow(box, "اسم الهوتسبوت", hotspotSsid(), false) { setHotspot(ssid = it) }
                textRow(box, "رمز الهوتسبوت", if (hotspotPass().isEmpty()) "" else "••••••", true) { setHotspot(pass = it) }
            }
            switchRow(box, "طريقة USB البديلة", "شغّلها إذا التلفون ما يتصل بالكيبل أبداً", s.useLibusb) { s.useLibusb = it }
            switchRow(box, "يتصل وحده من يتوصل التلفون", null, s.autoConnectSingleUsbDevice) { s.autoConnectSingleUsbDevice = it }
            switchRow(box, "يسكّر Android Auto من ينفصل التلفون", null, s.killOnDisconnect) { s.killOnDisconnect = it }
        }

        // ---- night
        card("الوضع الليلي").also { box ->
            val modes = listOf(AaSettings.NightMode.AUTO, AaSettings.NightMode.DAY, AaSettings.NightMode.NIGHT,
                AaSettings.NightMode.LIGHT_SENSOR, AaSettings.NightMode.CAR_SIGNAL)
            segmentedRow(box, "متى يصير ليلي", "«الغروب» يتغير وحده حسب وقت الغروب",
                listOf("الغروب", "نهار", "ليل", "حساس الضوء", "ضوية السيارة"), modes.indexOf(s.nightMode).coerceAtLeast(0)) { s.nightMode = modes[it] }
        }

        // ---- BYD
        card("BYD").also { box ->
            switchRow(box, "أسهم الملاحة على العدادات", "شاشات BYD بس", s.bydNavigationEnabled) { s.bydNavigationEnabled = it }
            switchRow(box, "خريطة على شاشة العدادات", "شاشات BYD بس", s.clusterMapEnabled) { s.clusterMapEnabled = it }
            switchRow(box, "إشعارات الملاحة", null, s.showNavigationNotifications) { s.showNavigationNotifications = it }
        }

        // ---- car + reset
        card("السيارة").also { box ->
            // Fixed (the user's choice): the engine sets it back to «ZMD» every start.
            row(box, "اسم السيارة بـ Android Auto", com.andrerinas.openheadunit.zmd.ZmdDefaults.CAR_NAME, null)
            row(box, "رجّع إعدادات Android Auto الأصلية", "إذا شي ما يشتغل بعد التغيير", Ui.button(this, "رجّع") { confirmReset() })
        }
    }

    // ---------------------------------------------------------------- hotspot (engine keeps these in its own prefs)

    private fun prefs() = getSharedPreferences(AaSettings.PREFS_NAME, MODE_PRIVATE)
    private fun hotspotSsid() = prefs().getString("hotspot-ssid-override", "").orEmpty()
    private fun hotspotPass() = prefs().getString("hotspot-password-override", "").orEmpty()
    private fun setHotspot(ssid: String? = null, pass: String? = null) {
        prefs().edit().apply {
            if (ssid != null) putString("hotspot-ssid-override", ssid.trim())
            if (pass != null) putString("hotspot-password-override", pass)
        }.apply()
        render()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("ترجيع الإعدادات الأصلية؟")
            .setMessage("كل إعدادات Android Auto ترجع مثل أول تنصيب. إعدادات ZMD Car الثانية ما تتغير.")
            .setPositiveButton("رجّع") { _, _ ->
                prefs().edit().clear().apply()
                getSharedPreferences("zmd", MODE_PRIVATE).edit().clear().apply()
                s = AaSettings(this)
                com.andrerinas.openheadunit.zmd.ZmdDefaults.applyOnce(this, s)
                val m = Prefs(this).mode(PhoneType.ANDROID)
                com.andrerinas.openheadunit.zmd.ZmdModes.apply(this, m != LinkMode.WIRELESS, m != LinkMode.CABLE)
                Toast.makeText(this, "رجعت الإعدادات الأصلية", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton("لا", null)
            .show()
    }

    // ---------------------------------------------------------------- building blocks (same look as MainActivity)

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun card(title: String): LinearLayout = Ui.vbox(this).apply {
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        background = Ui.glass(this@AutoSettingsActivity, 22f)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        addView(Ui.label(this@AutoSettingsActivity, title, 19f, bold = true), Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(8) })
        list.addView(this, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(12) })
    }

    private fun row(box: LinearLayout, title: String, sub: String?, end: View?) {
        val r = Ui.hbox(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(0, dp(6), 0, dp(6))
        }
        val texts = Ui.vbox(this)
        texts.addView(Ui.label(this, title, 16f, bold = true))
        if (!sub.isNullOrEmpty()) texts.addView(Ui.label(this, sub, 14f, Ui.SUB))
        r.addView(texts, Ui.lp(0, Ui.WRAP, 1f))
        if (end != null) r.addView(end, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = dp(10) })
        box.addView(r, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    private fun switchRow(box: LinearLayout, title: String, sub: String?, on: Boolean, onChange: (Boolean) -> Unit) {
        val sw = Switch(this).apply {
            isChecked = on
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Ui.WHITE, 0xFFD0DCE8.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(Ui.theme(this@AutoSettingsActivity).accent, 0x66FFFFFF))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row(box, title, sub, sw)
    }

    private fun segmentedRow(box: LinearLayout, title: String, sub: String?, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        box.addView(Ui.label(this, title, 16f, bold = true), Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8) })
        if (!sub.isNullOrEmpty()) box.addView(Ui.label(this, sub, 14f, Ui.SUB))
        box.addView(Ui.segmented(this, options, selected, onSelect), Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6); bottomMargin = dp(6) })
    }

    private fun textRow(box: LinearLayout, title: String, value: String, secret: Boolean, onSave: (String) -> Unit) {
        row(box, title, value.ifEmpty { "ما محدد" }, Ui.button(this, "غيّر") {
            val input = EditText(this).apply {
                setText(if (secret) "" else value)
                inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            }
            AlertDialog.Builder(this).setTitle(title).setView(input)
                .setPositiveButton("حفظ") { _, _ -> onSave(input.text.toString()); render() }
                .setNegativeButton("إلغاء", null)
                .show()
        })
    }
}

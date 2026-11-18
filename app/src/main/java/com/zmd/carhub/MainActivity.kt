package com.zmd.carhub

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast

/**
 * The hub's own screen: permissions, which apps to open, cable/wireless choice, the store link,
 * remembered Bluetooth phones, and test buttons. Opens by long-pressing the floating bar.
 */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        // Colourful night background so the glass cards have something to show through.
        window.setBackgroundDrawable(Ui.appBackground(this))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Starts the service (and the floating bar, once the overlay permission is granted).
        HubService.start(this)
        // Like the store and the app picker: the bar (and its permission warning) steps aside here.
        HubService.instance?.hideBarForSheet()
        if (inSetup()) renderSetup() else render()
    }

    /** First launch, or a required permission is missing: show the setup screen instead of the settings. */
    private fun inSetup(): Boolean =
        !prefs.setupDone || intent.getBooleanExtra(Perms.EXTRA_SETUP, false) || Perms.missingRequired(this).isNotEmpty()

    private fun dp(v: Int) = Ui.dp(this, v)

    // ---------------------------------------------------------------- setup

    /**
     * First-run wizard for a new customer, one step at a time: 1) permissions, 2) pair the phone
     * over Bluetooth and say whether it's an iPhone or an Android, 3) the map (look + the offline
     * Iraq download). Only step 1 is required. When setup was already done and a required
     * permission went missing later, only the permissions step shows.
     */
    private var step = 0
    private var mapListener: ((MapData.Status) -> Unit)? = null

    private fun renderSetup() {
        val c = this
        val steps = if (prefs.setupDone) 1 else 3
        if (step >= steps) step = steps - 1
        val root = Ui.vbox(c).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(24), dp(20) + Ui.dp(c, prefs.iconDp + 24), dp(24), dp(28))
        }
        root.addView(Ui.label(c, "أهلاً بيك بـ ${getString(R.string.app_name)}", 28f, bold = true))
        if (steps > 1) {
            root.addView(Ui.label(c, "خطوة ${step + 1} من $steps: " + STEP_TITLES[step], 16f, Ui.OK, bold = true),
                Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(4) })
            root.addView(stepDots(steps), Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = dp(8) })
        }
        if (step != 2) mapListener?.let { MapData.removeListener(it); mapListener = null }
        when (step) {
            0 -> setupPermissions(root)
            1 -> setupPhone(root)
            else -> setupMap(root)
        }

        val needed = Perms.missingRequired(c)
        val bottom = Ui.hbox(c).apply { gravity = Gravity.CENTER_VERTICAL }
        val note = when {
            step == 0 && needed.isNotEmpty() -> "باقي لازم: " + needed.joinToString("، ") { it.title }
            step == 0 && Perms.missing(c).isNotEmpty() -> "تگدر تكمل هسه، والباقي تفعّله بعدين من الإعدادات"
            step == 0 -> "الصلاحيات كلها جاهزة"
            step == 1 -> "تگدر تتخطى هالخطوة وتقرن التلفون بعدين"
            else -> "تگدر تنزّل الخريطة بعدين من إعدادات الخريطة"
        }
        bottom.addView(Ui.label(c, note, 15f, if (step == 0 && needed.isNotEmpty()) Ui.BAD else Ui.OK), Ui.lp(0, Ui.WRAP, 1f))
        if (step > 0) {
            bottom.addView(Ui.button(c, "رجوع") { step--; renderSetup() },
                Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = dp(10) })
        }
        if (step == 0) {
            val missing = Perms.missing(c)
            if (missing.isNotEmpty()) {
                // Walks through the missing ones in order: one tap, one settings page.
                bottom.addView(Ui.button(c, "فعّل الجاية") { request(missing.first()) },
                    Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = dp(10) })
            }
        }
        val last = step == steps - 1
        bottom.addView(Ui.button(c, if (last) "ابدأ" else "التالي", primary = needed.isEmpty()) {
            if (Perms.missingRequired(c).isNotEmpty()) {
                toast("فعّل الصلاحيات اللازمة أول")
                step = 0
                renderSetup()
                return@button
            }
            if (!last) {
                step++
                renderSetup()
                return@button
            }
            prefs.setupDone = true
            startActivity(Intent(c, MapActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = dp(10) })
        root.addView(bottom, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(18) })

        setContentView(ScrollView(c).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(root)
        })
    }

    /** Small progress pills under the step title. */
    private fun stepDots(n: Int): View = Ui.hbox(this).apply {
        for (i in 0 until n) {
            addView(View(this@MainActivity).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(if (i <= step) Ui.OK else 0x55FFFFFF)
                }
            }, LinearLayout.LayoutParams(dp(if (i == step) 28 else 14), dp(8)).apply { marginEnd = dp(6) })
        }
    }

    private fun setupPermissions(root: LinearLayout) {
        root.addView(Ui.label(this, "فعّل هالصلاحيات حتى يشتغل البرنامج زين. كل زر يفتحلك صفحتها، فعّلها وارجع لهنا.", 15f, Ui.SUB),
            Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(10) })
        root.addView(permissionsCard(), cardLp())
    }

    /** Step 2: pair the phone in Android's Bluetooth page, then label it here (iPhone / Android). */
    private fun setupPhone(root: LinearLayout) {
        val c = this
        root.addView(Ui.label(c, "اقرن تلفونك ويه الشاشة بالبلوتوث، وبعدين حدد نوعه حتى البرنامج يعرف يفتحله CarPlay لو Android Auto.", 15f, Ui.SUB),
            Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(10) })
        val box = card("التلفون")
        box.addView(Ui.label(c, "1. دوس «افتح البلوتوث» واقرن تلفونك من هناك.\n2. ارجع لهنا وحدد نوع التلفون من القائمة.", 15f),
            Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(10) })
        val btns = Ui.hbox(c)
        btns.addView(Ui.button(c, "افتح البلوتوث", primary = true) {
            open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(10) })
        btns.addView(Ui.button(c, "حدّث القائمة") { renderSetup() }, Ui.lp(Ui.WRAP, Ui.WRAP))
        box.addView(btns, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(8) })

        val phones = bondedPhones()
        when {
            phones == null -> box.addView(Ui.label(c, "صلاحية البلوتوث مطفية، فما أگدر أشوف التلفونات المقترنة. ارجع للخطوة الأولى وفعّلها.", 14f, Ui.BAD))
            phones.isEmpty() -> box.addView(Ui.label(c, "بعد ما أكو تلفون مقترن ويه الشاشة.", 14f, Ui.SUB))
            else -> for ((mac, name, guess) in phones) {
                val saved = prefs.phoneType(mac)
                val shown = saved ?: guess
                val sel = when (shown) { PhoneType.IPHONE -> 0; PhoneType.ANDROID -> 1; else -> -1 }
                val seg = Ui.segmented(c, listOf("آيفون", "أندرويد"), sel) { i ->
                    prefs.savePhone(mac, name, if (i == 0) PhoneType.IPHONE else PhoneType.ANDROID)
                    renderSetup()
                }
                val sub = when (saved) {
                    PhoneType.IPHONE -> "محفوظ: آيفون ← CarPlay"
                    PhoneType.ANDROID -> "محفوظ: أندرويد ← Android Auto"
                    null -> "حدد نوعه"
                }
                row(box, name, sub, null, status(saved != null), seg)
            }
        }
        root.addView(box, cardLp())
    }

    /** Phones paired with this screen: (address, name, best guess of the type); null = no Bluetooth permission. */
    private fun bondedPhones(): List<Triple<String, String, PhoneType?>>? {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Perms.BT_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.filter { Detect.btLooksLikePhone(it) }.map {
                Triple(it.address, runCatching { it.name }.getOrNull() ?: it.address, Detect.btGuess(it))
            }.sortedBy { it.second.lowercase() }
        }.getOrDefault(emptyList())
    }

    /** Step 3: how the map looks, and the one-time offline Iraq download (search + directions without internet). */
    private fun setupMap(root: LinearLayout) {
        val c = this
        root.addView(Ui.label(c, "اختار شكل الخريطة، ونزّل خريطة العراق حتى البحث والاتجاهات تشتغل بدون نت.", 15f, Ui.SUB),
            Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(10) })
        val box = card("الخريطة")
        box.addView(Ui.label(c, "شكل الخريطة", 16f, bold = true), Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(6) })
        for (r in MapLook.values().toList().chunked(3)) {
            box.addView(Ui.segmented(c, r.map { it.title }, r.indexOf(prefs.mapLook)) { i ->
                prefs.mapLook = r[i]
                renderSetup()
            }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(8) })
        }
        val dl = Ui.label(c, mapDownloadText(MapData.status), 15f, if (MapData.complete(c)) Ui.OK else Ui.WHITE)
        val r = Ui.hbox(c).apply { gravity = Gravity.CENTER_VERTICAL }
        r.addView(dl, Ui.lp(0, Ui.WRAP, 1f))
        if (!MapData.complete(c) && !MapData.busy()) {
            r.addView(Ui.button(c, "نزّل خريطة العراق", primary = true) {
                MapData.start(c)
                renderSetup()
            }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = dp(10) })
        }
        box.addView(r, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8) })
        root.addView(box, cardLp())
        // Live progress while this step is on screen.
        mapListener?.let { MapData.removeListener(it) }
        var lastPhase = MapData.status.phase
        val l: (MapData.Status) -> Unit = { st ->
            dl.text = mapDownloadText(st)
            val ended = st.phase == MapData.Phase.DONE || st.phase == MapData.Phase.ERROR
            if (ended && lastPhase != st.phase && !isFinishing) { lastPhase = st.phase; renderSetup() } else lastPhase = st.phase
        }
        mapListener = l
        MapData.addListener(l)
    }

    private fun mapDownloadText(st: MapData.Status): String = when {
        MapData.complete(this) -> "خريطة العراق منزّلة ✓"
        st.phase == MapData.Phase.DOWNLOADING && st.total > 0 ->
            "دا ينزّل: ${st.done * 100 / st.total}% (${st.done / 1_048_576} من ${st.total / 1_048_576} ميگا)"
        st.phase == MapData.Phase.CHECKING || st.phase == MapData.Phase.DOWNLOADING -> "دا يبدي التنزيل…"
        st.phase == MapData.Phase.INSTALLING -> "دا يجهّز الخريطة…"
        st.phase == MapData.Phase.ERROR -> "التنزيل وگف" + (if (st.note.isNotEmpty()) ": ${st.note}" else "") + ". جرّب مرة ثانية"
        else -> "مرة وحدة، حوالي 290 ميگا. يكمل بالخلفية حتى لو طلعت من هالصفحة"
    }

    override fun onPause() {
        super.onPause()
        mapListener?.let { MapData.removeListener(it) }
        mapListener = null
    }

    /**
     * Asks for one permission. Location, Bluetooth (Android 12+) and notifications (Android 13+)
     * get the system dialog; after "don't ask again", and for the others, its settings page opens.
     */
    private fun request(p: Perm) {
        val runtime = Perms.runtimeOf(p)
        if (runtime.isNotEmpty() && (!prefs.wasAsked(p) || shouldShowRequestPermissionRationale(runtime[0]))) {
            prefs.markAsked(p)
            requestPermissions(runtime, REQ_RUNTIME)
            return
        }
        if (!Perms.open(this, p)) toast("الشاشة ما بيها هالإعداد")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RUNTIME) {
            if (inSetup()) renderSetup() else render()
        }
    }

    /** The sections of the settings screen, in the side list. */
    private enum class Section(val title: String, val icon: String) {
        HOME("الرئيسية", "🏠"), CONNECT("الاتصال", "🔗"), APPS("البرامج", "📱"), MAP("الخريطة", "🗺"),
        LOOK("المظهر", "🎨"), GENERAL("عام", "⚙"), PERMS("الصلاحيات", "🔐"), TEST("تجربة", "🧪")
    }

    private var section = Section.HOME

    private fun render() {
        val c = this
        val t = Ui.theme(c)
        window.setBackgroundDrawable(Ui.appBackground(c, t))
        val top = dp(16) + Ui.dp(c, prefs.iconDp + 24)   // room for the floating bar
        val wide = resources.configuration.screenWidthDp >= 840

        // Header: name, version, quick actions.
        val head = Ui.hbox(c).apply { gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val titles = Ui.vbox(c)
        titles.addView(Ui.label(c, getString(R.string.app_name), 28f, bold = true))
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        titles.addView(Ui.label(c, "النسخة $ver · دوس مطوّل على الشريط الطايف حتى ترجع لهنا", 13f, Ui.SUB))
        // AGPL-3.0 (the built-in Android Auto engine): whoever gets the app can get its source.
        titles.addView(Ui.label(c, "الرخصة AGPL-3.0 · الكود المصدري: $SOURCE_URL", 12f, Ui.SUB))
        head.addView(titles, Ui.lp(0, Ui.WRAP, 1f))
        // «دعم فني»: a report of this screen, sent by the driver through the share sheet (Support.kt).
        head.addView(Ui.button(c, "دعم فني") { Support.show(this) },
            Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(10) })
        head.addView(Ui.button(c, "المتجر") { startActivity(Intent(c, StoreActivity::class.java)) },
            Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(10) })
        head.addView(Ui.button(c, "افتح الخريطة", primary = true) { HubService.start(c, HubService.ACTION_MAP) })

        // Side list of sections (on the right, Arabic order) and the chosen section's content.
        val nav = Ui.vbox(c).apply { setPadding(dp(8), dp(8), dp(8), dp(8)); background = Ui.glass(c, 22f) }
        for (sec in Section.values()) {
            val on = sec == section
            val item = Ui.hbox(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = Ui.ripple(c, if (on) android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor((t.accent and 0x00FFFFFF) or 0x40000000)
                    setStroke(dp(2), t.accent)
                } else null, 16f)
                isClickable = true
                setOnClickListener { section = sec; render() }
            }
            item.addView(Ui.label(c, sec.icon, 20f), Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(12) })
            item.addView(Ui.label(c, sec.title, 17f, if (on) Ui.WHITE else Ui.SUB, bold = on))
            nav.addView(item, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(4) })
        }

        val content = Ui.vbox(c)
        content.addView(Ui.label(c, "${section.icon}  ${section.title}", 24f, bold = true), Ui.lp(Ui.MATCH, Ui.WRAP))
        when (section) {
            Section.HOME -> homeCards(content)
            Section.CONNECT -> { content.addView(modesCard(), cardLp()); content.addView(connectCard(), cardLp()); content.addView(phonesCard(), cardLp()) }
            Section.APPS -> content.addView(appsCard(), cardLp())
            Section.MAP -> content.addView(mapCard(), cardLp())
            Section.LOOK -> { content.addView(themeCard(), cardLp()); content.addView(iconsCard(), cardLp()) }
            Section.GENERAL -> content.addView(generalCard(), cardLp())
            Section.PERMS -> content.addView(permissionsCard(), cardLp())
            Section.TEST -> content.addView(testCard(), cardLp())
        }
        val contentScroll = ScrollView(c).apply { isVerticalScrollBarEnabled = false; addView(content) }

        val root = Ui.vbox(c).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(24), top, dp(24), dp(20))
        }
        root.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP))
        if (wide) {
            val body = Ui.hbox(c).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
            body.addView(ScrollView(c).apply { isVerticalScrollBarEnabled = false; addView(nav) }, Ui.lp(dp(250), Ui.MATCH))
            body.addView(contentScroll, Ui.lp(0, Ui.MATCH, 1f).apply { marginStart = dp(20) })
            root.addView(body, Ui.lp(Ui.MATCH, 0, 1f).apply { topMargin = dp(16) })
            setContentView(root)
        } else {
            // Narrow screen: the sections as a row of chips above the content.
            nav.orientation = LinearLayout.HORIZONTAL
            root.addView(android.widget.HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(nav) },
                Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(12) })
            root.addView(contentScroll, Ui.lp(Ui.MATCH, 0, 1f).apply { topMargin = dp(8) })
            setContentView(root)
        }
    }

    /** First page: everything at a glance, with a shortcut to what still needs doing. */
    private fun homeCards(content: LinearLayout) {
        val c = this
        val t = Ui.theme(c)
        val tiles = Ui.hbox(c).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun tile(icon: String, title: String, value: String, ok: Boolean, go: Section) {
            val v = Ui.vbox(c).apply {
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = Ui.ripple(c, Ui.glass(c, 20f), 20f)
                isClickable = true
                setOnClickListener { section = go; render() }
            }
            v.addView(Ui.label(c, icon, 26f))
            v.addView(Ui.label(c, title, 14f, Ui.SUB), Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = dp(6) })
            v.addView(Ui.label(c, value, 18f, if (ok) Ui.OK else Ui.BAD, bold = true))
            tiles.addView(v, Ui.lp(0, Ui.WRAP, 1f).apply { marginEnd = dp(10) })
        }
        val perms = Perms.all(c)
        val have = perms.count { Perms.granted(c, it) }
        tile("🔐", "الصلاحيات", "$have من ${perms.size}", Perms.missingRequired(c).isEmpty(), Section.PERMS)
        val cp = !prefs.carplayPackage.isNullOrEmpty() && Apps.isInstalled(c, prefs.carplayPackage!!)
        tile("", "CarPlay", if (cp) "جاهز" else "مو منصّب", cp, Section.APPS)
        val aa = !prefs.autoPackage.isNullOrEmpty() && Apps.isInstalled(c, prefs.autoPackage!!)
        tile("🤖", "Android Auto", if (aa) "جاهز" else "مو منصّب", aa, Section.APPS)
        val pack = MapData.complete(c)
        tile("🗺", "خريطة العراق", if (pack) "منزّلة" else "بالنت", pack, Section.MAP)
        content.addView(tiles, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(14) })

        val how = card("شلون يشتغل")
        for (line in listOf(
            "وصّل الآيفون أو الأندرويد بالكيبل أو باللاسلكي، والبرنامج يفتح CarPlay أو Android Auto حسب إعدادات «الاتصال».",
            "الشريط الطايف: دوسه حتى تتنقل، ودوس مطوّل حتى ترجع لهالشاشة.",
            "اسحب من حافة الشاشة حتى تقسّم الشاشة أو ترجع للخريطة.",
            "المتجر: برامج وتحديثات للسيارة، وتحديث ZMD Car نفسه.",
        )) how.addView(Ui.label(c, "•  $line", 15f, Ui.SUB), Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(6) })
        content.addView(how, cardLp())
        @Suppress("UNUSED_VARIABLE") val unused = t
    }

    private fun connectCard(): View {
        val box = card("CarPlay و Android Auto")
        row(box, "يفتح CarPlay/Android Auto وحده عند الاتصال",
            "إذا مطفي: تطلع الأيقونة ملوّنة وتدوسها حتى تفتح",
            null, null, switch(prefs.autoOpenReceiver) { prefs.autoOpenReceiver = it })
        // Only the full build (installed from the PC) has the accessibility helper that can tap it.
        if (Perms.hasA11yService(this)) row(box, "يدوس سماح USB لـ CarPlay وحده",
            "الآيفون يرجع يتصل بسرعة فيروح السماح قبل ما تلحق. شغّلها حتى البرنامج يوافق وحده (يحتاج إمكانية الوصول)",
            null, null, switch(HubA11yService.autoAllowCarPlayUsb(this)) { HubA11yService.setAutoAllowCarPlayUsb(this, it) })
        row(box, "يرجع للخريطة من ينفصل التلفون", null, null, null,
            switch(prefs.backToMap) { prefs.backToMap = it })
        return box
    }

    private fun mapCard(): View {
        val box = card("الخريطة المدمجة")
        box.addView(Ui.label(this, "شكل الخريطة", 16f, bold = true), Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(6) })
        val looks = MapLook.values().toList()
        for (row in looks.chunked(3)) {
            box.addView(Ui.segmented(this, row.map { it.title }, row.indexOf(prefs.mapLook)) { i ->
                prefs.mapLook = row[i]
                render()
            }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(8) })
        }
        box.addView(Ui.label(this, "باقي إعدادات الخريطة (السيارة، تنزيل العراق والبنايات، الصوت) من زر الإعدادات داخل الخريطة نفسها.", 14f, Ui.SUB),
            Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6) })
        box.addView(Ui.button(this, "افتح الخريطة", primary = true) { HubService.start(this, HubService.ACTION_MAP) },
            Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = dp(10) })
        return box
    }

    /** Theme picker: a small preview of each theme's background, its accent, and its name. */
    private fun themeCard(): View {
        val c = this
        val box = card("الثيم")
        val cur = Ui.theme(c)
        for (rowThemes in Ui.themes.chunked(3)) {
            val line = Ui.hbox(c).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
            for (th in rowThemes) {
                val on = th.id == cur.id
                val cell = Ui.vbox(c).apply {
                    setPadding(dp(8), dp(8), dp(8), dp(10))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat()
                        setColor(0x14FFFFFF)
                        setStroke(dp(if (on) 3 else 1), if (on) th.accent else 0x33FFFFFF)
                    }
                    isClickable = true
                    setOnClickListener {
                        Ui.setTheme(c, th.id)
                        // The floating bar and the map follow straight away.
                        HubService.start(c, HubService.ACTION_REBUILD)
                        render()
                    }
                }
                val preview = android.widget.FrameLayout(c).apply {
                    background = Ui.appBackground(c, th)
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(12).toFloat()) }
                    }
                }
                preview.addView(View(c).apply { background = Ui.solid(c, th.accent, 10f) },
                    android.widget.FrameLayout.LayoutParams(dp(54), dp(20), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(10), 0, 0, dp(10)) })
                cell.addView(preview, Ui.lp(Ui.MATCH, dp(86)))
                cell.addView(Ui.label(c, (if (on) "✓ " else "") + th.name, 15f, bold = on).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER },
                    Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8) })
                line.addView(cell, Ui.lp(0, Ui.WRAP, 1f).apply { marginEnd = dp(10); bottomMargin = dp(10) })
            }
            repeat(3 - rowThemes.size) { line.addView(View(c), Ui.lp(0, 1, 1f)) }
            box.addView(line, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        return box
    }

    private fun iconsCard(): View {
        val box = card("الأيقونات الطايفة")
        val sizes = listOf(48, 56, 68)
        val sel = sizes.indexOf(prefs.iconDp).let { if (it < 0) 1 else it }
        box.addView(Ui.segmented(this, listOf("صغير", "وسط", "كبير"), sel) { i ->
            prefs.iconDp = sizes[i]
            HubService.start(this, HubService.ACTION_REBUILD)
        }, Ui.lp(Ui.MATCH, Ui.WRAP))
        box.addView(Ui.label(this,
            "تكدر تحط شعارك الخاص: صورتين carplay.png و androidauto.png بمجلد Android/data/$packageName/files/icons",
            13f, Ui.SUB), Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(10) })
        return box
    }

    // ---------------------------------------------------------------- cards

    private fun permissionsCard(): View {
        val box = card("الصلاحيات")
        // Same list and wording as the floating "permission missing" warning (Perms.kt).
        for (p in Perms.all(this)) {
            val ok = Perms.granted(this, p)
            val kind = if (p.required) "لازمة" else "مستحسنة"
            val sub = if (ok) "مفعّلة · ${p.why}" else "$kind · ${p.why}"
            row(box, p.title, sub, null, status(ok), if (ok) null else action("فعّل") { request(p) })
        }
        return box
    }

    private fun appsCard(): View {
        val box = card("البرامج")
        appRow(box, "الخريطة (الشاشة الرئيسية)", prefs.mapPackage, AppPickerActivity.MAP)
        // CarPlay and Android Auto are fixed to DiPlay / DiAuto (the user's choice): shown, not changeable.
        appRow(box, "CarPlay (للآيفون)", prefs.carplayPackage, AppPickerActivity.CARPLAY, fixed = true)
        appRow(box, "Android Auto (للأندرويد)", prefs.autoPackage, AppPickerActivity.AUTO, fixed = true)
        appRow(box, "البرنامج اللي يم الخريطة بالتقسيم", prefs.splitPackage, AppPickerActivity.SPLIT)
        // The CarPlay / Android Auto apps that come inside this app.
        val needed = Bundled.needed(this).map { it.pkg }.toSet()
        for (app in Bundled.apps) {
            val ok = app.pkg !in needed
            val update = !ok && Bundled.isUpdate(this, app)
            row(box, app.title, if (ok) "منصّب" else if (update) "أكو نسخة أحدث جوّه البرنامج" else "يجي ويه البرنامج، بعده ما منصّب",
                null, status(ok), if (ok) null else action(if (update) "حدّث" else "نصّب") { Bundled.install(this, app) })
        }
        if (Bundled.oldAutoLeft(this)) {
            row(box, "DiAuto القديم", "ياخذ التلفون قبل ZMD Auto، لازم ينحذف", null, status(false),
                action("احذف") { Bundled.removeOldAuto(this) })
        }
        return box
    }

    private fun appRow(box: LinearLayout, title: String, pkg: String?, target: String, fixed: Boolean = false) {
        val sub = when {
            pkg.isNullOrEmpty() && target == AppPickerActivity.MAP -> "الخريطة المدمجة"
            pkg.isNullOrEmpty() -> "ما مختار"
            pkg == packageName -> "مدمج داخل ZMD Car (ما يحتاج تنصيب)" // Android Auto is built in
            Apps.isInstalled(this, pkg) -> Apps.label(this, pkg)
            else -> "مو منصّب ($pkg)"
        }
        row(box, title, sub, Apps.icon(this, pkg), null, if (fixed) null else action("غيّر") { pick(target) })
    }

    private fun modesCard(): View {
        val box = card("طريقة الاتصال")
        box.addView(Ui.label(this, "الهب يفتح البرنامج بس من يتصل التلفون بالطريقة اللي تختارها", 14f, Ui.SUB),
            Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(8) })
        val modes = listOf(LinkMode.CABLE, LinkMode.WIRELESS, LinkMode.BOTH)
        for (t in PhoneType.values()) {
            box.addView(Ui.label(this, HubService.titleOf(t), 16f, bold = true),
                Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8); bottomMargin = dp(6) })
            box.addView(Ui.segmented(this, listOf("كيبل", "لاسلكي", "الاثنين"), modes.indexOf(prefs.mode(t))) { i ->
                prefs.setMode(t, modes[i])
                HubService.instance?.onModeChanged()
            }, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        return box
    }

    private fun generalCard(): View {
        val box = card("عام")
        val isHome = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName == packageName
        row(box, "ZMD Car كشاشة رئيسية (لانچر)",
            if (isHome) "مفعّل: زر البيت يرجعك للخريطة" else "زر البيت يرجعك لخريطتنا بدل شاشة السيارة",
            null, status(isHome), if (isHome) null else action("فعّل") {
                val i = Intent(Settings.ACTION_HOME_SETTINGS)
                if (runCatching { startActivity(i) }.isFailure) open(Intent(Settings.ACTION_SETTINGS))
            })
        row(box, "يشتغل وحده من تشتغل السيارة", "ويفتح الخريطة", null, null,
            switch(prefs.startOnBoot) { prefs.startOnBoot = it })
        row(box, "يرجع للخريطة من ينفصل التلفون", null, null, null,
            switch(prefs.backToMap) { prefs.backToMap = it })
        return box
    }

    private fun phonesCard(): View {
        val box = card("التلفونات المحفوظة (بلوتوث)")
        val phones = prefs.phones()
        if (phones.isEmpty()) {
            box.addView(Ui.label(this, "بعد ما انحفظ أي تلفون. أول مرة يتصل تلفون جديد يسألك شنو نوعه.", 14f, Ui.SUB))
        }
        for (p in phones) {
            val type = when (p.type) {
                PhoneType.IPHONE -> "آيفون ← CarPlay"
                PhoneType.ANDROID -> "أندرويد ← Android Auto"
                null -> "مو تلفون (يتجاهله)"
            }
            row(box, p.name, type, null, null, action("امسح") {
                prefs.removePhone(p.mac)
                render()
            })
        }
        return box
    }

    private fun testCard(): View {
        val box = card("تجربة بدون تلفون")
        box.addView(Ui.label(this, "تمثّل اتصال تلفون حتى تشوف الأيقونات والمراحل تشتغل", 14f, Ui.SUB),
            Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(8) })
        fun sim(text: String, t: PhoneType, cable: Boolean) = Ui.button(this, text) {
            HubService.start(this, HubService.ACTION_SIM) {
                putExtra(HubService.EXTRA_TYPE, t.name)
                putExtra(HubService.EXTRA_CABLE, cable)
            }
        }
        val r1 = Ui.hbox(this)
        r1.addView(sim("آيفون كيبل", PhoneType.IPHONE, true), Ui.lp(0, Ui.WRAP, 1f).apply { marginEnd = dp(8) })
        r1.addView(sim("آيفون لاسلكي", PhoneType.IPHONE, false), Ui.lp(0, Ui.WRAP, 1f))
        box.addView(r1, Ui.lp(Ui.MATCH, Ui.WRAP))
        val r2 = Ui.hbox(this)
        r2.addView(sim("أندرويد كيبل", PhoneType.ANDROID, true), Ui.lp(0, Ui.WRAP, 1f).apply { marginEnd = dp(8) })
        r2.addView(sim("أندرويد لاسلكي", PhoneType.ANDROID, false), Ui.lp(0, Ui.WRAP, 1f))
        box.addView(r2, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8) })
        box.addView(Ui.button(this, "افصل الكل") {
            HubService.start(this, HubService.ACTION_SIM) { putExtra(HubService.EXTRA_CLEAR, true) }
        }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8) })
        return box
    }

    // ---------------------------------------------------------------- building blocks

    private fun cardLp() = Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(16) }

    private fun card(title: String): LinearLayout = Ui.vbox(this).apply {
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        background = Ui.glass(this@MainActivity, 22f)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        addView(Ui.label(this@MainActivity, title, 19f, bold = true),
            Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(8) })
    }

    /** One line in a card: optional icon, title + subtitle, optional status mark and action on the far side. */
    private fun row(box: LinearLayout, title: String, sub: String?, icon: Drawable?, mark: View?, end: View?) {
        val r = Ui.hbox(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(0, dp(6), 0, dp(6))
        }
        if (icon != null) {
            r.addView(ImageView(this).apply { setImageDrawable(icon) },
                LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        }
        if (mark != null) r.addView(mark, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
        val texts = Ui.vbox(this)
        texts.addView(Ui.label(this, title, 16f, bold = true))
        if (!sub.isNullOrEmpty()) texts.addView(Ui.label(this, sub, 14f, Ui.SUB))
        r.addView(texts, Ui.lp(0, Ui.WRAP, 1f))
        if (end != null) r.addView(end, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = dp(10) })
        box.addView(r, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    private fun status(ok: Boolean): View = ImageView(this).apply {
        setImageResource(if (ok) R.drawable.ic_check else R.drawable.ic_close)
        setColorFilter(if (ok) Ui.OK else Ui.BAD)
    }

    private fun action(text: String, onClick: () -> Unit): View = Ui.button(this, text, onClick = onClick)

    private fun switch(on: Boolean, onChange: (Boolean) -> Unit): View = Switch(this).apply {
        isChecked = on
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(Ui.WHITE, 0xFFD0DCE8.toInt()))
        trackTintList = ColorStateList(states, intArrayOf(Ui.theme(this@MainActivity).accent, 0x66FFFFFF))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun pick(target: String) {
        startActivity(Intent(this, AppPickerActivity::class.java).putExtra(AppPickerActivity.TARGET, target))
    }

    private fun open(i: Intent) {
        if (runCatching { startActivity(i) }.isFailure) {
            // Some car screens hide these settings pages; fall back to this app's info page.
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            if (runCatching { startActivity(fallback) }.isFailure) toast("الشاشة ما بيها هالإعداد")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    @Suppress("unused")
    private val sdk = Build.VERSION.SDK_INT

    private companion object {
        const val REQ_RUNTIME = 41
        val STEP_TITLES = listOf("الصلاحيات", "التلفون", "الخريطة")
        /** Where ZMD Car's source is offered (AGPL-3.0, because of the built-in Android Auto engine). */
        const val SOURCE_URL = "github.com/zaid936/zmd-car"
    }
}

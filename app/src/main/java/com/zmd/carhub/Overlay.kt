package com.zmd.carhub

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** One line of the floating connection indicator. */
class Step(val text: String, val state: Int) {
    companion object {
        const val ACTIVE = 1
        const val DONE = 2
        const val ERROR = 3
    }
}

/**
 * Everything the hub draws on top of other apps: the floating icon bar, the small panel that
 * opens from a grey icon, the "new phone" question, and the connection-stage indicator.
 */
class Overlay(private val svc: HubService) {
    private val ctx: Context = svc
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs get() = svc.prefs
    private val handler = Handler(Looper.getMainLooper())

    private var bar: LinearLayout? = null
    private var barLp: WindowManager.LayoutParams? = null
    private var icCarplay: ImageView? = null
    private var icAuto: ImageView? = null
    private var icSplit: ImageView? = null
    private var batteryChip: TextView? = null
    private var batteryText: String? = null

    /** BYD battery line on the bar, e.g. «🔋 78% · 320 كم» (null = hide). */
    fun setBattery(text: String?) {
        batteryText = text
        batteryChip?.apply {
            this.text = text.orEmpty()
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }
    private var storeDot: View? = null
    private var badgeOn = false
    private var panel: View? = null
    private var steps: View? = null
    private val customLogos = HashMap<String, Bitmap?>()
    private val hideStepsRun = Runnable { removeSteps() }
    private val hidePanelRun = Runnable { dismissPanel() }

    fun canDraw(): Boolean = Settings.canDrawOverlays(ctx)

    // ---------------------------------------------------------------- bar

    fun show() {
        if (bar != null || !canDraw()) return
        val size = Ui.dp(ctx, prefs.iconDp)
        val pad = Ui.dp(ctx, 7)
        val b = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.floatingGlass(ctx, 24f)
            setPadding(pad, pad, pad, pad)
        }
        customLogos.clear()
        // Long-press = the menu (cable / wireless, settings, and for Android Auto «اختار التلفون»),
        // also while the phone is connected (then a tap opens it straight away).
        icCarplay = icon(b, R.drawable.ic_carplay, size, onLongPress = { showPanel(PhoneType.IPHONE) }) { svc.onIconTap(PhoneType.IPHONE) }
        icAuto = icon(b, R.drawable.ic_auto, size, onLongPress = { showPanel(PhoneType.ANDROID) }) { svc.onIconTap(PhoneType.ANDROID) }
        val line = View(ctx).apply { setBackgroundColor(0x4DFFFFFF) }
        b.addView(line, LinearLayout.LayoutParams(Ui.dp(ctx, 1), size * 3 / 5).apply {
            val m = Ui.dp(ctx, 6)
            setMargins(m, 0, m, 0)
        })
        icon(b, R.drawable.ic_map, size, onLongPress = { svc.openMapPicker() }) { svc.openMap() }
        // Split = the app on screen now + one the user picks (the map only when the map is on screen).
        icSplit = icon(b, R.drawable.ic_split, size) { svc.toggleSplit(withCurrent = true) }
        storeIcon(b, size) { svc.openStore() }
        // BYD only: battery % and electric range (hidden until the car tells us; see Byd.kt).
        batteryChip = Ui.label(ctx, "", 14f, Ui.WHITE, bold = true).apply {
            visibility = if (batteryText.isNullOrEmpty()) View.GONE else View.VISIBLE
            text = batteryText.orEmpty()
            setPadding(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 4), 0)
        }
        b.addView(batteryChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        b.setOnTouchListener(Touch(onTap = {}))

        // Fixed in the top-right corner (agreed with the user): it does not move.
        val m = Ui.dp(ctx, 16)
        val lp = baseLp().apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            x = m
            y = m
        }
        try {
            wm.addView(b, lp)
        } catch (e: Exception) {
            return
        }
        bar = b
        barLp = lp
        applyBarShown()
        refresh()
    }

    fun hide() {
        dismissPanel()
        removeSteps()
        removeEdge()
        hidePermissionAlert()
        bar?.let { runCatching { wm.removeView(it) } }
        bar = null
        barLp = null
    }

    // ---------------------------------------------------------------- bar hidden behind CarPlay

    /** false while CarPlay / Android Auto fills the screen; a swipe from the right edge brings tools. */
    private var barShown = true
    private var edge: View? = null
    private var edgePanel: View? = null
    private val hideEdgePanelRun = Runnable { dismissEdgePanel() }

    fun setBarShown(shown: Boolean) {
        barShown = shown
        applyBarShown()
    }

    private fun applyBarShown() {
        bar?.visibility = if (barShown) View.VISIBLE else View.GONE
        if (barShown) {
            dismissEdgePanel()
        } else {
            dismissPanel()
            hidePermissionAlert()
        }
        // The edge-swipe tools (map + split) stay available over every app, not only receivers.
        addEdge()
    }

    /** A thin invisible strip on the right edge. Swiping inwards from it opens the edge tools. */
    private fun addEdge() {
        if (edge != null || !canDraw()) return
        val dm = ctx.resources.displayMetrics
        val strip = View(ctx)
        var downX = 0f
        var fired = false
        strip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    fired = false
                }
                MotionEvent.ACTION_MOVE -> if (!fired && downX - e.rawX > Ui.dp(ctx, 24)) {
                    fired = true
                    showEdgePanel()
                }
            }
            true
        }
        val lp = baseLp().apply {
            width = Ui.dp(ctx, 14)
            height = (dm.heightPixels * 0.6f).toInt()
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            x = 0
            y = 0
        }
        try {
            wm.addView(strip, lp)
            edge = strip
        } catch (_: Exception) {
        }
    }

    private fun removeEdge() {
        edge?.let { runCatching { wm.removeView(it) } }
        edge = null
    }

    /** The tools that slide in from the edge: back to the map, and split screen. */
    private fun showEdgePanel() {
        dismissEdgePanel()
        if (!canDraw()) return
        val size = Ui.dp(ctx, prefs.iconDp)
        val pad = Ui.dp(ctx, 8)
        val col = Ui.vbox(ctx).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.floatingGlass(ctx, 24f, opaque = true)
            setPadding(pad, pad, pad, pad)
        }
        fun tool(res: Int, text: String, on: Boolean, action: () -> Unit) {
            val item = Ui.vbox(ctx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                minimumWidth = Ui.dp(ctx, 88) // room for the whole label, e.g. «إلغاء التقسيم»
                background = Ui.ripple(ctx, if (on) Ui.solid(ctx, Ui.GLASS_HI, 16f) else null, 16f)
                setPadding(pad, pad, pad, pad)
                isClickable = true
                setOnClickListener {
                    dismissEdgePanel()
                    action()
                }
            }
            item.addView(ImageView(ctx).apply {
                setImageResource(res)
                setColorFilter(Ui.WHITE)
            }, LinearLayout.LayoutParams(size * 3 / 5, size * 3 / 5))
            item.addView(Ui.label(ctx, text, 13f, Ui.WHITE, true).apply {
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                gravity = Gravity.CENTER
            }, Ui.lp(Ui.MATCH, Ui.WRAP))
            col.addView(item, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.dp(ctx, 4) })
        }
        tool(R.drawable.ic_map, "الخريطة", false) { svc.openMap() }
        tool(R.drawable.ic_split, if (svc.splitOn) "إلغاء التقسيم" else "تقسيم", svc.splitOn) {
            svc.toggleSplit(withCurrent = true)
        }
        val lp = baseLp().apply {
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            x = Ui.dp(ctx, 12)
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        col.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) dismissEdgePanel()
            false
        }
        try {
            wm.addView(col, lp)
        } catch (_: Exception) {
            return
        }
        edgePanel = col
        handler.postDelayed(hideEdgePanelRun, 8000)
    }

    private fun dismissEdgePanel() {
        handler.removeCallbacks(hideEdgePanelRun)
        edgePanel?.let { runCatching { wm.removeView(it) } }
        edgePanel = null
    }

    /** Re-creates the bar, e.g. after the icon size changed. */
    fun rebuild() {
        hide()
        show()
    }

    /** Coloured icon = that phone type is connected; dim white = not connected. */
    fun refresh() {
        paint(icCarplay, PhoneType.IPHONE, R.drawable.ic_carplay, "carplay.png", Ui.CARPLAY)
        paint(icAuto, PhoneType.ANDROID, R.drawable.ic_auto, "androidauto.png", Ui.AUTO)
        icSplit?.background = if (svc.splitOn) Ui.solid(ctx, Ui.GLASS_HI, 16f) else null
    }

    private fun paint(v: ImageView?, type: PhoneType, res: Int, custom: String, colors: IntArray) {
        v ?: return
        val on = svc.isConnected(type)
        val logo = customLogos.getOrPut(custom) {
            // The user's own logo image, if they saved one next to the app's files.
            val f = File(ctx.getExternalFilesDir("icons"), custom)
            if (f.isFile) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
        }
        // Otherwise the receiver app's own icon, exactly as installed (DiPlay / DiAuto).
        val appIcon = if (logo == null) Apps.icon(ctx, prefs.pkg(type)) else null
        if (logo != null || appIcon != null) {
            if (logo != null) v.setImageBitmap(logo) else v.setImageDrawable(appIcon)
            v.background = null
            if (on) {
                v.clearColorFilter()
                v.imageAlpha = 255
            } else {
                // Grey = not connected: same logo, colour removed.
                v.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                v.imageAlpha = 140
            }
        } else {
            v.setImageResource(res)
            v.setColorFilter(Ui.WHITE)
            v.background = if (on) Ui.tile(ctx, colors, 16f) else null
            v.imageAlpha = if (on) 255 else 115
        }
    }

    private fun icon(parent: LinearLayout, res: Int, size: Int, onLongPress: (() -> Unit)? = null, onTap: () -> Unit): ImageView {
        val v = ImageView(ctx).apply {
            setImageResource(res)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val p = size / 5
            setPadding(p, p, p, p)
            setColorFilter(Ui.WHITE)
        }
        val lp = LinearLayout.LayoutParams(size, size)
        val g = Ui.dp(ctx, 3)
        lp.setMargins(g, 0, g, 0)
        parent.addView(v, lp)
        v.setOnTouchListener(Touch(onTap, onLongPress))
        return v
    }

    /** The store icon, with a small amber dot that appears when app updates are waiting. */
    private fun storeIcon(parent: LinearLayout, size: Int, onTap: () -> Unit) {
        val frame = FrameLayout(ctx)
        val img = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_store)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val p = size / 5
            setPadding(p, p, p, p)
            setColorFilter(Ui.WHITE)
        }
        frame.addView(img, FrameLayout.LayoutParams(size, size))
        val dotSize = Ui.dp(ctx, 9)
        val dot = View(ctx).apply {
            background = Ui.solid(ctx, 0xFFFFB300.toInt(), 9f)
            visibility = if (badgeOn) View.VISIBLE else View.GONE
        }
        frame.addView(dot, FrameLayout.LayoutParams(dotSize, dotSize).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            topMargin = Ui.dp(ctx, 5)
            rightMargin = Ui.dp(ctx, 5)
        })
        val lp = LinearLayout.LayoutParams(size, size)
        val g = Ui.dp(ctx, 3)
        lp.setMargins(g, 0, g, 0)
        parent.addView(frame, lp)
        frame.setOnTouchListener(Touch(onTap))
        storeDot = dot
    }

    /** Show or hide the "updates waiting" dot on the store icon; remembered across rebuilds. */
    fun setStoreBadge(show: Boolean) {
        badgeOn = show
        storeDot?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun baseLp(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }

    private fun clamp(lp: WindowManager.LayoutParams, w: Int, h: Int) {
        val dm = ctx.resources.displayMetrics
        lp.x = lp.x.coerceIn(0, max(0, dm.widthPixels - w))
        lp.y = lp.y.coerceIn(0, max(0, dm.heightPixels - h))
    }

    /** Tap and long-press (opens settings) on the bar and its icons. The bar does not move. */
    private inner class Touch(
        private val onTap: () -> Unit,
        private val onLongPress: (() -> Unit)? = null
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private var longFired = false
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val longPress = Runnable {
            if (!moved) {
                longFired = true
                if (onLongPress != null) onLongPress.invoke() else svc.openSettings()
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                    longFired = false
                    v.alpha = 0.7f
                    handler.postDelayed(longPress, 700)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop) {
                        moved = true
                        handler.removeCallbacks(longPress)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    v.alpha = 1f
                    if (!moved && !longFired && e.actionMasked == MotionEvent.ACTION_UP) onTap()
                }
            }
            return true
        }
    }

    // ---------------------------------------------------------------- panels

    /** Opens under a grey icon: pick cable / wireless / both, or open the app by hand. */
    fun showPanel(type: PhoneType) {
        val title = HubService.titleOf(type)
        val pkg = prefs.pkg(type)
        val box = card()
        box.addView(Ui.label(ctx, if (svc.isConnected(type)) "$title متصل" else "$title غير متصل", 19f, bold = true))
        box.addView(Ui.label(ctx, "شلون تريده يتصل؟", 14f, Ui.SUB), Ui.lp(Ui.MATCH, Ui.WRAP).apply {
            topMargin = Ui.dp(ctx, 4)
            bottomMargin = Ui.dp(ctx, 10)
        })
        val modes = listOf(LinkMode.CABLE, LinkMode.WIRELESS, LinkMode.BOTH)
        box.addView(
            Ui.segmented(ctx, listOf("كيبل", "لاسلكي", "الاثنين"), modes.indexOf(prefs.mode(type))) { i ->
                prefs.setMode(type, modes[i])
                svc.onModeChanged()
                handler.removeCallbacks(hidePanelRun)
                handler.postDelayed(hidePanelRun, 12000)
            },
            Ui.lp(Ui.MATCH, Ui.WRAP)
        )
        val bottom = Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(ctx, 12) }
        if (Apps.isInstalled(ctx, pkg)) {
            val row = Ui.hbox(ctx)
            row.addView(Ui.button(ctx, "افتح ${Apps.label(ctx, pkg)}", primary = true) {
                dismissPanel()
                svc.open(type, null)
            }, Ui.lp(0, Ui.WRAP, 1f).apply { marginEnd = Ui.dp(ctx, 8) })
            row.addView(Ui.button(ctx, "إعداداته") {
                dismissPanel()
                // ZMD Auto has no icon of its own: its settings screen is opened by name.
                if (pkg == ZmdAuto.PKG) ZmdAuto.openSettings(ctx) else Apps.openSettingsOf(ctx, pkg)
            }, Ui.lp(0, Ui.WRAP, 1f))
            box.addView(row, bottom)
        } else {
            box.addView(Ui.label(ctx, "برنامج $title مو منصّب على الشاشة", 14f, Ui.BAD), bottom)
        }
        // Android Auto built in: connect wirelessly to a phone the driver picks by hand (for when it
        // isn't recognised by itself, or another paired phone should be used).
        if (type == PhoneType.ANDROID && pkg == ZmdAuto.PKG) {
            box.addView(Ui.button(ctx, "اختار التلفون يدوي (لاسلكي)") {
                dismissPanel()
                showAutoPhonePicker()
            }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(ctx, 8) })
            // Self mode: the Android Auto app on this screen, without a phone.
            box.addView(Ui.button(ctx, "Android Auto من الشاشة نفسها (بلا تلفون)") {
                dismissPanel()
                svc.startAutoSelf()
            }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(ctx, 8) })
        }
        showCard(box, Ui.dp(ctx, 340), underBar = true, timeoutMs = 12000)
    }

    /** The phones paired with this screen over Bluetooth: tap one and Android Auto connects to it wirelessly. */
    fun showAutoPhonePicker() {
        val box = card()
        box.addView(Ui.label(ctx, "اختار التلفون", 19f, bold = true))
        box.addView(Ui.label(ctx, "Android Auto يتصل بيه لاسلكي (بلوتوث ثم واي فاي)", 14f, Ui.SUB),
            Ui.lp(Ui.MATCH, Ui.WRAP).apply {
                topMargin = Ui.dp(ctx, 4)
                bottomMargin = Ui.dp(ctx, 10)
            })
        val phones = svc.pairedPhones()
        when {
            phones == null -> box.addView(Ui.label(ctx,
                "اسمح لـ ZMD Car بـ «البلوتوث / الأجهزة القريبة» من صفحة الصلاحيات حتى يشوف التلفونات", 14f, Ui.BAD))
            phones.isEmpty() -> box.addView(Ui.label(ctx,
                "ماكو تلفون مربوط بالبلوتوث ويا الشاشة. اربط تلفونك من إعدادات البلوتوث بالشاشة أول", 14f, Ui.BAD))
            else -> for ((mac, name) in phones.take(6)) {
                box.addView(Ui.button(ctx, name, primary = true) {
                    dismissPanel()
                    svc.connectAutoTo(mac, name)
                }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(ctx, 6) })
            }
        }
        showCard(box, Ui.dp(ctx, 380), underBar = true, timeoutMs = 20000)
    }

    /** First time an unknown phone connects by Bluetooth: ask once, then remember. */
    fun askPhoneType(name: String, onPick: (PhoneType?) -> Unit) {
        val box = card()
        box.addView(Ui.label(ctx, "تلفون جديد اتصل بالبلوتوث", 19f, bold = true))
        box.addView(Ui.label(ctx, name, 15f, Ui.SUB), Ui.lp(Ui.MATCH, Ui.WRAP).apply {
            topMargin = Ui.dp(ctx, 4)
            bottomMargin = Ui.dp(ctx, 12)
        })
        val row = Ui.hbox(ctx)
        fun choice(text: String, primary: Boolean, type: PhoneType?) {
            val lp = Ui.lp(0, Ui.WRAP, 1f)
            val g = Ui.dp(ctx, 4)
            lp.setMargins(g, 0, g, 0)
            row.addView(Ui.button(ctx, text, primary) {
                dismissPanel()
                onPick(type)
            }, lp)
        }
        choice("آيفون", true, PhoneType.IPHONE)
        choice("أندرويد", true, PhoneType.ANDROID)
        choice("مو تلفون", false, null)
        box.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
        showCard(box, Ui.dp(ctx, 440), underBar = false, timeoutMs = 25000)
    }

    private fun card(): LinearLayout = Ui.vbox(ctx).apply {
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        background = Ui.floatingGlass(ctx, 22f)
        val p = Ui.dp(ctx, 16)
        setPadding(p, p, p, p)
    }

    private fun showCard(box: View, width: Int, underBar: Boolean, timeoutMs: Long) {
        dismissPanel()
        if (!canDraw()) return
        val dm = ctx.resources.displayMetrics
        val gap = Ui.dp(ctx, 10)
        val w = width.coerceAtMost(dm.widthPixels - gap * 2)
        val lp = baseLp().apply {
            this.width = w
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        box.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED
        )
        val b = bar
        if (underBar && b != null && b.visibility == View.VISIBLE) {
            val at = IntArray(2)
            b.getLocationOnScreen(at)
            lp.x = (at[0] + b.width - w).coerceIn(gap, max(gap, dm.widthPixels - w - gap))
            lp.y = at[1] + b.height + gap
            if (lp.y + box.measuredHeight > dm.heightPixels - gap) {
                lp.y = max(gap, at[1] - box.measuredHeight - gap)
            }
        } else {
            lp.x = (dm.widthPixels - w) / 2
            lp.y = Ui.dp(ctx, 90)
        }
        box.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) dismissPanel()
            false
        }
        try {
            wm.addView(box, lp)
        } catch (e: Exception) {
            return
        }
        panel = box
        handler.postDelayed(hidePanelRun, timeoutMs)
    }

    fun dismissPanel() {
        handler.removeCallbacks(hidePanelRun)
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    // ---------------------------------------------------------------- connection stages

    fun showSteps(list: List<Step>) {
        // Problems the driver sees go into the «دعم فني» report too.
        list.filter { it.state == Step.ERROR }.forEach { Support.note(it.text) }
        handler.removeCallbacks(hideStepsRun)
        removeSteps()
        if (!canDraw()) return
        // Wide car screen: stages side by side. Narrow screen: one stage per line.
        val wide = ctx.resources.configuration.screenWidthDp >= 720
        val box = (if (wide) Ui.hbox(ctx) else Ui.vbox(ctx)).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = Ui.floatingGlass(ctx, 22f, opaque = true)
            setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 12), Ui.dp(ctx, 20), Ui.dp(ctx, 12))
        }
        val mark = Ui.dp(ctx, 20)
        list.forEachIndexed { i, s ->
            if (i > 0 && wide) {
                val sep = View(ctx).apply { setBackgroundColor(0x4DFFFFFF) }
                box.addView(sep, LinearLayout.LayoutParams(Ui.dp(ctx, 1), mark).apply {
                    val m = Ui.dp(ctx, 14)
                    setMargins(m, 0, m, 0)
                    gravity = Gravity.CENTER_VERTICAL
                })
            }
            val row = Ui.hbox(ctx).apply {
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = Gravity.CENTER_VERTICAL
            }
            box.addView(row, Ui.lp(Ui.WRAP, Ui.WRAP).apply {
                if (i > 0 && !wide) topMargin = Ui.dp(ctx, 8)
                gravity = if (wide) Gravity.CENTER_VERTICAL else Gravity.START
            })
            val markLp = LinearLayout.LayoutParams(mark, mark).apply { marginEnd = Ui.dp(ctx, 8) }
            when (s.state) {
                Step.ACTIVE -> row.addView(ProgressBar(ctx).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(Ui.WHITE)
                }, markLp)
                Step.ERROR -> row.addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_close)
                    setColorFilter(Ui.BAD)
                }, markLp)
                else -> row.addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(Ui.OK)
                }, markLp)
            }
            row.addView(Ui.label(ctx, s.text, 17f, if (s.state == Step.ERROR) Ui.BAD else Ui.WHITE, true))
        }
        val lp = baseLp().apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            // Bottom centre: the bar lives at the top, so the two never cover each other.
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = Ui.dp(ctx, 28)
        }
        try {
            wm.addView(box, lp)
        } catch (e: Exception) {
            return
        }
        steps = box
    }

    fun hideStepsLater(ms: Long) {
        handler.removeCallbacks(hideStepsRun)
        handler.postDelayed(hideStepsRun, ms)
    }

    private fun removeSteps() {
        steps?.let { runCatching { wm.removeView(it) } }
        steps = null
    }

    // ---------------------------------------------------------------- permission warning

    private var permAlert: View? = null
    private var permAlertText: String? = null

    /** A persistent floating warning that a permission is off, with a button to go fix it. */
    fun showPermissionAlert(title: String, sub: String, onFix: () -> Unit) {
        if (!canDraw() || !barShown) return // only on the map/home, never over setup / CarPlay / sheets
        if (permAlert != null && permAlertText == title) return // already showing this one
        hidePermissionAlert()
        permAlertText = title
        val card = Ui.hbox(ctx).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.floatingGlass(ctx, 20f)
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 12), Ui.dp(ctx, 16), Ui.dp(ctx, 12))
        }
        card.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Ui.BAD)
        }, LinearLayout.LayoutParams(Ui.dp(ctx, 24), Ui.dp(ctx, 24)).apply { marginEnd = Ui.dp(ctx, 10) })
        val texts = Ui.vbox(ctx)
        texts.addView(Ui.label(ctx, title, 16f, Ui.WHITE, true))
        if (sub.isNotEmpty()) texts.addView(Ui.label(ctx, sub, 13f, Ui.SUB))
        card.addView(texts, Ui.lp(0, Ui.WRAP, 1f))
        card.addView(Ui.button(ctx, "فعّل", primary = true) { onFix() },
            Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = Ui.dp(ctx, 12) })
        card.addView(Ui.button(ctx, "لاحقاً") { svc.snoozePermAlert() },
            Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = Ui.dp(ctx, 8) })
        // Right under the bar, lined up with its right edge: never on top of the bar, and the left
        // side (the map's search) stays free.
        val dm = ctx.resources.displayMetrics
        val m = Ui.dp(ctx, 16)
        val barH = bar?.height?.takeIf { it > 0 } ?: (Ui.dp(ctx, prefs.iconDp) + Ui.dp(ctx, 14))
        val w = (dm.widthPixels * 0.5f).toInt()
            .coerceIn(Ui.dp(ctx, 380), Ui.dp(ctx, 560))
            .coerceAtMost(dm.widthPixels - 2 * m)
        val lp = baseLp().apply {
            width = w
            gravity = Gravity.TOP or Gravity.RIGHT
            x = m
            y = m + barH + Ui.dp(ctx, 10)
        }
        try {
            wm.addView(card, lp)
        } catch (e: Exception) {
            return
        }
        permAlert = card
    }

    fun hidePermissionAlert() {
        permAlert?.let { runCatching { wm.removeView(it) } }
        permAlert = null
        permAlertText = null
    }
}

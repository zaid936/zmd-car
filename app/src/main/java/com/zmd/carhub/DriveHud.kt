package com.zmd.carhub

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What the driver reads while driving, over the map:
 *  - the road's speed limit, as a round road sign (from the offline road network; on the
 *    announced radar expressways also «حسب المسار 80 · 100 · 120»),
 *  - the car's own speed, red when it is over the limit,
 *  - a card when a speed camera is ahead, with a voice warning («كامرة سرعة بعد 400 متر،
 *    السرعة 80») and «خفف السرعة» if still too fast close to it. The bell on the card mutes and
 *    unmutes these voice warnings; the choice is kept.
 *
 * Self-contained: the map adds [view] to its layout and calls [update] with every position.
 * [speak] is how the map talks (its NavVoice, which already respects the map's own mute).
 */
class DriveHud(private val ctx: Context, private val speak: (String) -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val sp = ctx.getSharedPreferences("drive_hud", Context.MODE_PRIVATE)

    /** Voice warnings for cameras (the bell). */
    var cameraSound: Boolean
        get() = sp.getBoolean("camera_sound", true)
        set(v) {
            sp.edit().putBoolean("camera_sound", v).apply()
            paintBell()
        }

    val view: LinearLayout = Ui.vbox(ctx).apply { gravity = Gravity.BOTTOM }

    private val camCard: LinearLayout
    private val camIcon: ImageView
    private val camTitle: TextView
    private val camDist: TextView
    private val camSign: TextView
    private val bell: ImageView
    private val limitSign: TextView
    private val laneNote: TextView
    private val speedBox: LinearLayout
    private val speedText: TextView

    // What is known about the road under the car.
    private var roadLimit: Int? = null
    private var zone: SpeedCameras.Zone? = null
    private var lastRoadAt = 0L
    private var lastRoadLat = 0.0
    private var lastRoadLon = 0.0
    @Volatile private var roadBusy = false

    // Camera warnings already given: camera id -> when.
    private val warned = HashMap<String, Long>()
    private var slowSaid: String? = null
    private var camLimit: Int? = null
    private var kmh = 0
    private var shown = true

    init {
        val c = ctx
        // ---- camera card
        camCard = Ui.hbox(c).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.floatingGlass(c, 24f, opaque = true)
            setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10))
            visibility = View.GONE
        }
        camIcon = ImageView(c).apply {
            setImageDrawable(MapIcons.icon(CAMERA, 0xFFFF5A5F.toInt(), 2.2f))
        }
        camCard.addView(camIcon, LinearLayout.LayoutParams(Ui.dp(c, 30), Ui.dp(c, 30)).apply { marginEnd = Ui.dp(c, 10) })
        val texts = Ui.vbox(c)
        camTitle = Ui.label(c, "", 17f, bold = true)
        camDist = Ui.label(c, "", 15f, Ui.SUB)
        texts.addView(camTitle)
        texts.addView(camDist)
        camCard.addView(texts, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginEnd = Ui.dp(c, 10) })
        camSign = sign(c, 44, 17f)
        camCard.addView(camSign, LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)).apply { marginEnd = Ui.dp(c, 6) })
        bell = ImageView(c).apply {
            val p = Ui.dp(c, 9)
            setPadding(p, p, p, p)
            background = Ui.ripple(c, null, 22f)
            isClickable = true
            contentDescription = "صوت تنبيه الكامرات"
            setOnClickListener { cameraSound = !cameraSound }
        }
        camCard.addView(bell, LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)))
        paintBell()
        view.addView(camCard, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { bottomMargin = Ui.dp(c, 10) })

        // ---- lane note for the radar expressways
        laneNote = Ui.label(c, "", 13f, Ui.WHITE, true).apply {
            background = Ui.floatingGlass(c, 14f, opaque = true)
            setPadding(Ui.dp(c, 10), Ui.dp(c, 5), Ui.dp(c, 10), Ui.dp(c, 5))
            visibility = View.GONE
        }
        view.addView(laneNote, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { bottomMargin = Ui.dp(c, 8) })

        // ---- speed limit sign + the car's speed
        val row = Ui.hbox(c).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
        }
        limitSign = sign(c, 66, 26f).apply { visibility = View.GONE }
        row.addView(limitSign, LinearLayout.LayoutParams(Ui.dp(c, 66), Ui.dp(c, 66)).apply { marginEnd = Ui.dp(c, 10) })
        speedBox = Ui.vbox(c).apply {
            gravity = Gravity.CENTER
            background = Ui.floatingGlass(c, 38f, opaque = true)
        }
        speedText = Ui.label(c, "0", 26f, bold = true).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER }
        speedBox.addView(speedText)
        speedBox.addView(Ui.label(c, "كم/س", 11f, Ui.SUB).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER })
        row.addView(speedBox, LinearLayout.LayoutParams(Ui.dp(c, 76), Ui.dp(c, 76)))
        view.addView(row, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))

        // The camera list: the one inside the app at once, a newer one from the store once a day.
        bg.execute { runCatching { SpeedCameras.refreshIfDue(ctx) } }
    }

    /** Call with every position (real GPS or the simulated drive). */
    fun update(loc: Location) {
        val speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        kmh = (speedMps * 3.6).roundToInt()
        // GPS directions are noise when crawling.
        val bearing = if (loc.hasBearing() && speedMps > 2.0) loc.bearing.toDouble() else null
        lookupRoad(loc, bearing)
        val hit = if (bearing != null) runCatching {
            SpeedCameras.ahead(loc.latitude, loc.longitude, bearing, speedMps)
        }.getOrNull() else null
        showCamera(hit)
        paint()
    }

    /** Hide everything (filming mode), or show it again. */
    fun setVisible(on: Boolean) {
        shown = on
        view.visibility = if (on) View.VISIBLE else View.GONE
    }

    fun release() {
        ui.removeCallbacksAndMessages(null)
        bg.shutdownNow()
    }

    // ------------------------------------------------------------------ the road

    private fun lookupRoad(loc: Location, bearing: Double?) {
        val now = SystemClock.elapsedRealtime()
        if (roadBusy || now - lastRoadAt < 1500) return
        if (lastRoadAt != 0L && MapSearch.dist(lastRoadLat, lastRoadLon, loc.latitude, loc.longitude) < 15) return
        roadBusy = true
        lastRoadAt = now
        lastRoadLat = loc.latitude
        lastRoadLon = loc.longitude
        val lat = loc.latitude
        val lon = loc.longitude
        bg.execute {
            val road = runCatching { MapRoute.roadAt(ctx, lat, lon, bearing) }.getOrNull()
            val z = runCatching { SpeedCameras.zoneFor(road?.name, lat, lon) }.getOrNull()
            ui.post {
                roadBusy = false
                roadLimit = road?.limitKmh
                zone = z
                paint()
            }
        }
    }

    // ------------------------------------------------------------------ cameras

    private fun showCamera(hit: SpeedCameras.Ahead?) {
        if (hit == null) {
            camCard.visibility = View.GONE
            camLimit = null
            return
        }
        val c = hit.camera
        camLimit = c.speed
        camTitle.text = what(c)
        camDist.text = NavText.distance(hit.distM)
        camSign.text = c.speed?.toString() ?: ""
        camSign.visibility = if (c.speed != null) View.VISIBLE else View.GONE
        camCard.visibility = if (shown) View.VISIBLE else View.GONE

        val now = SystemClock.elapsedRealtime()
        val last = warned[c.id]
        if (last == null || now - last > 10 * 60 * 1000L) {
            warned[c.id] = now
            if (warned.size > 200) warned.entries.removeAll { now - it.value > 30 * 60 * 1000L }
            slowSaid = null
            if (cameraSound) speak(sentence(c, hit.distM))
        }
        val limit = c.speed
        if (limit != null && hit.distM < 300 && kmh > limit + 5 && slowSaid != c.id) {
            slowSaid = c.id
            if (cameraSound) speak("خفف السرعة، السرعة $limit")
        }
    }

    private fun what(c: SpeedCameras.Camera): String = when {
        c.reported -> "بلاغ كامرة"
        c.type == "traffic" -> "كامرة مراقبة"
        else -> "كامرة سرعة"
    }

    private fun sentence(c: SpeedCameras.Camera, d: Double): String {
        val far = if (d >= 950) "كيلومتر" else "${max(50, (d / 50).roundToInt() * 50)} متر"
        return if (c.speed != null) "${what(c)} بعد $far، السرعة ${c.speed}" else "${what(c)} بعد $far"
    }

    // ------------------------------------------------------------------ painting

    /** The limit that applies here: a camera just ahead, else the road's sign, else the radar road's top lane. */
    private fun limitNow(): Int? = camLimit ?: roadLimit ?: zone?.max

    private fun paint() {
        val limit = limitNow()
        limitSign.text = limit?.toString() ?: ""
        limitSign.visibility = if (limit != null) View.VISIBLE else View.GONE
        val z = zone
        if (z != null && z.lanes.isNotEmpty()) {
            laneNote.text = "حسب المسار: " + z.lanes.distinct().joinToString(" · ")
            laneNote.visibility = View.VISIBLE
        } else {
            laneNote.visibility = View.GONE
        }
        speedText.text = kmh.toString()
        val over = limit != null && kmh > limit + 3
        speedBox.background = if (over) Ui.solid(ctx, 0xEED32F2F.toInt(), 38f) else Ui.floatingGlass(ctx, 38f, opaque = true)
    }

    private fun paintBell() {
        bell.setImageDrawable(MapIcons.icon(if (cameraSound) BELL else BELL_OFF, Ui.WHITE, 2f))
    }

    /** A round speed-limit road sign: white disc, red ring, black number. */
    private fun sign(c: Context, sizeDp: Int, textSp: Float): TextView = TextView(c).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
        setTextColor(0xFF111111.toInt())
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
            setStroke(Ui.dp(c, sizeDp * 0.1f), 0xFFD32F2F.toInt())
        }
        elevation = Ui.dp(c, 4).toFloat()
    }

    private companion object {
        // Feather icons (MIT): camera, bell, bell-off.
        const val CAMERA = "M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2zM12 17a4 4 0 1 0 0-8 4 4 0 0 0 0 8z"
        const val BELL = "M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9M13.73 21a2 2 0 0 1-3.46 0"
        const val BELL_OFF = "M13.73 21a2 2 0 0 1-3.46 0M18.63 13A17.89 17.89 0 0 1 18 8M6.26 6.26A5.86 5.86 0 0 0 6 8c0 7-3 9-3 9h14M18 8a6 6 0 0 0-9.33-5M1 1l22 22"
    }
}

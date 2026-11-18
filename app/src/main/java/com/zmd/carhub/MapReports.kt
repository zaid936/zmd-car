package com.zmd.carhub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Road reports shared by every ZMD Car screen (like Waze): potholes, speed bumps, accidents,
 * police, traffic, hazards, speed cameras, closed roads. Kept on our own server (Cloudflare);
 * the last list is saved on the screen, so they still show (and warn) without internet.
 */
object MapReports {
    private const val API = "https://zmd-store.kc5zqt2pcd.workers.dev/api/reports"

    class Type(val id: String, val name: String, val color: Int, val glyph: String, val askAfterPass: Boolean)

    val types = listOf(
        Type("pothole", "حفرة", 0xFF8D5A3B.toInt(), "🕳️", true),
        Type("bump", "طسة", 0xFFF2B01E.toInt(), "", true),
        Type("accident", "حادث", 0xFFE5484D.toInt(), "💥", true),
        Type("police", "شرطة", 0xFF2F6FE0.toInt(), "👮", true),
        Type("traffic", "زحام", 0xFFF07A1A.toInt(), "🚗", false),
        Type("hazard", "خطر بالطريق", 0xFFE8A317.toInt(), "⚠️", true),
        Type("camera", "كاميرا سرعة", 0xFF8E4EC6.toInt(), "📷", true),
        Type("closure", "طريق مسدود", 0xFFC0262D.toInt(), "⛔", true),
    )

    fun type(id: String): Type = types.firstOrNull { it.id == id } ?: types[5]

    class Report(val id: Long, val type: String, val lat: Double, val lon: Double, val heading: Double?, val yes: Int)

    @Volatile var reports: List<Report> = emptyList()
        private set
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var app: Context
    private var loadedAt = 0L
    private var loadedLat = 0.0
    private var loadedLon = 0.0
    private val warned = HashSet<Long>()
    private val asked = HashSet<Long>()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        // Last list seen, for use without internet.
        runCatching { reports = parse(JSONObject(cacheFile().readText()).getJSONArray("reports")) }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun cacheFile() = File(MapData.dir(app), "reports.json")

    private fun device(): String {
        val sp = app.getSharedPreferences("map", Context.MODE_PRIVATE)
        return sp.getString("device_id", null) ?: UUID.randomUUID().toString().also { sp.edit().putString("device_id", it).apply() }
    }

    /** Asks the server for the reports around (about 25 km), at most once a minute or after 5 km. */
    fun refresh(lat: Double, lon: Double, online: Boolean, force: Boolean = false) {
        if (!online) return
        val now = SystemClock.elapsedRealtime()
        val moved = distM(lat, lon, loadedLat, loadedLon)
        if (!force && now - loadedAt < 60_000 && moved < 5000) return
        loadedAt = now; loadedLat = lat; loadedLon = lon
        io.execute {
            runCatching {
                val d = 0.25
                val url = "$API?bbox=${lon - d},${lat - d},${lon + d},${lat + d}"
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val text = r.body!!.string()
                    reports = parse(JSONObject(text).getJSONArray("reports"))
                    runCatching { cacheFile().writeText(text) }
                    main.post { listeners.forEach { it() } }
                }
            }
        }
    }

    /** Sends a new report at the car's position. [done] gets an Arabic message to show. */
    fun add(type: String, lat: Double, lon: Double, heading: Float?, done: (String) -> Unit) {
        io.execute {
            val msg = runCatching {
                val body = JSONObject().put("type", type).put("lat", lat).put("lon", lon).put("device", device())
                if (heading != null) body.put("heading", heading.toDouble())
                http.newCall(Request.Builder().url(API).post(body.toString().toRequestBody(JSON)).build()).execute().use { r ->
                    if (r.isSuccessful) "شكراً، انضاف البلاغ ويشوفه الكل ✓"
                    else runCatching { JSONObject(r.body!!.string()).optString("error") }.getOrNull()?.ifEmpty { null } ?: "ما انضاف (${r.code})"
                }
            }.getOrElse { "ما انضاف: يحتاج إنترنت" }
            loadedAt = 0
            refresh(lat, lon, true, force = true)
            main.post { done(msg) }
        }
    }

    fun vote(id: Long, still: Boolean) {
        io.execute {
            runCatching {
                val body = JSONObject().put("id", id).put("still", still).put("device", device())
                http.newCall(Request.Builder().url("$API/vote").post(body.toString().toRequestBody(JSON)).build()).execute().close()
            }
            if (!still) {
                reports = reports.filter { it.id != id }
                main.post { listeners.forEach { it() } }
            }
        }
    }

    /**
     * The next report on the road ahead (within [rangeM], in front of the car), not warned about yet.
     * Without a heading, anything within 150 m counts.
     */
    fun ahead(lat: Double, lon: Double, bearing: Float?, rangeM: Double = 350.0): Pair<Report, Double>? {
        var best: Pair<Report, Double>? = null
        for (r in reports) {
            if (r.id in warned) continue
            val d = distM(lat, lon, r.lat, r.lon)
            if (d > rangeM) continue
            if (bearing != null) {
                val to = Math.toDegrees(atan2((r.lon - lon) * cos(Math.toRadians(lat)), r.lat - lat))
                var diff = abs(((to - bearing) % 360 + 540) % 360 - 180)
                if (diff > 35) continue
                // A report made facing the other way is for the other side of the road.
                r.heading?.let { h -> diff = abs(((h - bearing) % 360 + 540) % 360 - 180); if (diff > 100) continue }
            } else if (d > 150) continue
            if (best == null || d < best.second) best = r to d
        }
        best?.let { warned += it.first.id }
        return best
    }

    /** A report the car has just driven past (so the driver can say whether it is still there). */
    fun justPassed(lat: Double, lon: Double): Report? {
        for (r in reports) {
            if (r.id in asked || r.id !in warned || !type(r.type).askAfterPass) continue
            if (distM(lat, lon, r.lat, r.lon) < 30) { asked += r.id; return r }
        }
        return null
    }

    fun geoJson(): String {
        val fs = JSONArray()
        for (r in reports) {
            fs.put(JSONObject()
                .put("type", "Feature")
                .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(r.lon).put(r.lat)))
                .put("properties", JSONObject().put("icon", "rep-${r.type}").put("id", r.id)))
        }
        return JSONObject().put("type", "FeatureCollection").put("features", fs).toString()
    }

    private fun parse(a: JSONArray): List<Report> = List(a.length()) { i ->
        val o = a.getJSONObject(i)
        Report(o.getLong("id"), o.getString("type"), o.getDouble("lat"), o.getDouble("lon"),
            if (o.isNull("heading")) null else o.optDouble("heading"), o.optInt("yes", 1))
    }

    fun distM(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val dy = (la2 - la1) * 111_320.0
        val dx = (lo2 - lo1) * 111_320.0 * cos(Math.toRadians(la1))
        return sqrt(dx * dx + dy * dy)
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // ------------------------------------------------------------------ icons

    /** Map marker for a report: a coloured badge with its symbol, a white rim and a soft glow. */
    fun icon(ctx: Context, t: Type, sizeDp: Float = 40f): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val s = sizeDp * d
        val pad = s * 0.3f
        val side = (s + pad * 2).toInt()
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = side / 2f
        val cy = side / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = (t.color and 0x00FFFFFF) or 0x66000000
        p.maskFilter = BlurMaskFilter(s * 0.22f, BlurMaskFilter.Blur.NORMAL)
        c.drawCircle(cx, cy, s * 0.5f, p)
        p.maskFilter = null
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(cx, cy, s * 0.5f, p)
        p.color = t.color
        c.drawCircle(cx, cy, s * 0.43f, p)
        if (t.id == "bump") {
            // A speed bump: a hump on a road line.
            p.color = 0xFF1B1E24.toInt()
            p.strokeWidth = s * 0.07f
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            c.drawLine(cx - s * 0.3f, cy + s * 0.12f, cx + s * 0.3f, cy + s * 0.12f, p)
            p.style = Paint.Style.FILL
            c.drawPath(Path().apply {
                moveTo(cx - s * 0.22f, cy + s * 0.12f)
                quadTo(cx, cy - s * 0.32f, cx + s * 0.22f, cy + s * 0.12f)
                close()
            }, p)
        } else {
            p.color = 0xFFFFFFFF.toInt()   // emoji draw in colour; plain symbols in white
            p.textAlign = Paint.Align.CENTER
            p.textSize = s * 0.48f
            p.typeface = Typeface.DEFAULT_BOLD
            val y = cy - (p.descent() + p.ascent()) / 2
            c.drawText(t.glyph, cx, y, p)
        }
        return bmp
    }

    /** Small rounded button face for the report menu. */
    fun menuIcon(ctx: Context, t: Type): Bitmap = icon(ctx, t, 56f)

    @Suppress("unused")
    private fun rr(l: Float, t: Float, r: Float, b: Float) = RectF(l, t, r, b)
}

package com.zmd.carhub

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.cos

/**
 * Speed cameras and radar-monitored roads all over Iraq, kept on the screen so the warnings work
 * with no internet. The list (cameras.json) ships inside the app and refreshes itself once a day
 * when online from the store's "maps" release, where a weekly job rebuilds it from OpenStreetMap
 * and the hand-kept official list (A:\byd\MapData\cameras\cameras-official.json). Cameras that
 * drivers report in the app ([MapReports], type "camera") are added on top.
 */
object SpeedCameras {
    const val URL = "https://github.com/zaid936/zmd-car-store/releases/download/maps/cameras.json"
    private const val FILE = "cameras.json"
    private const val ASSET = "map/cameras.json"
    private const val DAY_MS = 24L * 3600 * 1000

    /**
     * One camera. [type] is "speed" (radar) or "traffic" (watching a junction); [speed] is its
     * limit in km/h when known; [dirs] are the compass directions of the traffic it watches
     * (empty = both ways / unknown).
     */
    class Camera(
        val id: String, val lat: Double, val lon: Double, val type: String, val speed: Int?,
        val dirs: DoubleArray, val name: String, val reported: Boolean = false,
    )

    /** A road announced as radar-monitored, matched by name inside [bbox] (south, west, north, east). */
    class Zone(val id: String, val name: String, val match: List<String>, val bbox: DoubleArray, val lanes: List<Int>, val max: Int?)

    /** A camera ahead of the car and how far it is. */
    class Ahead(val camera: Camera, val distM: Double)

    private class Data(val version: String, val cams: List<Camera>, val zones: List<Zone>)

    @Volatile private var data = Data("", emptyList(), emptyList())
    @Volatile private var loaded = false

    /** Date of the list in use, e.g. "2026-11-17". */
    val version: String get() = data.version
    val count: Int get() = data.cams.size

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    // ------------------------------------------------------------------ loading

    /** Reads the newest list on the screen: the downloaded one or the one inside the app. */
    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        val saved = runCatching { File(MapData.dir(ctx), FILE).takeIf { it.isFile }?.readText() }.getOrNull()?.let { parse(it) }
        val bundled = runCatching { ctx.assets.open(ASSET).bufferedReader().use { it.readText() } }.getOrNull()?.let { parse(it) }
        val best = listOfNotNull(saved, bundled).maxByOrNull { it.version }
        if (best != null) data = best
        loaded = true
    }

    private fun parse(text: String): Data? = runCatching {
        val o = JSONObject(text)
        val list = ArrayList<Camera>()
        val arr = o.getJSONArray("cameras")
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val d = c.optJSONArray("dirs")
            val dirs = if (d == null) DoubleArray(0) else DoubleArray(d.length()) { d.getDouble(it) }
            val speed = if (c.isNull("speed")) null else c.optInt("speed").takeIf { it in 5..160 }
            list += Camera(c.getString("id"), c.getDouble("lat"), c.getDouble("lon"), c.optString("type", "speed"), speed, dirs, c.optString("name"))
        }
        val zones = ArrayList<Zone>()
        val za = o.optJSONArray("zones")
        if (za != null) {
            for (i in 0 until za.length()) {
                val z = za.getJSONObject(i)
                val m = z.getJSONArray("match")
                val b = z.getJSONArray("bbox")
                val l = z.optJSONArray("lanes")
                zones += Zone(
                    z.getString("id"), z.optString("name"),
                    (0 until m.length()).map { m.getString(it) },
                    DoubleArray(4) { b.getDouble(it) },
                    if (l == null) emptyList() else (0 until l.length()).map { l.getInt(it) },
                    if (z.isNull("max")) null else z.optInt("max"),
                )
            }
        }
        Data(o.optString("version"), list, zones)
    }.getOrNull()?.takeIf { it.cams.isNotEmpty() || it.zones.isNotEmpty() }

    /**
     * Once a day when online, fetches the list from the store; keeps it if it is newer than the
     * one in use. Background thread. A failed try is repeated a few hours later.
     */
    fun refreshIfDue(ctx: Context) {
        load(ctx)
        val sp = ctx.getSharedPreferences("cameras", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - sp.getLong("checked", 0L) < DAY_MS || !MapTiles.online) return
        val text = runCatching {
            http.newCall(Request.Builder().url(URL).header("Cache-Control", "no-cache").build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull()
        val fresh = text?.let { parse(it) }
        if (text == null || fresh == null) {
            sp.edit().putLong("checked", now - DAY_MS + 3 * 3600 * 1000L).apply()
            return
        }
        sp.edit().putLong("checked", now).apply()
        if (fresh.version > data.version) {
            runCatching { File(MapData.dir(ctx), FILE).writeText(text) }
            data = fresh
        }
    }

    // ------------------------------------------------------------------ questions

    /** Every camera, with the ones drivers reported in the app (unless one is already mapped there). */
    private fun all(): List<Camera> {
        val base = data.cams
        val reports = runCatching { MapReports.reports.filter { it.type == "camera" } }.getOrDefault(emptyList())
        if (reports.isEmpty()) return base
        val extra = reports.filter { r -> base.none { MapSearch.dist(it.lat, it.lon, r.lat, r.lon) < 40 } }.map { r ->
            Camera("rep-${r.id}", r.lat, r.lon, "speed", null, r.heading?.let { doubleArrayOf(it) } ?: DoubleArray(0), "", reported = true)
        }
        return base + extra
    }

    /** Cameras inside a box (south, west, north, east), for drawing them on the map. */
    fun inBox(south: Double, west: Double, north: Double, east: Double): List<Camera> =
        all().filter { it.lat in south..north && it.lon in west..east }

    /** Cameras within [radiusM] metres of a point. */
    fun near(lat: Double, lon: Double, radiusM: Double): List<Camera> {
        val dLat = radiusM / 110540.0
        val dLon = radiusM / (111320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        return all().filter {
            it.lat in (lat - dLat)..(lat + dLat) && it.lon in (lon - dLon)..(lon + dLon) &&
                MapSearch.dist(lat, lon, it.lat, it.lon) <= radiusM
        }
    }

    /**
     * The nearest camera in front of a car at [lat],[lon] driving towards [bearing] (degrees) at
     * [speedMps]: inside a narrow cone ahead, and watching this direction of travel. Looks further
     * ahead the faster the car goes (20 s of driving, 350 m to 1.2 km).
     */
    fun ahead(lat: Double, lon: Double, bearing: Double, speedMps: Double): Ahead? {
        val reach = (speedMps * 20).coerceIn(350.0, 1200.0)
        var best: Ahead? = null
        for (c in near(lat, lon, reach)) {
            val d = MapSearch.dist(lat, lon, c.lat, c.lon)
            if (d > 35) {
                val toCam = compass(lat, lon, c.lat, c.lon)
                if (MapRoute.turn(bearing, toCam) > 30) continue // off to the side, or already behind
            }
            if (c.dirs.isNotEmpty() && c.dirs.none { MapRoute.turn(bearing, it) <= 60 }) continue // watches the other side
            if (best == null || d < best.distM) best = Ahead(c, d)
        }
        return best
    }

    /** The announced radar road the car is on, if any ([roadName] from [MapRoute.roadAt]). */
    fun zoneFor(roadName: String?, lat: Double, lon: Double): Zone? {
        if (roadName.isNullOrBlank()) return null
        return data.zones.firstOrNull { z ->
            lat >= z.bbox[0] && lon >= z.bbox[1] && lat <= z.bbox[2] && lon <= z.bbox[3] && z.match.any { roadName.contains(it) }
        }
    }

    private fun compass(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val p1 = Math.toRadians(la1)
        val p2 = Math.toRadians(la2)
        val dl = Math.toRadians(lo2 - lo1)
        val y = Math.sin(dl) * Math.cos(p2)
        val x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl)
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
    }
}

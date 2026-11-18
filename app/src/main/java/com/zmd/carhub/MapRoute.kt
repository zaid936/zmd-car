package com.zmd.carhub

import android.content.Context
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.ResponsePath
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import com.graphhopper.routing.ev.MaxSpeed
import com.graphhopper.routing.util.DefaultEdgeFilter
import com.graphhopper.storage.index.LocationIndexTree
import com.graphhopper.storage.index.QueryResult
import com.graphhopper.util.FetchMode
import com.graphhopper.util.RoundaboutInstruction
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** One manoeuvre of a route: what to do at point [first], then [distM] metres along [street]. */
class RouteStep(val sign: Int, val street: String, val distM: Double, val timeMs: Long, val first: Int, val exit: Int = 0)

/** A calculated route. [pts] is lat, lon, lat, lon... */
class Route(val pts: DoubleArray, val distM: Double, val timeMs: Long, val steps: List<RouteStep>, val offline: Boolean) {
    val size: Int get() = pts.size / 2
    fun lat(i: Int): Double = pts[i * 2]
    fun lon(i: Int): Double = pts[i * 2 + 1]

    /**
     * The road (or, on a long trip, the town) that sets this route apart from the others offered
     * with it, for labels like «عن طريق شارع فلسطين» / «عن طريق الكوت». Empty when unknown.
     */
    var via: String = ""

    /** Metres from the start to each point. */
    val cum: DoubleArray = DoubleArray(pts.size / 2).also { c ->
        for (i in 1 until c.size) c[i] = c[i - 1] + MapSearch.dist(lat(i - 1), lon(i - 1), lat(i), lon(i))
    }
    val lengthM: Double get() = if (cum.isEmpty()) 0.0 else cum[cum.size - 1]

    /** The point [m] metres along the route, as lat, lon. */
    fun pointAt(m: Double): DoubleArray {
        if (size < 2) return doubleArrayOf(lat(0), lon(0))
        val d = m.coerceIn(0.0, lengthM)
        var lo = 0
        var hi = size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= d) lo = mid else hi = mid
        }
        val len = cum[hi] - cum[lo]
        val t = if (len <= 0.0) 0.0 else (d - cum[lo]) / len
        return doubleArrayOf(lat(lo) + (lat(hi) - lat(lo)) * t, lon(lo) + (lon(hi) - lon(lo)) * t)
    }
}

/**
 * Directions. With the offline pack: our own road network of Iraq (GraphHopper 1.0, car, fastest
 * route, pre-computed so even Zakho → Faw takes a blink). Without it: the OpenStreetMap routing
 * server (OSRM) over the internet. Both give the same [Route].
 */
object MapRoute {
    // Turn signs (the values GraphHopper uses).
    const val U_TURN = -98
    const val U_TURN_LEFT = -8
    const val KEEP_LEFT = -7
    const val LEAVE_ROUNDABOUT = -6
    const val SHARP_LEFT = -3
    const val LEFT = -2
    const val SLIGHT_LEFT = -1
    const val STRAIGHT = 0
    const val SLIGHT_RIGHT = 1
    const val RIGHT = 2
    const val SHARP_RIGHT = 3
    const val FINISH = 4
    const val VIA = 5
    const val ROUNDABOUT = 6
    const val KEEP_RIGHT = 7
    const val U_TURN_RIGHT = 8

    /** Extra time allowed for finding the other ways, on top of the main route. */
    private const val ALTERNATIVES_BUDGET_MS = 3000L

    private var hopper: GraphHopper? = null
    private var hopperPath: String? = null
    private var broken = false

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    fun offlineReady(ctx: Context): Boolean = MapData.routingDir(ctx) != null && !broken

    @Synchronized
    private fun engine(ctx: Context): GraphHopper? {
        val dir = MapData.routingDir(ctx) ?: return null
        hopper?.let { if (hopperPath == dir.absolutePath) return it }
        if (broken) return null
        close()
        return try {
            // Memory-mapped and read-only: the 140 MB road network stays on disk, not in the app's memory.
            val h = GraphHopper().forMobile()
            h.setProfiles(Profile("car").setVehicle("car").setWeighting("fastest"))
            h.getCHPreparationHandler().setCHProfiles(CHProfile("car"))
            h.getCHPreparationHandler().setDisablingAllowed(true)
            h.setAllowWrites(false)
            if (h.load(dir.absolutePath)) {
                hopper = h
                hopperPath = dir.absolutePath
                h
            } else {
                runCatching { h.close() }
                null
            }
        } catch (t: Throwable) {
            broken = true
            null
        }
    }

    /** Loads the road network ahead of the first route (background thread). */
    fun warmUp(ctx: Context) {
        engine(ctx)
    }

    @Synchronized
    fun close() {
        runCatching { hopper?.close() }
        hopper = null
        hopperPath = null
        broken = false
    }

    /**
     * The one best (fastest) route; used for re-routing while driving. Blocking: call from a
     * background thread. [heading] is the way the car is moving (degrees from north) so a new
     * route does not ask for an impossible U-turn; null when standing still.
     */
    fun route(ctx: Context, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, heading: Double?): Route {
        var offlineError: String? = null
        if (offlineReady(ctx)) {
            try {
                val h = engine(ctx)
                if (h != null) return toRoute(offlinePath(h, fromLat, fromLon, toLat, toLon, heading))
            } catch (e: Exception) {
                offlineError = e.message ?: "no route"
            }
        }
        if (MapTiles.online) {
            try {
                return onlineRoutes(fromLat, fromLon, toLat, toLon, heading, alternatives = false).first()
            } catch (e: Exception) {
                if (offlineError == null) throw IOException("ما كدرنا نحسب الطريق من النت")
            }
        }
        throw IOException(noRoute(offlineError))
    }

    /**
     * Up to [max] different ways to the destination for the driver to choose from, sorted by
     * distance: the first is the shortest. Blocking: call from a background thread; the other
     * ways take up to about three seconds more than [route]. When there is no real choice the
     * list holds just one route. Throws like [route] only when there is no route at all.
     */
    fun routes(ctx: Context, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, heading: Double?, max: Int = 3): List<Route> {
        val found = ArrayList<Route>()
        var offlineError: String? = null
        if (offlineReady(ctx)) {
            try {
                val h = engine(ctx)
                if (h != null) {
                    val main = offlinePath(h, fromLat, fromLon, toLat, toLon, heading)
                    found += toRoute(main)
                    if (max > 1) {
                        // Other ways through a via point on a main road beside the first route.
                        val others = runCatching { RouteAlternatives.find(h, main, max - 1, ALTERNATIVES_BUDGET_MS) }.getOrNull()
                        others?.forEach { p -> runCatching { toRoute(p) }.getOrNull()?.let { found += it } }
                    }
                }
            } catch (e: Exception) {
                if (found.isEmpty()) offlineError = e.message ?: "no route"
            }
        }
        if (found.isEmpty() && MapTiles.online) {
            try {
                found += distinct(onlineRoutes(fromLat, fromLon, toLat, toLon, heading, alternatives = max > 1), max)
            } catch (e: Exception) {
                if (offlineError == null) throw IOException("ما كدرنا نحسب الطريق من النت")
            }
        }
        if (found.isEmpty()) throw IOException(noRoute(offlineError))
        val sorted = found.sortedBy { it.distM }
        runCatching { label(ctx, sorted) }
        return sorted
    }

    /** The road under the car: its name and its posted speed limit (km/h; null when the map has none). */
    class RoadHere(val name: String, val limitKmh: Int?)

    /**
     * Which road the car is on and its speed limit, from the offline road network (OpenStreetMap
     * maxspeed, kept per direction). [bearing] picks the right road at junctions and the right
     * direction of a one-way limit. Blocking but quick (a few milliseconds): background thread.
     */
    fun roadAt(ctx: Context, lat: Double, lon: Double, bearing: Double?): RoadHere? {
        val h = engine(ctx) ?: return null
        return runCatching {
            val em = h.encodingManager
            val filter = DefaultEdgeFilter.allEdges(em.getEncoder("car"))
            val index = h.locationIndex
            val near: List<QueryResult> = (index as? LocationIndexTree)?.findNClosest(lat, lon, filter, 35.0)
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(index.findClosest(lat, lon, filter))
            var best: QueryResult? = null
            var bestScore = Double.MAX_VALUE
            var bestForward = true
            for (qr in near) {
                if (!qr.isValid || qr.queryDistance > 60) continue
                val edge = qr.closestEdge
                val geo = edge.fetchWayGeometry(FetchMode.ALL)
                if (geo.size() < 2) continue
                val i = qr.wayIndex.coerceIn(0, geo.size() - 2)
                val along = compass(geo.getLat(i), geo.getLon(i), geo.getLat(i + 1), geo.getLon(i + 1))
                var score = qr.queryDistance
                var forward = true
                if (bearing != null) {
                    val ahead = turn(bearing, along)
                    val back = turn(bearing, (along + 180) % 360)
                    forward = ahead <= back
                    val off = minOf(ahead, back)
                    score += off * 0.6 + if (off > 60) 100 else 0
                }
                if (score < bestScore) {
                    bestScore = score
                    best = qr
                    bestForward = forward
                }
            }
            val edge = (best ?: return@runCatching null).closestEdge
            var limit: Int? = null
            if (em.hasEncodedValue(MaxSpeed.KEY)) {
                val enc = em.getDecimalEncodedValue(MaxSpeed.KEY)
                val v = if (bestForward) edge.get(enc) else edge.getReverse(enc)
                // Unknown is stored as infinity, "no limit" as 150: neither is a sign to show.
                if (v.isFinite() && v > 0 && v < 150) limit = Math.round(v).toInt()
            }
            RoadHere(cleanName(edge.name), limit)
        }.getOrNull()
    }

    private fun compass(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val p1 = Math.toRadians(la1)
        val p2 = Math.toRadians(la2)
        val dl = Math.toRadians(lo2 - lo1)
        val y = Math.sin(dl) * Math.cos(p2)
        val x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl)
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
    }

    /** Smallest angle between two compass directions, 0..180. */
    fun turn(a: Double, b: Double): Double {
        val d = Math.abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }

    private fun noRoute(offlineError: String?): String =
        if (offlineError != null) "ما لكينا طريق بالسيارة لهالمكان"
        else "حساب الطريق يحتاج إنترنت، أو نزّل خريطة العراق حتى يشتغل بدون نت"

    // ------------------------------------------------------------------ offline (GraphHopper)

    private fun offlinePath(h: GraphHopper, la1: Double, lo1: Double, la2: Double, lo2: Double, heading: Double?): ResponsePath {
        fun ask(withHeading: Boolean): com.graphhopper.GHResponse {
            val req = GHRequest(la1, lo1, la2, lo2).setProfile("car").setLocale(Locale.ENGLISH)
            if (withHeading && heading != null) {
                req.setHeadings(listOf(heading, Double.NaN))
                req.putHint("ch.disable", true) // the pre-computed network cannot take a heading
            }
            return h.route(req)
        }
        // A heading makes the search slower, so it is only used for nearby destinations.
        val near = MapSearch.dist(la1, lo1, la2, lo2) < 120_000
        var rsp = ask(heading != null && near)
        if (rsp.hasErrors() && heading != null && near) rsp = ask(false)
        if (rsp.hasErrors()) throw IOException(rsp.errors.firstOrNull()?.message ?: "no route")
        val p = rsp.best
        if (p.points.size() < 2) throw IOException("no route")
        return p
    }

    private fun toRoute(p: ResponsePath): Route {
        val pl = p.points
        val n = pl.size()
        if (n < 2) throw IOException("no route")
        val pts = DoubleArray(n * 2)
        for (i in 0 until n) {
            pts[i * 2] = pl.getLat(i)
            pts[i * 2 + 1] = pl.getLon(i)
        }
        val steps = ArrayList<RouteStep>()
        var first = 0
        var afterVia = false
        for (ins in p.instructions) {
            val at = first.coerceAtMost(n - 1)
            // getLength(), not the point count: a via point is listed in two instructions but is
            // one point of the route, so counting points would put later turns one point late.
            first += ins.length
            if (ins.sign == VIA) {
                afterVia = true
                continue
            }
            // Passing the via point is no manoeuvre: the "carry on" that restarts there belongs to
            // the step before it, so nothing is announced in the middle of the road.
            if (afterVia) {
                afterVia = false
                val prev = steps.lastOrNull()
                if (prev != null && ins.sign == STRAIGHT) {
                    steps[steps.size - 1] = RouteStep(
                        prev.sign, prev.street.ifEmpty { cleanName(ins.name) },
                        prev.distM + ins.distance, prev.timeMs + ins.time, prev.first, prev.exit
                    )
                    continue
                }
            }
            val exit = if (ins is RoundaboutInstruction) ins.exitNumber else 0
            steps += RouteStep(ins.sign, cleanName(ins.name), ins.distance, ins.time, at, exit)
        }
        return Route(pts, p.distance, p.time, steps, offline = true)
    }

    /** "شارع ساطع الحصري, 27" → "شارع ساطع الحصري"; a bare road number → "طريق 80". */
    private fun cleanName(raw: String?): String {
        val parts = (raw ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        val named = parts.firstOrNull { p -> p.any { it.isLetter() } && p.length > 3 }
        return named ?: "طريق ${parts[0]}"
    }

    // ------------------------------------------------------------------ choosing and naming

    /** The first route and those after it that are not much longer nor mostly the same road. */
    private fun distinct(list: List<Route>, max: Int): List<Route> {
        if (list.isEmpty()) return list
        val main = list[0]
        val kept = arrayListOf(main)
        for (r in list.drop(1)) {
            if (kept.size >= max) break
            if (r.distM > RouteAlternatives.MAX_LONGER * main.distM) continue
            val same = kept.any {
                RouteAlternatives.overlap(r.pts, it.pts) > RouteAlternatives.MAX_SHARED ||
                    RouteAlternatives.overlap(it.pts, r.pts) > RouteAlternatives.MAX_SHARED
            }
            if (!same) kept += r
        }
        return kept
    }

    /**
     * Fills [Route.via]. Looks only at the part of each route the others do not share: on a long
     * trip it names the town there («الكوت»), in town the street it mostly drives on
     * («شارع فلسطين»), each falling back to the other.
     */
    private fun label(ctx: Context, routes: List<Route>) {
        for (r in routes) {
            val others = routes.filter { it !== r }.map { RouteAlternatives.Grid(it.pts, 100.0) }
            fun unique(p: DoubleArray) = others.none { it.near(p[0], p[1], 30.0) }

            val byName = HashMap<String, Double>()
            var uniqueM = 0.0
            for ((k, s) in r.steps.withIndex()) {
                if (s.distM <= 0.0 || s.sign == FINISH) continue
                val end = r.steps.getOrNull(k + 1)?.first ?: (r.size - 1)
                val mid = r.pointAt((r.cum[s.first] + r.cum[max(end, s.first)]) / 2)
                if (others.isNotEmpty() && !unique(mid)) continue
                uniqueM += s.distM
                if (s.street.isNotEmpty()) byName[s.street] = (byName[s.street] ?: 0.0) + s.distM
            }
            val street = byName.maxByOrNull { it.value }?.takeIf { it.value >= max(400.0, 0.15 * uniqueM) }?.key

            var town: String? = null
            if (r.lengthM > 40_000 || street == null) {
                // The middle of the stretch only this route drives.
                val step = max(50.0, r.lengthM / 300)
                val marks = ArrayList<Double>()
                var m = step / 2
                while (m < r.lengthM) {
                    if (others.isEmpty() || unique(r.pointAt(m))) marks += m
                    m += step
                }
                if (marks.isNotEmpty()) {
                    val p = r.pointAt(marks[marks.size / 2])
                    town = MapSearch.nearestTown(ctx, p[0], p[1])
                }
            }
            r.via = (if (r.lengthM > 40_000) town ?: street else street ?: town) ?: ""
        }
    }

    // ------------------------------------------------------------------ online (OSRM)

    private fun onlineRoutes(la1: Double, lo1: Double, la2: Double, lo2: Double, heading: Double?, alternatives: Boolean): List<Route> {
        fun n(v: Double) = String.format(Locale.US, "%.6f", v)
        var url = "https://routing.openstreetmap.de/routed-car/route/v1/driving/${n(lo1)},${n(la1)};${n(lo2)},${n(la2)}" +
            "?overview=false&steps=true&geometries=polyline6"
        if (alternatives) url += "&alternatives=true"
        if (heading != null) url += "&bearings=${heading.toInt().mod(360)},60;"
        val text = http.newCall(Request.Builder().url(url).header("User-Agent", "ZMD-Car (personal car head unit)").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body!!.string()
        }
        val o = JSONObject(text)
        if (o.optString("code") != "Ok") throw IOException(o.optString("message", "no route"))
        val arr = o.getJSONArray("routes")
        val out = ArrayList<Route>()
        for (i in 0 until arr.length()) runCatching { parseOsrm(arr.getJSONObject(i)) }.getOrNull()?.let { out += it }
        if (out.isEmpty()) throw IOException("no route")
        return out
    }

    private fun parseOsrm(r0: JSONObject): Route {
        val legs = r0.getJSONArray("legs")
        val lat = ArrayList<Double>()
        val lon = ArrayList<Double>()
        val steps = ArrayList<RouteStep>()
        for (li in 0 until legs.length()) {
            val arr = legs.getJSONObject(li).getJSONArray("steps")
            for (si in 0 until arr.length()) {
                val s = arr.getJSONObject(si)
                val man = s.getJSONObject("maneuver")
                val type = man.optString("type")
                val mod = man.optString("modifier")
                val geo = decode6(s.optString("geometry"))
                val first = if (lat.isEmpty()) 0 else lat.size - 1
                var k = if (lat.isEmpty()) 0 else 1 // the first point repeats the previous step's last one
                while (k < geo.size / 2) {
                    lat += geo[k * 2]
                    lon += geo[k * 2 + 1]
                    k++
                }
                val sign = when (type) {
                    "arrive" -> FINISH
                    "depart" -> STRAIGHT
                    "roundabout", "rotary" -> ROUNDABOUT
                    "exit roundabout", "exit rotary" -> LEAVE_ROUNDABOUT
                    "fork", "on ramp", "off ramp", "merge" -> when {
                        mod.contains("left") -> KEEP_LEFT
                        mod.contains("right") -> KEEP_RIGHT
                        else -> STRAIGHT
                    }
                    else -> when (mod) {
                        "left" -> LEFT
                        "slight left" -> SLIGHT_LEFT
                        "sharp left" -> SHARP_LEFT
                        "right" -> RIGHT
                        "slight right" -> SLIGHT_RIGHT
                        "sharp right" -> SHARP_RIGHT
                        "uturn" -> U_TURN
                        else -> STRAIGHT
                    }
                }
                if (sign == LEAVE_ROUNDABOUT) continue
                // A step that only says the street changed its name is not worth an announcement.
                if (sign == STRAIGHT && type == "new name" && steps.isNotEmpty()) continue
                val name = s.optString("name").ifEmpty { s.optString("ref").let { if (it.isEmpty()) "" else "طريق $it" } }
                steps += RouteStep(sign, name, s.optDouble("distance", 0.0), (s.optDouble("duration", 0.0) * 1000).toLong(), first, man.optInt("exit", 0))
            }
        }
        if (lat.size < 2) throw IOException("no route")
        val pts = DoubleArray(lat.size * 2)
        for (i in lat.indices) {
            pts[i * 2] = lat[i]
            pts[i * 2 + 1] = lon[i]
        }
        return Route(pts, r0.optDouble("distance", 0.0), (r0.optDouble("duration", 0.0) * 1000).toLong(), steps, offline = false)
    }

    /** Google "encoded polyline" with 6 decimals → lat, lon, lat, lon... */
    private fun decode6(s: String): DoubleArray {
        val out = ArrayList<Double>()
        var i = 0
        var lat = 0L
        var lon = 0L
        while (i < s.length) {
            for (which in 0..1) {
                var shift = 0
                var result = 0L
                while (true) {
                    val b = s[i++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val delta = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (which == 0) lat += delta else lon += delta
            }
            out += lat / 1e6
            out += lon / 1e6
        }
        return out.toDoubleArray()
    }
}

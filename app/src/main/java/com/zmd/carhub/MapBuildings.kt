package com.zmd.carhub

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Every building of Iraq (Overture Maps footprints, with heights), for the 3D map.
 *
 * Online, the whole country is one PMTiles file next to the store (GitHub release "maps"); a PMTiles
 * file can be read piece by piece with HTTP range requests, so the screen never needs all of it.
 * Every piece the screen gets is kept for good in buildings.db, so it keeps working with no internet:
 *  - a governorate can be downloaded from the settings (only the pieces inside it, fetched in big runs);
 *  - while driving with internet, the pieces on the route (and straight ahead) are fetched before the car
 *    gets there.
 * The map asks for them through MapTiles at https://map.zmd.local/btiles/{z}/{x}/{y}.pbf.
 */
object MapBuildings {
    const val DEFAULT_URL = "https://github.com/zaid936/zmd-car-store/releases/download/maps/iraq-buildings.pmtiles"
    private const val MIN_Z = 13
    private const val MAX_Z = 14
    private val EMPTY = ByteArray(0)

    class Governorate(val id: String, val name: String, val bbox: DoubleArray, val rings: List<DoubleArray>)

    class Job(val gov: String, val done: Int, val total: Int, val finished: Boolean, val error: String? = null)

    private lateinit var app: Context
    @Volatile private var db: SQLiteDatabase? = null
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val ahead = Executors.newSingleThreadExecutor()
    private val inFlight = ConcurrentHashMap.newKeySet<Long>()
    private val listeners = CopyOnWriteArrayList<(Job) -> Unit>()
    @Volatile var job: Job? = null
        private set
    @Volatile private var cancelJob = false

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    @Synchronized
    fun init(ctx: Context) {
        if (db != null) return
        app = ctx.applicationContext
        runCatching {
            val d = SQLiteDatabase.openOrCreateDatabase(File(MapData.dir(app), "buildings.db"), null)
            d.enableWriteAheadLogging()
            d.execSQL("CREATE TABLE IF NOT EXISTS t(id INTEGER PRIMARY KEY, d BLOB NOT NULL)")
            d.execSQL("CREATE TABLE IF NOT EXISTS gov(id TEXT PRIMARY KEY, done INTEGER NOT NULL)")
            db = d
        }
    }

    fun addListener(l: (Job) -> Unit) { listeners += l }
    fun removeListener(l: (Job) -> Unit) { listeners -= l }
    private fun post(j: Job) { job = j; main.post { listeners.forEach { it(j) } } }

    // ------------------------------------------------------------------ answering the map

    /** The piece for the map: from the screen, else from the internet (then kept). null = unknown yet. */
    fun tile(z: Int, x: Int, y: Int, online: Boolean): ByteArray? {
        if (z < MIN_Z || z > MAX_Z) return EMPTY
        val id = tileId(z, x, y)
        stored(id)?.let { return it }
        if (!online) return null
        return runCatching { fetchOne(id) }.getOrNull()
    }

    fun has(z: Int, x: Int, y: Int): Boolean {
        if (z < MIN_Z || z > MAX_Z) return false
        val d = db ?: return false
        return runCatching {
            d.rawQuery("SELECT length(d) FROM t WHERE id=${tileId(z, x, y)}", null).use { it.moveToFirst() && it.getInt(0) > 0 }
        }.getOrDefault(false)
    }

    private fun stored(id: Long): ByteArray? {
        val d = db ?: return null
        return runCatching {
            d.rawQuery("SELECT d FROM t WHERE id=$id", null).use { c -> if (c.moveToFirst()) gunzipIfNeeded(c.getBlob(0)) else null }
        }.getOrNull()
    }

    private fun store(id: Long, gz: ByteArray) {
        runCatching { db?.execSQL("INSERT OR REPLACE INTO t(id,d) VALUES(?,?)", arrayOf<Any>(id, gz)) }
    }

    /** Number of kept pieces and the bytes they take. */
    fun stats(): Pair<Long, Long> {
        val d = db ?: return 0L to 0L
        return runCatching {
            d.rawQuery("SELECT COUNT(*), COALESCE(SUM(length(d)),0) FROM t", null).use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else 0L to 0L }
        }.getOrDefault(0L to 0L)
    }

    fun clear() {
        runCatching {
            db?.execSQL("DELETE FROM t")
            db?.execSQL("DELETE FROM gov")
            db?.execSQL("VACUUM")
        }
    }

    fun downloaded(govId: String): Boolean = runCatching {
        db?.rawQuery("SELECT done FROM gov WHERE id=?", arrayOf(govId))?.use { it.moveToFirst() && it.getInt(0) == 1 } ?: false
    }.getOrDefault(false)

    // ------------------------------------------------------------------ PMTiles (read over the internet)

    private class Header(
        val rootOff: Long, val rootLen: Long, val leafOff: Long, val dataOff: Long, val internalGzip: Boolean,
    )

    private class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    @Volatile private var header: Header? = null
    @Volatile private var root: List<Entry>? = null
    private val leaves = object : LinkedHashMap<Long, List<Entry>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Entry>>?) = size > 256
    }

    private fun url(): String = MapPrefs(app).buildingsUrl

    private fun range(from: Long, len: Long): ByteArray {
        val req = Request.Builder().url(url()).header("Range", "bytes=$from-${from + len - 1}").build()
        http.newCall(req).execute().use { r ->
            if (r.code != 206 && r.code != 200) throw IOException("HTTP ${r.code}")
            val b = r.body!!.bytes()
            if (r.code == 200) {
                if (b.size < from + len) throw IOException("short")
                return b.copyOfRange(from.toInt(), (from + len).toInt())
            }
            return b
        }
    }

    @Volatile private var headerFailedAt = 0L

    @Synchronized
    private fun header(): Header {
        header?.let { return it }
        // The file not reachable (no internet, or not published yet): don't ask again for 5 minutes.
        if (android.os.SystemClock.elapsedRealtime() - headerFailedAt < 300_000 && headerFailedAt != 0L) throw IOException("buildings file not reachable")
        val b = try { range(0, 16384) } catch (e: IOException) {
            headerFailedAt = android.os.SystemClock.elapsedRealtime(); throw e
        }
        if (String(b, 0, 7, Charsets.US_ASCII) != "PMTiles" || b[7].toInt() != 3) throw IOException("not pmtiles v3")
        fun u64(o: Int): Long { var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF); return v }
        val h = Header(u64(8), u64(16), u64(40), u64(56), b[97].toInt() == 2)
        header = h
        // The root directory is usually inside those first 16 KB.
        root = if (h.rootOff + h.rootLen <= b.size) parseDir(b.copyOfRange(h.rootOff.toInt(), (h.rootOff + h.rootLen).toInt()), h)
        else parseDir(range(h.rootOff, h.rootLen), h)
        return h
    }

    private fun parseDir(raw: ByteArray, h: Header): List<Entry> {
        val b = if (h.internalGzip) gunzip(raw) else raw
        var p = 0
        fun varint(): Long {
            var shift = 0; var v = 0L
            while (true) {
                val c = b[p++].toInt() and 0xFF
                v = v or ((c and 0x7F).toLong() shl shift)
                if (c and 0x80 == 0) return v
                shift += 7
            }
        }
        val n = varint().toInt()
        val ids = LongArray(n); val runs = IntArray(n); val lens = IntArray(n); val offs = LongArray(n)
        var last = 0L
        for (i in 0 until n) { last += varint(); ids[i] = last }
        for (i in 0 until n) runs[i] = varint().toInt()
        for (i in 0 until n) lens[i] = varint().toInt()
        for (i in 0 until n) {
            val v = varint()
            offs[i] = if (v == 0L && i > 0) offs[i - 1] + lens[i - 1] else v - 1
        }
        return List(n) { Entry(ids[it], offs[it], lens[it], runs[it]) }
    }

    private fun find(entries: List<Entry>, id: Long): Entry? {
        var lo = 0; var hi = entries.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = entries[mid].tileId
            if (c < id) lo = mid + 1 else if (c > id) hi = mid - 1 else return entries[mid]
        }
        if (hi >= 0) {
            val e = entries[hi]
            if (e.runLength == 0) return e      // a leaf directory covers it
            if (id - e.tileId < e.runLength) return e
        }
        return null
    }

    private fun leaf(h: Header, e: Entry): List<Entry> {
        synchronized(leaves) { leaves[e.offset]?.let { return it } }
        val dir = parseDir(range(h.leafOff + e.offset, e.length.toLong()), h)
        synchronized(leaves) { leaves[e.offset] = dir }
        return dir
    }

    /** Where piece [id] is in the file: (offset in file, length), or null when there is no building there. */
    private fun locate(id: Long): Pair<Long, Int>? {
        val h = header()
        var entries = root ?: return null
        repeat(4) {
            val e = find(entries, id) ?: return null
            if (e.runLength > 0) return (h.dataOff + e.offset) to e.length
            entries = leaf(h, e)
        }
        return null
    }

    private fun fetchOne(id: Long): ByteArray {
        val at = locate(id)
        if (at == null) { store(id, EMPTY); return EMPTY }
        val gz = range(at.first, at.second.toLong())
        store(id, gz)
        return gunzipIfNeeded(gz)
    }

    // ------------------------------------------------------------------ ahead of the car

    /** Fetches (in the background) the pieces along [route] that the screen does not have yet. */
    fun prefetchRoute(route: Route, online: Boolean) {
        if (!online || !MapPrefs(app).buildingsAlongRoute) return
        ahead.execute {
            val ids = LinkedHashSet<Long>()
            val pts = route.pts
            var i = 0
            while (i + 1 < pts.size) {
                val lat = pts[i]; val lon = pts[i + 1]
                for (z in MIN_Z..MAX_Z) {
                    val x = lonToX(lon, z); val y = latToY(lat, z)
                    // The road plus one piece either side.
                    for (dx in -1..1) for (dy in -1..1) ids += tileId(z, x + dx, y + dy)
                }
                i += 2
            }
            fetchMissing(ids.toList())
        }
    }

    /** While driving with no route: guesses the road ahead from the heading and fetches about 2 km of it. */
    @Volatile private var aheadAt = 0L
    @Volatile private var aheadLat = 0.0
    @Volatile private var aheadLon = 0.0

    fun prefetchAhead(lat: Double, lon: Double, bearingDeg: Float?, online: Boolean) {
        if (!online || db == null || !MapPrefs(app).buildingsAlongRoute) return
        // Once every 15 s or 200 m is plenty: each run covers about 2 km ahead.
        val now = android.os.SystemClock.elapsedRealtime()
        val movedM = Math.hypot((lat - aheadLat) * 111_320.0, (lon - aheadLon) * 111_320.0 * cos(Math.toRadians(lat)))
        if (now - aheadAt < 15000 && movedM < 200) return
        aheadAt = now; aheadLat = lat; aheadLon = lon
        ahead.execute {
            val ids = LinkedHashSet<Long>()
            val steps = if (bearingDeg == null) 0 else 8
            for (k in 0..steps) {
                val dist = k * 250.0
                val b = Math.toRadians((bearingDeg ?: 0f).toDouble())
                val la = lat + dist * cos(b) / 111_320.0
                val lo = lon + dist * sin(b) / (111_320.0 * cos(Math.toRadians(lat)))
                for (z in MIN_Z..MAX_Z) {
                    val x = lonToX(lo, z); val y = latToY(la, z)
                    for (dx in -1..1) for (dy in -1..1) ids += tileId(z, x + dx, y + dy)
                }
            }
            fetchMissing(ids.toList())
        }
    }

    private fun fetchMissing(ids: List<Long>) {
        val d = db ?: return
        val todo = ids.filter { id ->
            !inFlight.contains(id) && runCatching { d.rawQuery("SELECT 1 FROM t WHERE id=$id", null).use { !it.moveToFirst() } }.getOrDefault(false)
        }
        for (id in todo) {
            if (!inFlight.add(id)) continue
            try { runCatching { fetchOne(id) } } finally { inFlight.remove(id) }
        }
    }

    // ------------------------------------------------------------------ whole governorate

    private var govs: List<Governorate>? = null

    /** The 19 governorates (simplified outlines shipped in the app). */
    fun governorates(): List<Governorate> {
        govs?.let { return it }
        val arr = JSONArray(app.assets.open("map/governorates.json").bufferedReader().use { it.readText() })
        val out = ArrayList<Governorate>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val geo = JSONObject(o.getString("geo"))
            val rings = ArrayList<DoubleArray>()
            fun ring(a: JSONArray) = DoubleArray(a.length() * 2) { k -> a.getJSONArray(k / 2).getDouble(k % 2) }
            when (geo.getString("type")) {
                "Polygon" -> rings += ring(geo.getJSONArray("coordinates").getJSONArray(0))
                "MultiPolygon" -> {
                    val polys = geo.getJSONArray("coordinates")
                    for (k in 0 until polys.length()) rings += ring(polys.getJSONArray(k).getJSONArray(0))
                }
            }
            val name = o.optString("name_ar").removePrefix("محافظة ").trim()
            out += Governorate(o.optString("region").ifEmpty { "IQ-HA" }, name,
                doubleArrayOf(o.getDouble("x0"), o.getDouble("y0"), o.getDouble("x1"), o.getDouble("y1")), rings)
        }
        govs = out.sortedBy { it.name }
        return govs!!
    }

    fun governorateAt(lat: Double, lon: Double): Governorate? = governorates().firstOrNull { inside(it, lon, lat) }

    private fun inside(g: Governorate, lon: Double, lat: Double): Boolean {
        if (lon < g.bbox[0] || lon > g.bbox[2] || lat < g.bbox[1] || lat > g.bbox[3]) return false
        return g.rings.any { r ->
            var c = false
            var j = r.size - 2
            var i = 0
            while (i < r.size) {
                val xi = r[i]; val yi = r[i + 1]; val xj = r[j]; val yj = r[j + 1]
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) c = !c
                j = i; i += 2
            }
            c
        }
    }

    /**
     * Downloads every piece inside [g]: walks the file's index for that area and fetches the pieces in
     * long runs (one request for many), so a governorate takes a few minutes, not thousands of requests.
     */
    fun downloadGovernorate(g: Governorate) {
        if (job?.finished == false) return
        cancelJob = false
        post(Job(g.name, 0, 0, false))
        pool.execute {
            try {
                val h = header()
                // Every piece of the governorate, at both zoom levels.
                val wanted = ArrayList<Long>()
                for (z in MIN_Z..MAX_Z) {
                    val x0 = lonToX(g.bbox[0], z); val x1 = lonToX(g.bbox[2], z)
                    val y0 = latToY(g.bbox[3], z); val y1 = latToY(g.bbox[1], z)
                    for (x in x0..x1) for (y in y0..y1) {
                        val lon = (x + 0.5) / (1 shl z) * 360.0 - 180.0
                        val lat = Math.toDegrees(atan(sinh(PI * (1 - 2 * (y + 0.5) / (1 shl z)))))
                        if (inside(g, lon, lat)) wanted += tileId(z, x, y)
                    }
                }
                // Where each is in the file; skip the ones already on the screen.
                val d = db ?: throw IOException("no store")
                val have = HashSet<Long>()
                d.rawQuery("SELECT id FROM t", null).use { c -> while (c.moveToNext()) have += c.getLong(0) }
                val parts = ArrayList<Triple<Long, Long, Int>>() // id, offset, length
                var looked = 0
                for (id in wanted) {
                    if (cancelJob) throw IOException("cancelled")
                    looked++
                    if (looked % 400 == 0) post(Job(g.name, 0, wanted.size, false))
                    if (id in have) continue
                    val at = locate(id)
                    if (at == null) store(id, EMPTY) else parts += Triple(id, at.first, at.second)
                }
                parts.sortBy { it.second }
                val total = parts.size
                var done = 0
                // Neighbouring pieces in one request (up to 4 MB).
                var i = 0
                while (i < parts.size) {
                    if (cancelJob) throw IOException("cancelled")
                    var j = i
                    var end = parts[i].second + parts[i].third
                    while (j + 1 < parts.size && parts[j + 1].second - end < 4096 && parts[j + 1].second + parts[j + 1].third - parts[i].second < 4_000_000) {
                        j++
                        end = maxOf(end, parts[j].second + parts[j].third)
                    }
                    val blob = range(parts[i].second, end - parts[i].second)
                    d.beginTransaction()
                    try {
                        for (k in i..j) {
                            val o = (parts[k].second - parts[i].second).toInt()
                            store(parts[k].first, blob.copyOfRange(o, o + parts[k].third))
                        }
                        d.setTransactionSuccessful()
                    } finally { d.endTransaction() }
                    done += j - i + 1
                    post(Job(g.name, done, total, false))
                    i = j + 1
                }
                d.execSQL("INSERT OR REPLACE INTO gov(id,done) VALUES(?,1)", arrayOf(g.id))
                post(Job(g.name, total, total, true))
            } catch (e: Exception) {
                post(Job(g.name, 0, 0, true, if (e.message == "cancelled") "توقف" else (e.message ?: "خطأ")))
            }
        }
    }

    fun cancel() { cancelJob = true }

    // ------------------------------------------------------------------ helpers

    fun lonToX(lon: Double, z: Int): Int = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

    fun latToY(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat)
        return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }

    /** PMTiles tile id (Hilbert order inside each zoom level). */
    fun tileId(z: Int, x: Int, y: Int): Long {
        var acc = 0L
        for (t in 0 until z) acc += 1L shl (2 * t)
        val n = 1L shl z
        var xx = x.toLong(); var yy = y.toLong()
        var d = 0L
        var s = n / 2
        while (s > 0) {
            val rx = if (xx and s > 0) 1L else 0L
            val ry = if (yy and s > 0) 1L else 0L
            d += s * s * ((3 * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) { xx = s - 1 - xx; yy = s - 1 - yy }
                val t = xx; xx = yy; yy = t
            }
            s /= 2
        }
        return acc + d
    }

    private fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }

    private fun gunzipIfNeeded(b: ByteArray): ByteArray =
        if (b.size >= 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()) gunzip(b) else b

    @Suppress("unused")
    private fun gzip(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(b) }
        return out.toByteArray()
    }
}

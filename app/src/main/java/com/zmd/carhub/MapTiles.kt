package com.zmd.carhub

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Where the map gets its pieces from. The map styles ask one fixed, made-up address
 * (https://map.zmd.local/...) and this engine answers every request, in this order:
 *
 *  1. our own offline map of Iraq (the downloaded pack, an .mbtiles file),
 *  2. streets saved earlier — every tile that came from the internet is kept,
 *  3. the internet (OpenFreeMap), and the answer is saved for next time.
 *
 * So the map works with no internet wherever the pack or an earlier drive covers, and works
 * online everywhere else. Fonts are answered the same way (APK first).
 */
object MapTiles {
    const val HOST = "map.zmd.local"
    private const val UPSTREAM_INFO = "https://tiles.openfreemap.org/planet"
    private const val UPSTREAM_FONTS = "https://tiles.openfreemap.org/fonts/"
    private const val AGENT = "ZMD-Car (personal car head unit)"
    private const val REFRESH_AFTER_MS = 30L * 24 * 3600 * 1000   // a saved tile is re-read from the internet after a month
    private const val TEMPLATE_MAX_AGE_MS = 6L * 3600 * 1000
    private val EMPTY = ByteArray(0)
    private val PBF = "application/x-protobuf".toMediaType()

    private lateinit var app: Context
    private var started = false
    private val main = Handler(Looper.getMainLooper())

    private val packLock = ReentrantReadWriteLock()
    private var pack: SQLiteDatabase? = null
    /** west, south, east, north of the offline pack. */
    private var packBounds: DoubleArray? = null
    private var saved: SQLiteDatabase? = null
    private var fontAssets: Map<String, Set<String>> = emptyMap()

    @Volatile var online = true
        private set
    @Volatile private var netBlockedUntil = 0L
    private val netFails = AtomicInteger()
    private val writes = AtomicLong()
    @Volatile private var lastTemplateTry = 0L

    /** Counters for the offline-maps screen: where the tiles of this session came from. */
    val fromPack = AtomicLong()
    val fromSaved = AtomicLong()
    val fromNet = AtomicLong()

    private val upstream: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    private val prefetcher = Executors.newSingleThreadExecutor()
    @Volatile private var lastPrefetch = -1L

    /** Call before the first MapView is created. Safe to call again. */
    @Synchronized
    fun init(ctx: Context) {
        if (started) return
        app = ctx.applicationContext
        MapLibre.getInstance(app)
        fontAssets = listOf("regular", "bold").associateWith { dir ->
            (runCatching { app.assets.list("map/fonts/$dir") }.getOrNull() ?: emptyArray()).map { it.removeSuffix(".pbf") }.toSet()
        }
        openSaved()
        reloadPack()
        MapBuildings.init(app)
        val client = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 20 })
            .addInterceptor(Engine)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        HttpRequestUtil.setOkHttpClient(client)
        // MapLibre must always ask: this engine decides whether the answer is local or online.
        MapLibre.setConnected(true)
        // MapLibre's own cache is keyed by the online address, which changes weekly; ours is not.
        runCatching {
            OfflineManager.getInstance(app).setMaximumAmbientCacheSize(0, object : OfflineManager.FileSourceCallback {
                override fun onSuccess() {}
                override fun onError(message: String) {}
            })
        }
        watchNetwork()
        started = true
    }

    private fun watchNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        online = runCatching {
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(true)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    online = true
                    netBlockedUntil = 0L
                    retryFailed()
                }

                override fun onLost(network: Network) {
                    online = false
                }
            })
        }
    }

    /** Makes MapLibre ask again for everything that failed (internet is back, or a pack was installed). */
    fun retryFailed() {
        main.post {
            runCatching {
                MapLibre.setConnected(false)
                MapLibre.setConnected(true)
            }
        }
    }

    // ------------------------------------------------------------------ offline pack

    fun hasPack(): Boolean = packLock.read { pack != null }

    /** Opens (or re-opens) the downloaded Iraq map. */
    fun reloadPack() {
        packLock.write {
            runCatching { pack?.close() }
            pack = null
            packBounds = null
            val f = MapData.tilesFile(app) ?: return
            runCatching {
                val db = SQLiteDatabase.openDatabase(
                    f.absolutePath, null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                )
                db.rawQuery("SELECT value FROM metadata WHERE name='bounds'", null).use { c ->
                    if (c.moveToFirst()) {
                        val p = c.getString(0).split(',').mapNotNull { it.trim().toDoubleOrNull() }
                        if (p.size == 4) packBounds = p.toDoubleArray()
                    }
                }
                pack = db
            }
        }
    }

    /** Lets go of the pack file so it can be replaced or deleted. */
    fun closePack() {
        packLock.write {
            runCatching { pack?.close() }
            pack = null
            packBounds = null
        }
    }

    private fun packTile(z: Int, x: Int, y: Int): ByteArray? = packLock.read {
        val db = pack ?: return null
        runCatching {
            val row = (1 shl z) - 1 - y // .mbtiles counts rows from the bottom
            db.rawQuery("SELECT tile_data FROM tiles WHERE zoom_level=$z AND tile_column=$x AND tile_row=$row", null).use { c ->
                if (c.moveToFirst()) gunzip(c.getBlob(0)) else null
            }
        }.getOrNull()
    }

    private fun packHas(z: Int, x: Int, y: Int): Boolean = packLock.read {
        val db = pack ?: return false
        runCatching {
            val row = (1 shl z) - 1 - y
            db.rawQuery("SELECT 1 FROM tiles WHERE zoom_level=$z AND tile_column=$x AND tile_row=$row", null).use { it.moveToFirst() }
        }.getOrDefault(false)
    }

    /** True when the tile touches the area our pack covers (there, the pack is the authority). */
    private fun insidePack(z: Int, x: Int, y: Int): Boolean {
        val b = packLock.read { packBounds } ?: return false
        val n = (1 shl z).toDouble()
        val west = x / n * 360.0 - 180.0
        val east = (x + 1) / n * 360.0 - 180.0
        val north = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / n))))
        val south = Math.toDegrees(atan(sinh(PI * (1 - 2 * (y + 1) / n))))
        return east > b[0] && west < b[2] && north > b[1] && south < b[3]
    }

    // ------------------------------------------------------------------ saved streets

    private fun savedFile(): File = File(MapData.dir(app), "saved-tiles.db")

    private fun openSaved() {
        runCatching {
            val db = SQLiteDatabase.openOrCreateDatabase(savedFile(), null)
            runCatching { db.rawQuery("PRAGMA auto_vacuum=INCREMENTAL", null).use { it.moveToFirst() } }
            db.enableWriteAheadLogging()
            db.execSQL("CREATE TABLE IF NOT EXISTS t(z INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, d BLOB NOT NULL, ts INTEGER NOT NULL, PRIMARY KEY(z,x,y)) WITHOUT ROWID")
            db.execSQL("CREATE TABLE IF NOT EXISTS g(k TEXT PRIMARY KEY, d BLOB NOT NULL) WITHOUT ROWID")
            saved = db
        }
    }

    private class Saved(val data: ByteArray, val ts: Long)

    private fun savedTile(z: Int, x: Int, y: Int): Saved? {
        val db = saved ?: return null
        return runCatching {
            db.rawQuery("SELECT d, ts FROM t WHERE z=$z AND x=$x AND y=$y", null).use { c ->
                if (!c.moveToFirst()) null
                else {
                    val raw = c.getBlob(0)
                    Saved(if (raw.isEmpty()) EMPTY else gunzip(raw), c.getLong(1))
                }
            }
        }.getOrNull()
    }

    private fun savedHas(z: Int, x: Int, y: Int): Boolean {
        val db = saved ?: return false
        return runCatching {
            db.rawQuery("SELECT 1 FROM t WHERE z=$z AND x=$x AND y=$y", null).use { it.moveToFirst() }
        }.getOrDefault(false)
    }

    private fun saveTile(z: Int, x: Int, y: Int, data: ByteArray) {
        val db = saved ?: return
        if (!MapPrefs(app).autoSave) return
        runCatching {
            db.execSQL(
                "INSERT OR REPLACE INTO t(z,x,y,d,ts) VALUES(?,?,?,?,?)",
                arrayOf<Any>(z, x, y, if (data.isEmpty()) EMPTY else gzip(data), System.currentTimeMillis())
            )
            if (writes.incrementAndGet() % 300L == 0L) trimSaved()
        }
    }

    private fun trimSaved() {
        val db = saved ?: return
        val limit = MapPrefs(app).cacheLimitMb * 1048576L
        if (savedFile().length() < limit) return
        runCatching {
            // Oldest fifth goes; the file is then given back to the disk.
            db.execSQL("DELETE FROM t WHERE ts <= (SELECT ts FROM t ORDER BY ts LIMIT 1 OFFSET (SELECT COUNT(*) / 5 FROM t))")
            db.rawQuery("PRAGMA incremental_vacuum", null).use { c -> while (c.moveToNext()) { /* runs the vacuum */ } }
        }
    }

    /** Number of saved tiles and the bytes they take on disk. */
    fun savedStats(): Pair<Long, Long> {
        val db = saved ?: return 0L to 0L
        val n = runCatching { db.rawQuery("SELECT COUNT(*) FROM t", null).use { if (it.moveToFirst()) it.getLong(0) else 0L } }.getOrDefault(0L)
        val f = savedFile()
        val wal = File(f.path + "-wal")
        return n to (f.length() + if (wal.exists()) wal.length() else 0L)
    }

    fun clearSaved() {
        val db = saved ?: return
        runCatching {
            db.execSQL("DELETE FROM t")
            db.execSQL("DELETE FROM g")
            db.execSQL("VACUUM")
        }
    }

    // ------------------------------------------------------------------ internet

    private fun canFetch(): Boolean = online && SystemClock.elapsedRealtime() >= netBlockedUntil

    private fun netFailed() {
        // A screen on Wi-Fi with no real internet would otherwise wait out a timeout per tile.
        if (netFails.incrementAndGet() >= 4) {
            netFails.set(0)
            netBlockedUntil = SystemClock.elapsedRealtime() + 15000
        }
    }

    /** The current online tile address ("…/planet/<release>/{z}/{x}/{y}.pbf"), refreshed now and then. */
    @Synchronized
    private fun template(force: Boolean = false): String? {
        val p = MapPrefs(app)
        val now = System.currentTimeMillis()
        val have = p.tileTemplate
        if (have != null && !force && now - p.tileTemplateAt < TEMPLATE_MAX_AGE_MS) return have
        if (!online || (now - lastTemplateTry < 60000 && have != null)) return have
        if (now - lastTemplateTry < 5000) return have
        lastTemplateTry = now
        val fresh = runCatching {
            upstream.newCall(Request.Builder().url(UPSTREAM_INFO).header("User-Agent", AGENT).build()).execute().use { r ->
                if (!r.isSuccessful) null else JSONObject(r.body!!.string()).getJSONArray("tiles").getString(0)
            }
        }.getOrNull()
        if (fresh != null && fresh.startsWith("https://tiles.openfreemap.org/") && fresh.contains("{z}")) {
            p.tileTemplate = fresh
            p.tileTemplateAt = now
            return fresh
        }
        return have
    }

    /** null = could not reach the internet; empty = the tile exists but has nothing in it. */
    private fun download(call: (Request) -> Response, z: Int, x: Int, y: Int): ByteArray? {
        var tpl = template() ?: return null
        for (attempt in 0..1) {
            val url = tpl.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
            val resp = try {
                call(Request.Builder().url(url).header("User-Agent", AGENT).build())
            } catch (e: IOException) {
                netFailed()
                return null
            }
            resp.use { r ->
                if (r.code == 200) {
                    netFails.set(0)
                    return try { r.body!!.bytes() } catch (e: IOException) { null }
                }
                if (r.code != 204 && r.code != 404) return null
            }
            // "Not there" can mean an empty tile, or that the weekly release we know is gone.
            netFails.set(0)
            val newer = if (attempt == 0) template(force = true) else null
            if (newer == null || newer == tpl) return EMPTY
            tpl = newer
        }
        return EMPTY
    }

    // ------------------------------------------------------------------ answering the map

    private object Engine : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            if (req.url.host != HOST) return chain.proceed(req)
            val seg = req.url.pathSegments
            try {
                return when (seg.getOrNull(0)) {
                    "tiles" -> tile(chain, seg[1].toInt(), seg[2].toInt(), seg[3].substringBefore('.').toInt())
                    "btiles" -> buildingTile(req, seg[1].toInt(), seg[2].toInt(), seg[3].substringBefore('.').toInt())
                    "fonts" -> glyphs(chain, seg[1], seg[2].substringBefore('.'))
                    else -> reply(req, 404, EMPTY)
                }
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException(e)
            }
        }
    }

    private fun reply(req: Request, code: Int, body: ByteArray): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "No Content")
            .header("Cache-Control", "max-age=3600")
            .body(body.toResponseBody(PBF))
            .build()

    private fun tileReply(req: Request, data: ByteArray): Response =
        if (data.isEmpty()) reply(req, 204, EMPTY) else reply(req, 200, data)

    private fun tileReply(req: Request, z: Int, x: Int, y: Int, data: ByteArray): Response =
        if (data.isEmpty()) reply(req, 204, EMPTY) else reply(req, 200, withoutOsmBuildings(z, x, y, data))

    private fun tile(chain: Interceptor.Chain, z: Int, x: Int, y: Int): Response {
        val req = chain.request()
        // Far-out zooms come from the internet when possible: the pack only knows Iraq, so the
        // neighbouring countries would look empty. Close-up zooms are the pack's job.
        val wide = z <= 7
        if (!wide) packTile(z, x, y)?.let {
            fromPack.incrementAndGet()
            return tileReply(req, z, x, y, it)
        }
        val kept = savedTile(z, x, y)
        val fresh = kept != null && System.currentTimeMillis() - kept.ts < REFRESH_AFTER_MS
        if (kept != null && (fresh || !canFetch())) {
            fromSaved.incrementAndGet()
            return tileReply(req, z, x, y, kept.data)
        }
        if (canFetch()) {
            val got = download({ chain.proceed(it) }, z, x, y)
            if (got != null) {
                fromNet.incrementAndGet()
                saveTile(z, x, y, got)
                return tileReply(req, z, x, y, got)
            }
        }
        if (kept != null) {
            fromSaved.incrementAndGet()
            return tileReply(req, z, x, y, kept.data)
        }
        if (wide) packTile(z, x, y)?.let {
            fromPack.incrementAndGet()
            return tileReply(req, z, x, y, it)
        }
        // Inside Iraq the pack is complete: what it does not have is simply empty land.
        if (!wide && insidePack(z, x, y)) return reply(req, 204, EMPTY)
        // Anything else is unknown until there is internet; an error keeps the wider tile on screen.
        throw IOException("tile $z/$x/$y is not saved and there is no internet")
    }

    /** Our all-Iraq building footprints (see [MapBuildings]): kept on the screen, else fetched and kept. */
    private fun buildingTile(req: Request, z: Int, x: Int, y: Int): Response {
        val got = MapBuildings.tile(z, x, y, canFetch())
        return when {
            got == null -> throw IOException("buildings $z/$x/$y not on the screen yet and no internet")
            got.isEmpty() -> reply(req, 204, EMPTY)
            else -> reply(req, 200, got)
        }
    }

    /**
     * Removes the "building" layer from a map piece when our own footprints cover it, so the same
     * building is not drawn twice (our set already contains the OpenStreetMap ones).
     */
    private fun withoutOsmBuildings(z: Int, x: Int, y: Int, data: ByteArray): ByteArray {
        if (z < 13 || data.isEmpty()) return data
        val bz = minOf(z, 14)
        val bx = x shr (z - bz)
        val by = y shr (z - bz)
        if (!MapBuildings.has(bz, bx, by)) return data
        return runCatching { dropLayer(data, "building") }.getOrDefault(data)
    }

    /** Vector tile without the layer called [name] (top-level field 3 = layer, its field 1 = name). */
    private fun dropLayer(b: ByteArray, name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
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
        while (p < b.size) {
            val start = p
            val key = varint()
            val wire = (key and 7).toInt()
            val end = when (wire) {
                0 -> { varint(); p }
                1 -> p + 8
                2 -> { val n = varint().toInt(); p + n }
                5 -> p + 4
                else -> return b
            }
            var keep = true
            if ((key ushr 3) == 3L && wire == 2) {
                // Look for the layer's name inside it.
                val save = p
                while (p < end) {
                    val k = varint()
                    val w = (k and 7).toInt()
                    if ((k ushr 3) == 1L && w == 2) {
                        val n = varint().toInt()
                        if (String(b, p, n, Charsets.UTF_8) == name) keep = false
                        break
                    }
                    when (w) { 0 -> varint(); 1 -> p += 8; 2 -> p += varint().toInt(); 5 -> p += 4; else -> break }
                }
                p = save
            }
            p = end
            if (keep) out.write(b, start, end - start)
        }
        return out.toByteArray()
    }

    private fun glyphs(chain: Interceptor.Chain, stack: String, range: String): Response {
        val req = chain.request()
        if (!Regex("\\d+-\\d+").matches(range)) return reply(req, 200, EMPTY)
        val bold = stack.contains("Bold", ignoreCase = true)
        val dir = if (bold) "bold" else "regular"
        if (fontAssets[dir]?.contains(range) == true) {
            runCatching { app.assets.open("map/fonts/$dir/$range.pbf").use { it.readBytes() } }.getOrNull()?.let {
                return reply(req, 200, it)
            }
        }
        val key = "$dir/$range"
        val db = saved
        if (db != null) {
            runCatching {
                db.rawQuery("SELECT d FROM g WHERE k=?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }
            }.getOrNull()?.let { return reply(req, 200, it) }
        }
        if (canFetch()) {
            val name = if (bold) "Noto%20Sans%20Bold" else "Noto%20Sans%20Regular"
            val got = runCatching {
                chain.proceed(Request.Builder().url("$UPSTREAM_FONTS$name/$range.pbf").header("User-Agent", AGENT).build()).use { r ->
                    if (r.code == 200) r.body!!.bytes() else null
                }
            }.getOrNull()
            if (got != null) {
                runCatching { db?.execSQL("INSERT OR REPLACE INTO g(k,d) VALUES(?,?)", arrayOf<Any>(key, got)) }
                return reply(req, 200, got)
            }
        }
        // An empty letter set, not an error: an error would leave every label of the tile hidden.
        return reply(req, 200, EMPTY)
    }

    // ------------------------------------------------------------------ saving the road ahead

    /**
     * Called as the car moves: saves the streets around it at every zoom (not only the zoom on
     * screen), so the same road works later with no internet whatever the zoom.
     */
    fun prefetchAround(lat: Double, lon: Double) {
        if (!started || !online) return
        val z = 14
        val cx = lonToX(lon, z)
        val cy = latToY(lat, z)
        val key = (cx.toLong() shl 32) or cy.toLong()
        if (key == lastPrefetch) return
        lastPrefetch = key
        if (!MapPrefs(app).autoSave) return
        prefetcher.execute {
            val max = (1 shl z) - 1
            for (dx in -1..1) for (dy in -1..1) keep(z, (cx + dx).coerceIn(0, max), (cy + dy).coerceIn(0, max))
            var x = cx
            var y = cy
            for (zz in 13 downTo 8) {
                x = x shr 1
                y = y shr 1
                keep(zz, x, y)
            }
        }
    }

    private fun keep(z: Int, x: Int, y: Int) {
        if (!canFetch() || packHas(z, x, y) || savedHas(z, x, y)) return
        val got = download({ upstream.newCall(it).execute() }, z, x, y) ?: return
        saveTile(z, x, y, got)
    }

    // ------------------------------------------------------------------ helpers

    fun lonToX(lon: Double, z: Int): Int {
        val n = 1 shl z
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    fun latToY(lat: Double, z: Int): Int {
        val n = 1 shl z
        val r = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
    }

    private fun gunzip(data: ByteArray): ByteArray {
        if (data.size < 2 || data[0] != 0x1f.toByte() || data[1] != 0x8b.toByte()) return data
        return GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }
}

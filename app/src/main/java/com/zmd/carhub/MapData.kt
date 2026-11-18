package com.zmd.carhub

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * The offline map of Iraq: three files in the app's "maps" folder on the screen's storage.
 *
 *   iraq.mbtiles     the map itself (every street, building and name)
 *   iraq-places.db   the search index
 *   iraq-gh/         the road network for directions (arrives zipped as iraq-routing.zip)
 *
 * They are downloaded from the address in maps.json (next to the store), with resume, and are
 * checked against their SHA-256. They can also simply be copied into the folder (adb push):
 * /sdcard/Android/data/com.zmd.carhub/files/maps/
 */
object MapData {
    const val DEFAULT_MANIFEST = "https://github.com/zaid936/zmd-car-store/releases/download/maps/maps.json"
    const val TILES = "iraq.mbtiles"
    const val PLACES = "iraq-places.db"
    const val ROUTING_ZIP = "iraq-routing.zip"
    const val ROUTING_DIR = "iraq-gh"

    enum class Phase { IDLE, CHECKING, DOWNLOADING, INSTALLING, DONE, ERROR }

    class Status(val phase: Phase, val done: Long = 0, val total: Long = 0, val note: String = "")

    class Part(val url: String, val size: Long)

    class Item(val name: String, val parts: List<Part>, val size: Long, val sha256: String)

    class Manifest(val version: String, val items: List<Item>)

    @Volatile var status = Status(Phase.IDLE)
        private set
    private val listeners = CopyOnWriteArrayList<(Status) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var worker: Thread? = null
    @Volatile private var cancelled = false

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    fun addListener(l: (Status) -> Unit) { listeners += l }
    fun removeListener(l: (Status) -> Unit) { listeners -= l }

    private fun post(s: Status) {
        status = s
        main.post { listeners.forEach { it(s) } }
    }

    // ------------------------------------------------------------------ what is installed

    fun dir(ctx: Context): File {
        val d = ctx.getExternalFilesDir("maps") ?: File(ctx.filesDir, "maps")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun tilesFile(ctx: Context): File? = File(dir(ctx), TILES).takeIf { it.isFile && it.length() > 1_000_000 }

    fun placesFile(ctx: Context): File? = File(dir(ctx), PLACES).takeIf { it.isFile && it.length() > 100_000 }

    fun routingDir(ctx: Context): File? = File(dir(ctx), ROUTING_DIR).takeIf { File(it, "properties").isFile && File(it, "edges").isFile }

    /** True when the whole offline pack is in place. */
    fun complete(ctx: Context): Boolean = tilesFile(ctx) != null && placesFile(ctx) != null && routingDir(ctx) != null

    fun installedBytes(ctx: Context): Long {
        var n = (tilesFile(ctx)?.length() ?: 0L) + (placesFile(ctx)?.length() ?: 0L)
        routingDir(ctx)?.listFiles()?.forEach { n += it.length() }
        return n
    }

    private fun record(ctx: Context): JSONObject =
        runCatching { JSONObject(File(dir(ctx), "installed.json").readText()) }.getOrDefault(JSONObject())

    private fun saveRecord(ctx: Context, o: JSONObject) {
        runCatching { File(dir(ctx), "installed.json").writeText(o.toString()) }
    }

    private fun recordedSha(ctx: Context, name: String): String? =
        record(ctx).optJSONObject("files")?.optString(name)?.takeIf { it.isNotEmpty() }

    private fun setSha(ctx: Context, name: String, sha: String?, version: String? = null) {
        val o = record(ctx)
        val files = o.optJSONObject("files") ?: JSONObject().also { o.put("files", it) }
        if (sha == null) files.remove(name) else files.put(name, sha)
        if (version != null) o.put("version", version)
        saveRecord(ctx, o)
    }

    /** The date of the installed map data, e.g. "2026-11-17", or null. */
    fun installedVersion(ctx: Context): String? = record(ctx).optString("version").takeIf { it.isNotEmpty() }

    // ------------------------------------------------------------------ the list online

    /** Reads maps.json. Blocking: call from a background thread. */
    fun fetchManifest(ctx: Context): Manifest {
        val url = MapPrefs(ctx).manifestUrl
        if (!Net.allowed(url)) throw IOException("الرابط لازم يبدي بـ https")
        val text = http.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body!!.string()
        }
        val o = JSONObject(text)
        val base = url.substringBeforeLast('/') + "/"
        fun full(u: String) = if (u.startsWith("http://") || u.startsWith("https://")) u else base + u
        val items = ArrayList<Item>()
        val arr = o.getJSONArray("files")
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            val size = f.getLong("size")
            val parts = ArrayList<Part>()
            val pa = f.optJSONArray("parts")
            if (pa != null && pa.length() > 0) {
                for (k in 0 until pa.length()) {
                    val p = pa.getJSONObject(k)
                    parts += Part(full(p.getString("url")), p.getLong("size"))
                }
            } else {
                parts += Part(full(f.optString("url", f.getString("name"))), size)
            }
            if (parts.any { !Net.allowed(it.url) }) throw IOException("رابط ملف الخريطة لازم يبدي بـ https")
            items += Item(f.getString("name"), parts, size, f.optString("sha256").lowercase())
        }
        return Manifest(o.optString("version", "?"), items)
    }

    /** The files of [m] this screen does not have yet (or has an older copy of). */
    fun missing(ctx: Context, m: Manifest): List<Item> = m.items.filter { item ->
        val present = when (item.name) {
            TILES -> tilesFile(ctx) != null
            PLACES -> placesFile(ctx) != null
            ROUTING_ZIP -> routingDir(ctx) != null
            else -> File(dir(ctx), item.name).isFile
        }
        !present || (item.sha256.isNotEmpty() && recordedSha(ctx, item.name) != item.sha256)
    }

    // ------------------------------------------------------------------ download

    fun busy(): Boolean = worker?.isAlive == true

    /** Downloads whatever is missing. Progress arrives through the listeners. */
    @Synchronized
    fun start(ctx: Context) {
        if (busy()) return
        val app = ctx.applicationContext
        cancelled = false
        worker = Thread {
            try {
                post(Status(Phase.CHECKING))
                adoptCopied(app)
                val m = fetchManifest(app)
                val todo = missing(app, m)
                val total = todo.sumOf { it.size }
                if (todo.isEmpty()) {
                    setVersion(app, m.version)
                    post(Status(Phase.DONE, note = m.version))
                    return@Thread
                }
                val free = dir(app).usableSpace
                // The routing zip is unpacked next to itself, so it needs its size about three times.
                val need = total + (todo.firstOrNull { it.name == ROUTING_ZIP }?.size ?: 0L) * 2 + 50_000_000L
                if (free in 1 until need) {
                    throw IOException("ماكو مساحة كافية: نحتاج ${need / 1048576} ميكا والباقي ${free / 1048576}")
                }
                var before = 0L
                for (item in todo) {
                    val part = File(dir(app), item.name + ".part")
                    fetch(item, part, before, total)
                    post(Status(Phase.INSTALLING, before + item.size, total, item.name))
                    val sha = sha256(part)
                    if (item.sha256.isNotEmpty() && sha != item.sha256) {
                        part.delete()
                        throw IOException("الملف ${item.name} نزل ناقص أو متغيّر، جرّب مرة ثانية")
                    }
                    install(app, item.name, part, sha)
                    before += item.size
                }
                setVersion(app, m.version)
                post(Status(Phase.DONE, total, total, m.version))
            } catch (e: Exception) {
                if (cancelled) post(Status(Phase.IDLE))
                else post(Status(Phase.ERROR, note = e.message ?: e.javaClass.simpleName))
            }
        }.also {
            it.name = "map-data"
            it.start()
        }
    }

    fun cancel() {
        cancelled = true
        worker?.interrupt()
    }

    private fun setVersion(ctx: Context, version: String) {
        val o = record(ctx)
        o.put("version", version)
        saveRecord(ctx, o)
    }

    /** Downloads [item] into [part], continuing from wherever an earlier try stopped. */
    private fun fetch(item: Item, part: File, before: Long, total: Long) {
        var tries = 0
        while (true) {
            if (cancelled) throw IOException("cancelled")
            try {
                var have = if (part.exists()) part.length() else 0L
                if (have > item.size) {
                    part.delete()
                    have = 0L
                }
                var offset = 0L // where the current piece starts inside the whole file
                for (p in item.parts) {
                    val end = offset + p.size
                    if (have >= end) {
                        offset = end
                        continue
                    }
                    val from = have - offset
                    val rb = Request.Builder().url(p.url).header("Accept-Encoding", "identity")
                    if (from > 0) rb.header("Range", "bytes=$from-")
                    http.newCall(rb.build()).execute().use { r ->
                        if (r.code != 200 && r.code != 206) throw IOException("HTTP ${r.code}")
                        RandomAccessFile(part, "rw").use { out ->
                            // 200 = the server sent the piece from its start again.
                            val at = if (r.code == 206) have else offset
                            out.setLength(at)
                            out.seek(at)
                            have = at
                            val input = r.body!!.byteStream()
                            val buf = ByteArray(1 shl 16)
                            var last = 0L
                            while (true) {
                                if (cancelled) throw IOException("cancelled")
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                have += n
                                val now = System.currentTimeMillis()
                                if (now - last > 400) {
                                    last = now
                                    post(Status(Phase.DOWNLOADING, before + have, total, item.name))
                                }
                            }
                        }
                    }
                    if (have < end) throw IOException("انقطع التنزيل")
                    offset = end
                    tries = 0
                }
                if (part.length() != item.size) throw IOException("حجم الملف مو مضبوط")
                return
            } catch (e: IOException) {
                if (cancelled || ++tries > 6) throw e
                post(Status(Phase.DOWNLOADING, before + (if (part.exists()) part.length() else 0L), total, "انقطع النت، دا نحاول مرة ثانية..."))
                try { Thread.sleep(3000L * tries) } catch (ie: InterruptedException) { throw IOException("cancelled") }
            }
        }
    }

    /** Moves a finished download into place and tells the map, search and directions about it. */
    private fun install(ctx: Context, name: String, part: File, sha: String) {
        val d = dir(ctx)
        when (name) {
            TILES -> {
                MapTiles.closePack()
                swap(part, File(d, TILES))
                setSha(ctx, name, sha)
                MapTiles.reloadPack()
                MapTiles.retryFailed()
            }
            PLACES -> {
                MapSearch.close()
                swap(part, File(d, PLACES))
                setSha(ctx, name, sha)
            }
            ROUTING_ZIP -> {
                MapRoute.close()
                unzipRouting(ctx, part)
                part.delete()
                setSha(ctx, name, sha)
            }
            else -> {
                swap(part, File(d, name))
                setSha(ctx, name, sha)
            }
        }
    }

    private fun swap(from: File, to: File) {
        if (to.exists() && !to.delete()) throw IOException("ما انمسح الملف القديم ${to.name}")
        File(to.path + "-journal").delete()
        if (!from.renameTo(to)) throw IOException("ما انحفظ الملف ${to.name}")
    }

    private fun unzipRouting(ctx: Context, zip: File) {
        val d = dir(ctx)
        val fresh = File(d, "$ROUTING_DIR.new")
        fresh.deleteRecursively()
        fresh.mkdirs()
        ZipInputStream(FileInputStream(zip).buffered(1 shl 16)).use { z ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val e = z.nextEntry ?: break
                // Entries are "iraq-gh/<file>": only the file name is used, so nothing can escape the
                // folder. A zip made on Windows may say "iraq-gh\<file>", so both slashes count.
                val leaf = e.name.replace('\\', '/').substringAfterLast('/')
                if (e.isDirectory || leaf.isEmpty() || leaf.startsWith(".")) continue
                FileOutputStream(File(fresh, leaf)).use { out ->
                    while (true) {
                        if (cancelled) throw IOException("cancelled")
                        val n = z.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        if (!File(fresh, "properties").isFile) {
            fresh.deleteRecursively()
            throw IOException("ملف الطرق مو كامل")
        }
        val live = File(d, ROUTING_DIR)
        live.deleteRecursively()
        if (!fresh.renameTo(live)) throw IOException("ما انحفظت بيانات الطرق")
    }

    /**
     * Files copied into the folder by hand (adb push) instead of downloaded: the routing zip is
     * unpacked, and each file's checksum is noted so a later "update" does not fetch it again.
     * Blocking; called from a background thread when the map starts.
     */
    fun adoptCopied(ctx: Context) {
        val d = dir(ctx)
        val zip = File(d, ROUTING_ZIP)
        if (zip.isFile && zip.length() > 1_000_000) {
            runCatching {
                val sha = sha256(zip)
                MapRoute.close()
                unzipRouting(ctx, zip)
                zip.delete()
                setSha(ctx, ROUTING_ZIP, sha)
            }
        }
        for (name in listOf(TILES, PLACES)) {
            val f = File(d, name)
            if (f.isFile && f.length() > 100_000 && recordedSha(ctx, name) == null) {
                runCatching { setSha(ctx, name, sha256(f)) }
            }
        }
        if (installedVersion(ctx) == null && complete(ctx)) {
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(File(d, TILES).lastModified()))
            setVersion(ctx, day)
        }
    }

    /** Removes the offline pack (the saved streets are kept; they have their own button). */
    fun delete(ctx: Context) {
        cancel()
        MapTiles.closePack()
        MapSearch.close()
        MapRoute.close()
        val d = dir(ctx)
        listOf(TILES, PLACES, ROUTING_ZIP, "installed.json").forEach { File(d, it).delete() }
        d.listFiles()?.filter { it.name.endsWith(".part") || it.name.endsWith("-journal") }?.forEach { it.delete() }
        File(d, ROUTING_DIR).deleteRecursively()
        File(d, "$ROUTING_DIR.new").deleteRecursively()
        post(Status(Phase.IDLE))
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

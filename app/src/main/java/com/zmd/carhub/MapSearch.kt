package com.zmd.carhub

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Search. With the offline pack: our own index of every named place, street and point of
 * interest in Iraq (iraq-places.db, built by A:\byd\MapData\scripts\BuildPlaces.java), searched as
 * the user types. Without it, or on request: OpenStreetMap's online search (Nominatim).
 */
object MapSearch {
    private var db: SQLiteDatabase? = null
    private var dbPath: String? = null

    /** A "near me" button: its Arabic label and the kinds of place it finds. */
    class Category(val key: String, val title: String, val kinds: List<String>, val words: List<String>)

    val CATEGORIES = listOf(
        Category("fuel", "بنزين", listOf("fuel"), listOf("بنزين", "بانزين", "وقود", "محطه", "محطه وقود", "محطه بنزين", "بنزينخانه", "fuel", "gas", "petrol")),
        Category("charging", "شحن كهرباء", listOf("charging"), listOf("شحن", "شاحن", "محطه شحن", "شحن كهرباء", "charging", "charger")),
        Category("restaurant", "مطاعم", listOf("restaurant"), listOf("مطعم", "مطاعم", "اكل", "restaurant", "food")),
        Category("cafe", "كافيهات", listOf("cafe"), listOf("كافيه", "كافيهات", "كوفي", "مقهى", "كهوه", "قهوه", "cafe", "coffee")),
        Category("market", "أسواق", listOf("mall", "market"), listOf("سوق", "اسواق", "مول", "ماركت", "سوبر ماركت", "mall", "market")),
        Category("parking", "مواقف", listOf("parking"), listOf("موقف", "مواقف", "كراج", "باركنك", "parking")),
        Category("hospital", "مستشفيات", listOf("hospital"), listOf("مستشفى", "مستشفيات", "عياده", "طبيب", "دكتور", "hospital", "clinic")),
        Category("pharmacy", "صيدليات", listOf("pharmacy"), listOf("صيدليه", "صيدليات", "pharmacy")),
        Category("mosque", "جوامع", listOf("mosque"), listOf("جامع", "جوامع", "مسجد", "مساجد", "حسينيه", "mosque")),
        Category("bank", "مصارف", listOf("bank", "atm"), listOf("مصرف", "مصارف", "بنك", "صراف", "صراف الي", "bank", "atm")),
        Category("car", "تصليح سيارات", listOf("car"), listOf("تصليح", "فيتر", "ميكانيك", "غسل", "غسيل", "اطارات", "بنجرجي", "car repair")),
        Category("hotel", "فنادق", listOf("hotel"), listOf("فندق", "فنادق", "hotel")),
        Category("police", "شرطة", listOf("police"), listOf("شرطه", "مركز شرطه", "police")),
    )

    private val KIND_TITLES = mapOf(
        "city" to "مدينة", "town" to "مدينة", "village" to "قرية", "hamlet" to "قرية", "suburb" to "حي",
        "quarter" to "حي", "neighbourhood" to "محلة", "locality" to "منطقة", "square" to "ساحة",
        "state" to "محافظة", "province" to "محافظة", "region" to "إقليم", "district" to "قضاء", "county" to "قضاء",
        "island" to "جزيرة", "farm" to "مزرعة", "city_block" to "بلوك", "isolated_dwelling" to "منطقة",
        "street" to "شارع", "fuel" to "محطة وقود", "charging" to "محطة شحن", "restaurant" to "مطعم",
        "cafe" to "كافيه", "hospital" to "مستشفى / عيادة", "pharmacy" to "صيدلية", "mosque" to "جامع",
        "church" to "كنيسة", "worship" to "دار عبادة", "school" to "مدرسة", "university" to "جامعة",
        "bank" to "مصرف", "atm" to "صراف آلي", "police" to "شرطة", "parking" to "موقف سيارات",
        "car" to "خدمات سيارات", "gov" to "دائرة حكومية", "market" to "سوق", "mall" to "مول",
        "fun" to "ترفيه", "station" to "محطة", "hotel" to "فندق", "attraction" to "معلم",
        "park" to "حديقة", "sport" to "رياضة", "airport" to "مطار", "office" to "مكتب",
        "shop" to "محل", "building" to "بناية", "area" to "منطقة", "nature" to "طبيعة", "other" to "مكان",
    )

    fun kindTitle(kind: String): String = KIND_TITLES[kind] ?: ""

    fun ready(ctx: Context): Boolean = open(ctx) != null

    @Synchronized
    private fun open(ctx: Context): SQLiteDatabase? {
        val f = MapData.placesFile(ctx)
        if (f == null) {
            close()
            return null
        }
        db?.let { if (dbPath == f.absolutePath && it.isOpen) return it }
        close()
        return runCatching {
            SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        }.getOrNull()?.also {
            db = it
            dbPath = f.absolutePath
        }
    }

    @Synchronized
    fun close() {
        runCatching { db?.close() }
        db = null
        dbPath = null
    }

    private const val COLS = "p.id, p.name, p.alt, p.kind, p.sub, p.lat, p.lon, p.rank"

    private class Row(val id: Int, val place: Place, val rank: Int)

    private fun rows(c: Cursor, into: HashMap<Int, Row>) {
        while (c.moveToNext()) {
            val id = c.getInt(0)
            if (into.containsKey(id)) continue
            val kind = c.getString(3)
            val name = c.getString(1).ifEmpty { kindTitle(kind) }
            into[id] = Row(id, Place(name, c.getString(4), c.getDouble(5), c.getDouble(6), kind, c.getString(2)), c.getInt(7))
        }
    }

    /** The category a query asks for ("بنزين", "صيدلية"...), or null when it is a name. */
    fun categoryOf(query: String): Category? {
        val q = norm(query)
        if (q.isEmpty()) return null
        return CATEGORIES.firstOrNull { c -> c.words.any { norm(it) == q } }
    }

    /**
     * Offline search by name, as you type. Every word must start a word of the name. Results are
     * ordered by how important the place is, how well the name fits, and how near it is.
     */
    fun search(ctx: Context, query: String, nearLat: Double, nearLon: Double, limit: Int = 40): List<Place> {
        val d = open(ctx) ?: return emptyList()
        val q = norm(query)
        val words = q.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || (words.size == 1 && words[0].length < 2)) return emptyList()
        val match = words.filter { it.length >= 2 || words.size == 1 }.joinToString(" ") { "$it*" }
        if (match.isEmpty()) return emptyList()
        val found = HashMap<Int, Row>()
        runCatching {
            // The most important matches anywhere in the country...
            d.rawQuery("SELECT $COLS FROM fts f JOIN places p ON p.id = f.docid WHERE f.norm MATCH ? ORDER BY p.rank DESC LIMIT 160", arrayOf(match)).use { rows(it, found) }
            // ...and every match close to the car, however small.
            val dLat = 0.35
            val dLon = 0.35 / cos(Math.toRadians(nearLat)).coerceAtLeast(0.2)
            d.rawQuery(
                "SELECT $COLS FROM fts f JOIN places p ON p.id = f.docid WHERE f.norm MATCH ? AND p.lat BETWEEN ? AND ? AND p.lon BETWEEN ? AND ? LIMIT 300",
                arrayOf(match, (nearLat - dLat).toString(), (nearLat + dLat).toString(), (nearLon - dLon).toString(), (nearLon + dLon).toString())
            ).use { rows(it, found) }
        }
        return found.values.map { r ->
            val p = r.place
            p.distM = dist(nearLat, nearLon, p.lat, p.lon)
            val n = norm(p.name)
            val fit = when {
                n == q -> 30.0
                n.startsWith(q) -> 14.0
                else -> 0.0
            }
            val score = r.rank + fit - min(45.0, 15.0 * log10(1.0 + p.distM / 1000.0))
            score to p
        }.sortedByDescending { it.first }.take(limit).map { it.second }
    }

    /** The nearest places of a category, widening the circle until there are enough. */
    fun nearby(ctx: Context, kinds: List<String>, lat: Double, lon: Double, limit: Int = 30): List<Place> {
        val d = open(ctx) ?: return emptyList()
        val marks = kinds.joinToString(",") { "?" }
        var best: List<Place> = emptyList()
        for (km in doubleArrayOf(4.0, 12.0, 40.0, 150.0)) {
            val dLat = km / 111.0
            val dLon = km / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
            val found = HashMap<Int, Row>()
            runCatching {
                d.rawQuery(
                    "SELECT $COLS FROM places p WHERE p.kind IN ($marks) AND p.lat BETWEEN ? AND ? AND p.lon BETWEEN ? AND ? LIMIT 2000",
                    (kinds + listOf((lat - dLat).toString(), (lat + dLat).toString(), (lon - dLon).toString(), (lon + dLon).toString())).toTypedArray()
                ).use { rows(it, found) }
            }
            best = found.values.map { it.place.apply { distM = dist(lat, lon, this.lat, this.lon) } }
                .filter { it.distM <= km * 1000 }
                .sortedBy { it.distM }
                .take(limit)
            if (best.size >= 8) break
        }
        return best
    }

    /** Places of these kinds inside a box (south, west, north, east), for drawing them on the map. */
    fun inBox(ctx: Context, kinds: List<String>, s: Double, w: Double, n: Double, e: Double, limit: Int = 300): List<Place> {
        val d = open(ctx) ?: return emptyList()
        val marks = kinds.joinToString(",") { "?" }
        val found = HashMap<Int, Row>()
        runCatching {
            d.rawQuery(
                "SELECT $COLS FROM places p WHERE p.kind IN ($marks) AND p.lat BETWEEN ? AND ? AND p.lon BETWEEN ? AND ? LIMIT $limit",
                (kinds + listOf(s.toString(), n.toString(), w.toString(), e.toString())).toTypedArray()
            ).use { rows(it, found) }
        }
        return found.values.map { it.place }
    }

    /** What is at this point: the named place it sits on, or the street it is next to. */
    fun reverse(ctx: Context, lat: Double, lon: Double): Place? {
        val d = open(ctx) ?: return null
        val dLat = 0.0025
        val dLon = 0.0025 / cos(Math.toRadians(lat)).coerceAtLeast(0.2)
        val found = HashMap<Int, Row>()
        runCatching {
            d.rawQuery(
                "SELECT $COLS FROM places p WHERE p.lat BETWEEN ? AND ? AND p.lon BETWEEN ? AND ? AND p.name <> '' LIMIT 800",
                arrayOf((lat - dLat).toString(), (lat + dLat).toString(), (lon - dLon).toString(), (lon + dLon).toString())
            ).use { rows(it, found) }
        }
        var poi: Place? = null
        var street: Place? = null
        var area: Place? = null
        for (r in found.values) {
            val p = r.place
            p.distM = dist(lat, lon, p.lat, p.lon)
            when (p.kind) {
                "street" -> if (p.distM < 250 && (street == null || p.distM < street.distM)) street = p
                "suburb", "quarter", "neighbourhood", "village", "town", "city", "locality" ->
                    if (area == null || p.distM < area.distM) area = p
                else -> if (p.distM < 45 && (poi == null || p.distM < poi.distM)) poi = p
            }
        }
        poi?.let { return Place(it.name, it.sub, lat, lon, it.kind, it.alt) }
        street?.let { return Place(it.name, it.sub, lat, lon, "street") }
        area?.let { return Place("قرب ${it.name}", it.sub, lat, lon, "pin") }
        return null
    }

    /**
     * The city or town a point is near, for labels like «عن طريق الكوت»; a village only when it
     * is right there. Null without the offline search pack or when nothing is close.
     */
    fun nearestTown(ctx: Context, lat: Double, lon: Double, maxKm: Double = 35.0): String? {
        val d = open(ctx) ?: return null
        val dLat = maxKm / 111.0
        val dLon = maxKm / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        val found = HashMap<Int, Row>()
        runCatching {
            d.rawQuery(
                "SELECT $COLS FROM places p WHERE p.kind IN ('city','town','village') AND p.name <> '' " +
                    "AND p.lat BETWEEN ? AND ? AND p.lon BETWEEN ? AND ? LIMIT 3000",
                arrayOf((lat - dLat).toString(), (lat + dLat).toString(), (lon - dLon).toString(), (lon + dLon).toString())
            ).use { rows(it, found) }
        }
        var best: Place? = null
        var bestScore = Double.MAX_VALUE
        for (r in found.values) {
            val p = r.place
            val km = dist(lat, lon, p.lat, p.lon) / 1000.0
            if (km > (if (p.kind == "village") 6.0 else maxKm)) continue
            // A city a little further away says more than the village next to the road.
            val score = km / when (p.kind) {
                "city" -> 3.0
                "town" -> 1.8
                else -> 1.0
            }
            if (score < bestScore) {
                bestScore = score
                best = p
            }
        }
        return best?.name
    }

    /**
     * Online search (OpenStreetMap Nominatim), Arabic names, nearest to [lat],[lon]. One request
     * per search, as the service's usage policy asks; never called while typing.
     */
    fun online(query: String, lat: Double, lon: Double, version: String): List<Place> {
        val box = "${lon - 1.5},${lat + 1.5},${lon + 1.5},${lat - 1.5}"
        val url = URL(
            "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=10&accept-language=ar" +
                "&viewbox=$box&q=" + URLEncoder.encode(query.trim(), "UTF-8")
        )
        val con = url.openConnection() as HttpURLConnection
        con.setRequestProperty("User-Agent", "ZMD-Car/$version (personal car head unit)")
        con.connectTimeout = 10000
        con.readTimeout = 15000
        val text = try {
            con.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            con.disconnect()
        }
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val full = o.optString("display_name")
            Place(
                name = o.optString("name").ifEmpty { full.substringBefore(',') },
                sub = full.substringAfter(',', "").trim(),
                lat = o.getString("lat").toDouble(),
                lon = o.getString("lon").toDouble(),
            ).apply { distM = dist(lat, lon, this.lat, this.lon) }
        }
    }

    /** "33.3152, 44.3661" typed into the search box. */
    fun coordinates(query: String): Place? {
        val m = Regex("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*[,،\\s]\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$").find(query) ?: return null
        val la = m.groupValues[1].toDoubleOrNull() ?: return null
        val lo = m.groupValues[2].toDoubleOrNull() ?: return null
        if (la < -85 || la > 85 || lo < -180 || lo > 180) return null
        return Place("%.5f, %.5f".format(java.util.Locale.US, la, lo), "إحداثيات", la, lo, "pin")
    }

    fun dist(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val x = Math.toRadians(lo2 - lo1) * cos(Math.toRadians((la1 + la2) / 2))
        val y = Math.toRadians(la2 - la1)
        return sqrt(x * x + y * y) * 6371000.0
    }

    /**
     * Folds a name down to what people actually type: no vowel marks, one form for each letter
     * that has several (أ إ آ → ا, ة → ه, ى → ي, Kurdish/Persian letters → the Arabic key),
     * western digits, lower case, and no leading "ال".
     * Must stay identical to norm() in BuildPlaces.java, which builds the index.
     */
    fun norm(s: String?): String {
        if (s == null) return ""
        val b = StringBuilder(s.length)
        for (ch in s) {
            var c = ch
            val code = c.code
            if ((code in 0x064B..0x065F) || code == 0x0670 || code == 0x0640 || (code in 0x06D6..0x06ED) || (code in 0x200B..0x200F)) continue
            c = when (code) {
                0x0623, 0x0625, 0x0622, 0x0671 -> '\u0627'
                0x0649, 0x06CC, 0x06CE, 0x06D2, 0x0626 -> '\u064A'
                0x0624, 0x06C6, 0x06C7 -> '\u0648'
                0x0629, 0x06D5, 0x06C0, 0x06BE -> '\u0647'
                0x06A9, 0x06AF -> '\u0643'
                0x067E -> '\u0628'
                0x0686 -> '\u062C'
                0x06A4 -> '\u0641'
                0x0698 -> '\u0632'
                0x0695 -> '\u0631'
                0x06B5 -> '\u0644'
                else -> c
            }
            if (c.code in 0x0660..0x0669) c = ('0'.code + (c.code - 0x0660)).toChar()
            else if (c.code in 0x06F0..0x06F9) c = ('0'.code + (c.code - 0x06F0)).toChar()
            if (Character.isLetterOrDigit(c)) b.append(Character.toLowerCase(c)) else b.append(' ')
        }
        val out = StringBuilder(b.length)
        for (t0 in b.toString().trim().split(Regex("\\s+"))) {
            if (t0.isEmpty()) continue
            var t = t0
            if (t.length >= 4 && t[0] == '\u0627' && t[1] == '\u0644') t = t.substring(2)
            if (out.isNotEmpty()) out.append(' ')
            out.append(t)
        }
        return out.toString()
    }
}

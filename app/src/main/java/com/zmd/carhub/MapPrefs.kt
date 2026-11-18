package com.zmd.carhub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A spot on the map: a search result, a saved place, or a pin dropped by a long press. */
class Place(
    val name: String,
    val sub: String,
    val lat: Double,
    val lon: Double,
    val kind: String = "",
    val alt: String = "",
) {
    /** Metres from the car (or from the middle of the map); -1 when unknown. */
    var distM: Double = -1.0

    fun json(): JSONObject = JSONObject().put("n", name).put("s", sub).put("la", lat).put("lo", lon).put("k", kind)

    fun sameSpot(o: Place): Boolean = Math.abs(lat - o.lat) < 0.00005 && Math.abs(lon - o.lon) < 0.00005

    companion object {
        fun of(o: JSONObject): Place =
            Place(o.optString("n"), o.optString("s"), o.getDouble("la"), o.getDouble("lo"), o.optString("k"))
    }
}

/** Settings and saved places of the built-in map (kept apart from the hub's own [Prefs]). */
class MapPrefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("map", Context.MODE_PRIVATE)

    /** Save every street the map shows while online, so it works without internet later. */
    var autoSave: Boolean
        get() = sp.getBoolean("auto_save", true)
        set(v) { sp.edit().putBoolean("auto_save", v).apply() }

    /** Upper size of the saved-streets store, in MB. */
    var cacheLimitMb: Int
        get() = sp.getInt("cache_limit_mb", 2048)
        set(v) { sp.edit().putInt("cache_limit_mb", v).apply() }

    /** Tilted view with 3D buildings. */
    var tilt3d: Boolean
        get() = sp.getBoolean("tilt_3d", true)
        set(v) { sp.edit().putBoolean("tilt_3d", v).apply() }

    /** What is drawn where the car is: "arrow", "moto" or a BYD model id (see [VehicleArt.models]). */
    var vehicle: String
        get() = sp.getString("vehicle", "arrow") ?: "arrow"
        set(v) { sp.edit().putString("vehicle", v).apply() }

    /** Paint colour of that vehicle (ARGB). */
    var vehicleColor: Int
        get() = sp.getInt("vehicle_color", VehicleArt.colors[0].color)
        set(v) { sp.edit().putInt("vehicle_color", v).apply() }

    /** Building footprints of all Iraq (download per governorate, or as you drive). */
    var buildingsAlongRoute: Boolean
        get() = sp.getBoolean("buildings_route", true)
        set(v) { sp.edit().putBoolean("buildings_route", v).apply() }

    /** Spoken directions (needs an Arabic voice on the screen). */
    var voice: Boolean
        get() = sp.getBoolean("voice", true)
        set(v) { sp.edit().putBoolean("voice", v).apply() }

    /** How big the map is drawn (labels, roads): 1.0 = phone size; a car screen is read from further away. */
    var scale: Float
        get() = sp.getFloat("scale", 1.2f)
        set(v) { sp.edit().putFloat("scale", v).apply() }

    var lastLat: Double
        get() = sp.getFloat("last_lat", 33.3152f).toDouble()
        set(v) { sp.edit().putFloat("last_lat", v.toFloat()).apply() }

    var lastLon: Double
        get() = sp.getFloat("last_lon", 44.3661f).toDouble()
        set(v) { sp.edit().putFloat("last_lon", v.toFloat()).apply() }

    var lastZoom: Double
        get() = sp.getFloat("last_zoom", 12f).toDouble()
        set(v) { sp.edit().putFloat("last_zoom", v.toFloat()).apply() }

    /** Where the list of offline map files is published. */
    var manifestUrl: String
        get() = sp.getString("manifest_url", null) ?: MapData.DEFAULT_MANIFEST
        set(v) { sp.edit().putString("manifest_url", v).apply() }

    /** Where the all-Iraq buildings file (PMTiles) is published. */
    var buildingsUrl: String
        get() = sp.getString("buildings_url", null) ?: MapBuildings.DEFAULT_URL
        set(v) { sp.edit().putString("buildings_url", v).apply() }

    /** The online tile address in use (it changes with every weekly map release). */
    var tileTemplate: String?
        get() = sp.getString("tile_template", null)
        set(v) { sp.edit().putString("tile_template", v).apply() }

    var tileTemplateAt: Long
        get() = sp.getLong("tile_template_at", 0L)
        set(v) { sp.edit().putLong("tile_template_at", v).apply() }

    // ---- saved places ----

    private fun place(key: String): Place? =
        sp.getString(key, null)?.let { runCatching { Place.of(JSONObject(it)) }.getOrNull() }

    private fun putPlace(key: String, p: Place?) {
        sp.edit().apply { if (p == null) remove(key) else putString(key, p.json().toString()) }.apply()
    }

    var home: Place?
        get() = place("home")
        set(v) = putPlace("home", v)

    var work: Place?
        get() = place("work")
        set(v) = putPlace("work", v)

    private fun list(key: String): ArrayList<Place> {
        val out = ArrayList<Place>()
        val arr = runCatching { JSONArray(sp.getString(key, "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until arr.length()) runCatching { out += Place.of(arr.getJSONObject(i)) }
        return out
    }

    private fun putList(key: String, list: List<Place>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.json()) }
        sp.edit().putString(key, arr.toString()).apply()
    }

    fun saved(): List<Place> = list("saved")

    fun isSaved(p: Place): Boolean = saved().any { it.sameSpot(p) }

    /** Adds or removes a favourite; returns true when it is now saved. */
    fun toggleSaved(p: Place): Boolean {
        val all = list("saved")
        val at = all.indexOfFirst { it.sameSpot(p) }
        if (at >= 0) all.removeAt(at) else all.add(0, p)
        putList("saved", all)
        return at < 0
    }

    fun recents(): List<Place> = list("recents")

    fun addRecent(p: Place) {
        val all = list("recents")
        all.removeAll { it.sameSpot(p) }
        all.add(0, p)
        putList("recents", all.take(12))
    }

    fun clearRecents() = putList("recents", emptyList())
}

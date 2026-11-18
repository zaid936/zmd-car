package com.zmd.carhub

import android.content.Context
import org.json.JSONObject

enum class PhoneType { IPHONE, ANDROID }

/** What the hub listens for, per phone type. */
enum class LinkMode { CABLE, WIRELESS, BOTH }

/** Built-in map styles (OpenFreeMap). AUTO = colourful by day, dark blue at night. */
enum class MapLook(val title: String) { COLOR("نهاري"), LIGHT("فاتح"), NIGHT("ليلي"), AUTO("تلقائي"), VIVID("ملوّن"), SATELLITE("قمر صناعي") }

/** A Bluetooth phone the user has already labelled. type == null means "ignore this device". */
class SavedPhone(val mac: String, val name: String, val type: PhoneType?)

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("hub", Context.MODE_PRIVATE)

    var mapPackage: String?
        get() = sp.getString("map_pkg", null)
        set(v) { sp.edit().putString("map_pkg", v).apply() }

    // Fixed to DiPlay / ZMD Auto (the user's choice): any older saved choice is ignored, and the
    // setters only remain so existing callers still compile.
    var carplayPackage: String
        get() = DEFAULT_CARPLAY
        set(v) { sp.edit().putString("carplay_pkg", v).apply() }

    var autoPackage: String
        get() = DEFAULT_AUTO
        set(v) { sp.edit().putString("auto_pkg", v).apply() }

    /** The app shown next to the map in split-screen. */
    var splitPackage: String?
        get() = sp.getString("split_pkg", null)
        set(v) { sp.edit().putString("split_pkg", v).apply() }

    // Default store: the owner's online store (works from anywhere, even with the PC off).
    // The old GitHub "raw" link is moved to the new one: raw keeps a 5-minute old copy, the new
    // link always gives the newest list, so a just-uploaded app shows at once.
    var storeUrl: String
        get() {
            val v = sp.getString("store_url", null)?.trim().orEmpty()
            return if (v.isEmpty() || v == OLD_STORE) DEFAULT_STORE else v
        }
        set(v) { sp.edit().putString("store_url", v).apply() }

    var startOnBoot: Boolean
        get() = sp.getBoolean("start_on_boot", true)
        set(v) { sp.edit().putBoolean("start_on_boot", v).apply() }

    var backToMap: Boolean
        get() = sp.getBoolean("back_to_map", true)
        set(v) { sp.edit().putBoolean("back_to_map", v).apply() }

    /**
     * Open CarPlay / Android Auto by itself the moment the phone connects. Default off: the phone's
     * icon just lights up and the user taps it when ready — so they can answer the USB permission
     * prompt before the receiver takes the screen.
     */
    var autoOpenReceiver: Boolean
        get() = sp.getBoolean("auto_open_receiver", false)
        set(v) { sp.edit().putBoolean("auto_open_receiver", v).apply() }

    /** Floating icon size in dp. */
    var iconDp: Int
        get() = sp.getInt("icon_dp", 56)
        set(v) { sp.edit().putInt("icon_dp", v).apply() }

    /** Floating bar position in pixels; -1 = not placed yet (defaults to top-right). */
    var barX: Int
        get() = sp.getInt("bar_x", -1)
        set(v) { sp.edit().putInt("bar_x", v).apply() }

    var barY: Int
        get() = sp.getInt("bar_y", -1)
        set(v) { sp.edit().putInt("bar_y", v).apply() }

    /** Look of the built-in map (settings → عام). */
    var mapLook: MapLook
        get() = runCatching { MapLook.valueOf(sp.getString("map_look", null) ?: "") }.getOrDefault(MapLook.COLOR)
        set(v) { sp.edit().putString("map_look", v.name).apply() }

    /** The first-run setup screen was finished (all required permissions were granted then). */
    var setupDone: Boolean
        get() = sp.getBoolean("setup_done", false)
        set(v) { sp.edit().putBoolean("setup_done", v).apply() }

    /** A permission's system dialog was shown once; after that a denial goes to the app's settings page. */
    fun wasAsked(p: Perm): Boolean = sp.getBoolean("asked_" + p.name, false) ||
        (p == Perm.LOCATION && sp.getBoolean("location_asked", false))

    fun markAsked(p: Perm) {
        sp.edit().putBoolean("asked_" + p.name, true).apply()
    }

    fun pkg(type: PhoneType): String = if (type == PhoneType.IPHONE) carplayPackage else autoPackage

    fun mode(type: PhoneType): LinkMode =
        runCatching { LinkMode.valueOf(sp.getString("mode_" + type.name, null) ?: "BOTH") }.getOrDefault(LinkMode.BOTH)

    fun setMode(type: PhoneType, mode: LinkMode) {
        sp.edit().putString("mode_" + type.name, mode.name).apply()
    }

    // ---- Bluetooth phones the user has labelled ----

    private fun phonesJson(): JSONObject =
        runCatching { JSONObject(sp.getString("phones", "{}") ?: "{}") }.getOrDefault(JSONObject())

    fun phones(): List<SavedPhone> {
        val j = phonesJson()
        val out = ArrayList<SavedPhone>()
        for (mac in j.keys()) {
            val o = j.optJSONObject(mac) ?: continue
            val type = runCatching { PhoneType.valueOf(o.optString("type")) }.getOrNull()
            out += SavedPhone(mac, o.optString("name", mac), type)
        }
        return out.sortedBy { it.name.lowercase() }
    }

    fun isKnown(mac: String): Boolean = phonesJson().has(mac)

    fun phoneType(mac: String): PhoneType? = phones().firstOrNull { it.mac == mac }?.type

    fun savePhone(mac: String, name: String, type: PhoneType?) {
        val j = phonesJson()
        j.put(mac, JSONObject().put("name", name).put("type", type?.name ?: "IGNORE"))
        sp.edit().putString("phones", j.toString()).apply()
    }

    fun removePhone(mac: String) {
        val j = phonesJson()
        j.remove(mac)
        sp.edit().putString("phones", j.toString()).apply()
    }

    companion object {
        const val DEFAULT_CARPLAY = "com.shihab.diplay"
        // Android Auto is BUILT INTO ZMD Car (the ZMD Auto engine, com.zmd:auto-engine, runs in our
        // own ":auto" process), so the Android Auto "app" is ZMD Car itself: nothing to install.
        // It replaces the public DiAuto, which grabs every USB device and must not stay installed.
        const val DEFAULT_AUTO = "com.zmd.carhub"
        /** The separate ZMD Auto app (same engine, for screens without ZMD Car). It steps aside by itself here. */
        const val SEPARATE_AUTO = "com.zmd.auto"
        const val OLD_AUTO = "com.andrerinas.headunitrevived"
        const val DEFAULT_STORE = "https://zmd-store.kc5zqt2pcd.workers.dev/index.json"
        const val OLD_STORE = "https://raw.githubusercontent.com/zaid936/zmd-car-store/main/index.json"
    }
}

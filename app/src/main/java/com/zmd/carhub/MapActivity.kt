package com.zmd.carhub

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The hub's home screen: our own map. Offline map of Iraq (downloaded pack), saved streets and
 * the internet behind one engine ([MapTiles]); search ([MapSearch]); directions ([MapRoute]) and
 * turn-by-turn navigation with voice ([NavEngine]); a clean "filming" mode. The floating bar
 * from [HubService] sits in the top-right corner, so everything here keeps to the left side.
 */
class MapActivity : Activity() {
    private lateinit var map: MapView
    private var ml: MapLibreMap? = null
    private var style: Style? = null
    private var styleKey: String? = null
    private lateinit var prefs: Prefs
    private lateinit var mp: MapPrefs
    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val router = Executors.newSingleThreadExecutor()

    // Views.
    private lateinit var root: FrameLayout
    private lateinit var left: LinearLayout
    private lateinit var searchBar: View
    private lateinit var searchBox: EditText
    private lateinit var banner: LinearLayout
    private lateinit var panelHost: FrameLayout
    private lateinit var bottomRow: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var navCard: LinearLayout
    private lateinit var speedChip: LinearLayout
    /** Street speed limit, car speed and speed-camera warnings (map session's DriveHud). */
    private lateinit var hud: DriveHud
    /** Side bar: clock, date, weather, favourite apps. */
    private lateinit var rail: MapRail
    private var themeAtStart: String? = null
    private lateinit var speedText: TextView
    private lateinit var zoomCol: LinearLayout
    private lateinit var compassBtn: ImageView
    private lateinit var locateBtn: ImageView
    private lateinit var downloadBtn: ImageView
    private lateinit var flashView: TextView
    private var card: View? = null
    /** "Not now" on a first-run card: don't ask again until the app is reopened. */
    private var skipBundled = false
    private var skipPack = false

    // Location.
    private var lm: LocationManager? = null
    private var lastFix: Location? = null
    private var lastGpsAt = 0L
    private var locating = false
    private var following = true
    private var lastSavedAt = 0L

    // What is on screen.
    private enum class Mode { BROWSE, NAV, CINEMA }
    private var mode = Mode.BROWSE
    private var panel = ""
    private var selected: Place? = null
    private var route: Route? = null
    private var routeDest: Place? = null
    private var searchToken = 0
    private var ignoreText = false

    // Navigation.
    private var nav: NavEngine? = null
    private var voice: NavVoice? = null
    private var offCount = 0
    private var rerouting = false
    private var lastRerouteAt = 0L
    private var lastDoneAt = 0L
    private var lastZoomSet = 0.0
    private var arrivedShown = false
    private lateinit var bannerArrow: ImageView
    private lateinit var bannerDist: TextView
    private lateinit var bannerText: TextView
    private lateinit var bannerThen: TextView
    private lateinit var navEta: TextView
    private lateinit var navLeft: TextView
    private lateinit var muteBtn: ImageView
    private lateinit var simSpeedBtn: TextView

    // Simulated drive (for trying a route, and for filming).
    private var simulating = false
    private var simAlong = 0.0
    private var simFactor = 1
    private var simLast = 0L

    // Filming mode.
    private var beforeCinema = Mode.BROWSE
    private var cinemaBearing = 0.0

    private val dataListener: (MapData.Status) -> Unit = { onDataStatus(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        mp = MapPrefs(this)
        // First launch, or a required permission is missing: the setup screen comes first.
        if (!prefs.setupDone || Perms.missingRequired(this).isNotEmpty()) {
            startActivity(Perms.setupIntent(this))
            finish()
            return
        }
        // Another maps app chosen as the home map (Waze, Darbouna...): open it on top, the bar floats over it.
        if (savedInstanceState == null && intent?.action != Intent.ACTION_VIEW && intent?.getBooleanExtra(EXTRA_FROM_HOME, false) != true) openChosenMap()

        // Our own tile engine must be in place before the map asks for its first tile.
        MapTiles.init(this)
        MapReports.init(this)
        themeAtStart = Ui.theme(this).id
        val options = MapLibreMapOptions.createFromAttributes(this)
            .pixelRatio(resources.displayMetrics.density * mp.scale)
            .compassEnabled(false)
            .logoEnabled(false)
            .attributionEnabled(false)
            .camera(CameraPosition.Builder().target(LatLng(mp.lastLat, mp.lastLon)).zoom(mp.lastZoom).build())
        map = MapView(this, options)
        map.onCreate(savedInstanceState)
        map.getMapAsync { m ->
            ml = m
            m.setMinZoomPreference(3.0)
            m.setMaxZoomPreference(20.0)
            m.addOnMapLongClickListener { at ->
                if (mode == Mode.BROWSE) dropPin(at)
                true
            }
            m.addOnMapClickListener { at -> onMapTap(at) }
            m.addOnCameraMoveListener { updateCompass(); paintCar() }
            m.addOnCameraIdleListener { paintCameras() }
            applyStyle()
        }
        buildUi()
        setContentView(root)
        MapData.addListener(dataListener)
        MapBuildings.addListener(onBuildings)
        MapReports.addListener(onReports)
        // Files copied in by hand are picked up, and the road network is loaded ahead of time.
        bg.execute {
            val had = MapData.complete(this)
            MapData.adoptCopied(this)
            if (!had && MapData.tilesFile(this) != null) {
                MapTiles.reloadPack()
                MapTiles.retryFailed()
            }
            MapRoute.warmUp(this)
        }
        handleViewIntent(intent)
    }

    // ================================================================== style

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES ||
            Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 18 || it < 6 }

    /** The look picked by the user: colourful day, quiet light, deep-navy night, or day/night by the clock. */
    private fun lookNow(): String = when (prefs.mapLook) {
        MapLook.COLOR -> "day"
        MapLook.LIGHT -> "light"
        MapLook.NIGHT -> "night"
        MapLook.AUTO -> if (isNight()) "night" else "day"
        MapLook.VIVID -> "vivid"
        MapLook.SATELLITE -> "satellite"
    }

    private fun applyStyle() {
        val m = ml ?: return
        val key = lookNow()
        if (key == styleKey) return
        val json = runCatching { assets.open("map/style-$key.json").bufferedReader().use { it.readText() } }.getOrNull() ?: return
        styleKey = key
        style = null
        locating = false
        m.setStyle(Style.Builder().fromJson(json)) { s ->
            style = s
            addOverlays(s)
            startLocationLayer(s)
            drawRoute()
            drawPins()
        }
    }

    private val autoLook = object : Runnable {
        override fun run() {
            applyStyle()
            ui.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    /**
     * One storey-and-a-bay of a building's wall: [wall] colour with two windows (one [lit], one [dark]
     * at night), a sill line between floors. Repeated over the walls it reads as a block of flats.
     */
    private fun windowTile(wall: Int, lit: Int, dark: Int): android.graphics.Bitmap {
        val w = 32
        val h = 24
        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        c.drawColor(wall)
        p.color = (wall and 0x00FFFFFF) or 0x33000000
        c.drawRect(0f, h - 2f, w.toFloat(), h.toFloat(), p)           // floor line
        p.color = lit
        c.drawRect(4f, 6f, 13f, 17f, p)
        p.color = dark
        c.drawRect(19f, 6f, 28f, 17f, p)
        p.color = 0x55FFFFFF
        c.drawRect(4f, 6f, 13f, 8f, p)                               // light on the glass
        return b
    }

    private fun addOverlays(s: Style) {
        // Window walls for the apartment blocks (fill-extrusion-pattern in the styles).
        s.addImage("zmd-windows-day", windowTile(0xFFE9E1D4.toInt(), 0xFF7E9DBE.toInt(), 0xFF7E9DBE.toInt()))
        s.addImage("zmd-windows-light", windowTile(0xFFEDEDEA.toInt(), 0xFFA3B3C4.toInt(), 0xFFA3B3C4.toInt()))
        s.addImage("zmd-windows-night", windowTile(0xFF22345A.toInt(), 0xFFFFD27A.toInt(), 0xFF1A2742.toInt()))
        s.addImage(PIN_RED, MapIcons.pinBitmap(this, 0xFFE5484D.toInt()))
        s.addImage(PIN_BLUE, MapIcons.pinBitmap(this, Ui.ACCENT))
        s.addSource(GeoJsonSource(SRC_ROUTE))
        s.addSource(GeoJsonSource(SRC_DONE))
        s.addSource(GeoJsonSource(SRC_ALTS))
        s.addSource(GeoJsonSource(SRC_PINS))
        for (t in MapReports.types) s.addImage("rep-${t.id}", MapReports.icon(this, t, 34f * mp.scale))
        s.addSource(GeoJsonSource(SRC_REPORTS, MapReports.geoJson()))
        s.addImage("cam-fixed", MapReports.icon(this, MapReports.type("camera"), 30f * mp.scale))
        s.addSource(GeoJsonSource(SRC_CAMS))
        fun width(scale: Float) = PropertyFactory.lineWidth(
            Expression.interpolate(
                Expression.exponential(1.5f), Expression.zoom(),
                Expression.stop(6, 3.5f * scale), Expression.stop(12, 6f * scale), Expression.stop(16, 11f * scale), Expression.stop(19, 22f * scale)
            )
        )
        val round = arrayOf(PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND))
        val casing = LineLayer(L_ROUTE_CASING, SRC_ROUTE).withProperties(PropertyFactory.lineColor(0xFF0B3C8C.toInt()), width(1.45f), *round)
        val line = LineLayer(L_ROUTE, SRC_ROUTE).withProperties(PropertyFactory.lineColor(0xFF2E8BFF.toInt()), width(1f), *round)
        val done = LineLayer(L_DONE, SRC_DONE).withProperties(PropertyFactory.lineColor(0xFF8794AA.toInt()), width(1f), *round)
        // Under the first name label, so street names stay readable on top of the route.
        val below = s.layers.firstOrNull { it is SymbolLayer && !it.id.startsWith("road_one_way") }?.id
        // Fixed speed cameras (SpeedCameras list), with their limit when known.
        s.addLayer(SymbolLayer(L_CAMS, SRC_CAMS).withProperties(
            PropertyFactory.iconImage("cam-fixed"),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(11, 0.5f), Expression.stop(15, 0.85f))),
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textSize(12f * mp.scale),
            PropertyFactory.textOffset(arrayOf(0f, 1.6f)),
            PropertyFactory.textColor(0xFF8E4EC6.toInt()),
            PropertyFactory.textHaloColor(0xFFFFFFFF.toInt()),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textAllowOverlap(true)
        ).apply { minZoom = 11f })
        // Road reports (potholes, bumps, police...) above the route.
        s.addLayer(SymbolLayer(L_REPORTS, SRC_REPORTS).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(10, 0.45f), Expression.stop(14, 0.8f), Expression.stop(17, 1.0f)))
        ).apply { minZoom = 10f })
        val altLine = LineLayer(L_ALTS, SRC_ALTS).withProperties(PropertyFactory.lineColor(0xFF9AA7BA.toInt()), width(0.9f), PropertyFactory.lineOpacity(0.85f), *round)
        for (layer in listOf(altLine, casing, line, done)) {
            if (below != null) s.addLayerBelow(layer, below) else s.addLayer(layer)
        }
        s.addLayer(
            SymbolLayer(L_PINS, SRC_PINS).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        )
    }

    private fun drawRoute() {
        val s = style ?: return
        val r = route
        s.getSourceAs<GeoJsonSource>(SRC_ALTS)?.let { alts ->
            val others = if (nav == null) routeAlts.filter { it !== r } else emptyList()
            alts.setGeoJson(FeatureCollection.fromFeatures(others.map { o ->
                Feature.fromGeometry(LineString.fromLngLats((0 until o.size).map { Point.fromLngLat(o.lon(it), o.lat(it)) }))
            }))
        }
        val src = s.getSourceAs<GeoJsonSource>(SRC_ROUTE) ?: return
        if (r == null) {
            src.setGeoJson(FeatureCollection.fromFeatures(arrayOf<Feature>()))
            s.getSourceAs<GeoJsonSource>(SRC_DONE)?.setGeoJson(FeatureCollection.fromFeatures(arrayOf<Feature>()))
            return
        }
        val pts = ArrayList<Point>(r.size)
        for (i in 0 until r.size) pts += Point.fromLngLat(r.lon(i), r.lat(i))
        src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(pts)))
        drawDone()
    }

    /** Greys out the part of the route already driven. */
    private fun drawDone() {
        val s = style ?: return
        val src = s.getSourceAs<GeoJsonSource>(SRC_DONE) ?: return
        val n = nav
        val r = route
        if (n == null || r == null || mode == Mode.BROWSE || n.seg < 0) {
            src.setGeoJson(FeatureCollection.fromFeatures(arrayOf<Feature>()))
            return
        }
        val pts = ArrayList<Point>(n.seg + 2)
        for (i in 0..min(n.seg, r.size - 1)) pts += Point.fromLngLat(r.lon(i), r.lat(i))
        pts += Point.fromLngLat(n.snapLon, n.snapLat)
        if (pts.size >= 2) src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(pts)))
    }

    private fun drawPins() {
        val s = style ?: return
        val src = s.getSourceAs<GeoJsonSource>(SRC_PINS) ?: return
        val feats = ArrayList<Feature>()
        val dest = routeDest
        val sel = selected
        if (dest != null) feats += Feature.fromGeometry(Point.fromLngLat(dest.lon, dest.lat)).apply { addStringProperty("icon", PIN_RED) }
        if (sel != null && (dest == null || !sel.sameSpot(dest))) {
            feats += Feature.fromGeometry(Point.fromLngLat(sel.lon, sel.lat)).apply { addStringProperty("icon", PIN_BLUE) }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(feats))
    }

    // ================================================================== location

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The "you are here" arrow. We feed it ourselves (real GPS, or the simulated drive). */
    @android.annotation.SuppressLint("MissingPermission")
    private fun startLocationLayer(s: Style) {
        val m = ml ?: return
        runCatching {
            val lc = m.locationComponent
            val look = vehicleLook(s)
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(this, s)
                    .locationComponentOptions(look)
                    .useDefaultLocationEngine(false)
                    .build()
            )
            lc.isLocationComponentEnabled = true
            lc.renderMode = RenderMode.GPS
            lc.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
                override fun onCameraTrackingDismissed() {
                    following = false
                    paintLocate()
                    if (mode == Mode.NAV) ui.postDelayed(recenter, 9000)
                }

                override fun onCameraTrackingChanged(currentMode: Int) {}
            })
            locating = true
            lastFix?.let { lc.forceLocationUpdate(it) }
            if (following && lastFix != null) follow(animated = false)
        }
    }

    /**
     * The location marker: the standard arrow, or the vehicle chosen in the look settings
     * (motorbike / BYD model, any colour), drawn by [VehicleArt] and turned with the car's heading.
     */
    private fun vehicleLook(s: Style): LocationComponentOptions {
        val b = LocationComponentOptions.builder(this)
            .pulseEnabled(true)
            .pulseColor(Ui.ACCENT)
            .accuracyColor(Ui.ACCENT)
            .accuracyAlpha(0.12f)
            .trackingGesturesManagement(true)
        val model = VehicleArt.model(mp.vehicle)
        if (model.kind == VehicleArt.Kind.PHOTO) {
            s.addImage(VEHICLE_BG, android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888))
            b.gpsName(VEHICLE_BG).foregroundName(VEHICLE_BG).bearingName(VEHICLE_BG)
                .backgroundName(VEHICLE_BG).foregroundStaleName(VEHICLE_BG).backgroundStaleName(VEHICLE_BG)
                .pulseEnabled(false).accuracyAlpha(0f)
            loadCarFrames(s, model)
            return b.build()
        }
        run {
            val night = currentLookIsNight()
            // Arrows: blue unless another colour was picked; cars: their paint colour.
            val paint = if (model.kind == VehicleArt.Kind.ARROW && mp.vehicleColor == VehicleArt.colors[0].color) Ui.ACCENT else mp.vehicleColor
            val art = VehicleArt.bitmap(this, model, paint, if (night) 0xFF4FC3FF.toInt() else Ui.ACCENT,
                54f * mp.scale)
            s.addImage(VEHICLE_IMG, art)
            s.addImage(VEHICLE_BG, android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888))
            b.gpsName(VEHICLE_IMG).foregroundName(VEHICLE_IMG).bearingName(VEHICLE_IMG)
                .backgroundName(VEHICLE_BG).foregroundStaleName(VEHICLE_IMG).backgroundStaleName(VEHICLE_BG)
                .pulseEnabled(false)
                .minZoomIconScale(0.55f).maxZoomIconScale(1.0f)
        }
        return b.build()
    }

    /** Re-draws the marker after the vehicle or its colour changed. */
    private fun applyVehicle() {
        val m = ml ?: return
        val s = m.style ?: return
        runCatching { m.locationComponent.applyStyle(vehicleLook(s)) }
        if (VehicleArt.model(mp.vehicle).kind != VehicleArt.Kind.PHOTO) {
            (s.getSource(SRC_ME) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        } else paintCar()
    }

    // ---------------------------------------------------------------- the 3D car

    /*
     * Like the big navigation apps: the car is a real model pre-rendered from the tilted camera at 24
     * headings (assets/vehicles3d), shown standing up towards the viewer (not flattened by the tilt);
     * the picture matching the car's heading relative to the map is used. Seen from straight above
     * (flat map), the top view (assets/vehicles) turns with the heading instead. Between GPS fixes the
     * car glides to the new spot, so it never jumps.
     */
    private var carShown: LatLng? = null
    private var carFrom: LatLng? = null
    private var carTo: LatLng? = null
    private var carBearing = 0f
    private var carAnimStart = 0L
    private var carAnimMs = 950L
    private var carLastFixAt = 0L
    private val carAnim = object : Runnable {
        override fun run() {
            val a = carFrom ?: return
            val b = carTo ?: return
            val t = ((SystemClock.elapsedRealtime() - carAnimStart) / carAnimMs.toDouble()).coerceIn(0.0, 1.0)
            carShown = LatLng(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
            paintCar()
            if (t < 1.0) ui.postDelayed(this, 33)
        }
    }

    private fun loadCarFrames(s: Style, model: VehicleArt.Model) {
        val key = VehicleArt.colors.firstOrNull { it.color == mp.vehicleColor }?.key ?: "white"
        for (k in 0 until 24) {
            runCatching {
                assets.open("vehicles3d/${model.style}-$key-${String.format(java.util.Locale.US, "%02d", k)}.webp").use { android.graphics.BitmapFactory.decodeStream(it) }
            }.getOrNull()?.let { s.addImage("car-$k", it) }
        }
        VehicleArt.bitmap(this, model, mp.vehicleColor, Ui.ACCENT, 80f).let { s.addImage("car-top", it) }
        if (s.getSource(SRC_ME) == null) s.addSource(GeoJsonSource(SRC_ME))
        if (s.getLayer(L_ME) == null) {
            carFlat = null
            s.addLayer(SymbolLayer(L_ME, SRC_ME).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconRotate(Expression.get("rot")),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                PropertyFactory.iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                    Expression.stop(12, 0.42f * mp.scale), Expression.stop(16, 0.62f * mp.scale), Expression.stop(18, 0.8f * mp.scale)))
            ))
        }
        paintCar()
    }

    /** Puts the car where it is now, with the picture for its heading and the map's angle. */
    private fun paintCar() {
        val m = ml ?: return
        val s = m.style ?: return
        if (VehicleArt.model(mp.vehicle).kind != VehicleArt.Kind.PHOTO) return
        val at = carShown ?: lastFix?.let { LatLng(it.latitude, it.longitude) } ?: return
        val cam = m.cameraPosition
        val f = if (cam.tilt < 15) {
            // Flat map: top view, turned with the heading on the map itself.
            Feature.fromGeometry(Point.fromLngLat(at.longitude, at.latitude)).apply {
                addStringProperty("icon", "car-top"); addNumberProperty("rot", carBearing); addStringProperty("align", "map")
            }
        } else {
            val rel = (((carBearing - cam.bearing) % 360) + 360) % 360
            val frame = ((24 - Math.round(rel / 15.0).toInt()) % 24 + 24) % 24
            Feature.fromGeometry(Point.fromLngLat(at.longitude, at.latitude)).apply {
                addStringProperty("icon", "car-$frame"); addNumberProperty("rot", 0); addStringProperty("align", "viewport")
            }
        }
        // Flat map: the top view turns with the map; tilted: the standing picture faces the viewer.
        val flat = cam.tilt < 15
        if (flat != carFlat) {
            carFlat = flat
            s.getLayer(L_ME)?.setProperties(PropertyFactory.iconRotationAlignment(
                if (flat) Property.ICON_ROTATION_ALIGNMENT_MAP else Property.ICON_ROTATION_ALIGNMENT_VIEWPORT))
        }
        (s.getSource(SRC_ME) as? GeoJsonSource)?.setGeoJson(f)
    }

    private var carFlat: Boolean? = null

    private fun moveCar(loc: Location) {
        if (VehicleArt.model(mp.vehicle).kind != VehicleArt.Kind.PHOTO) return
        if (loc.hasBearing() && (!loc.hasSpeed() || loc.speed > 1.5f)) carBearing = loc.bearing
        val to = LatLng(loc.latitude, loc.longitude)
        carFrom = carShown ?: to
        carTo = to
        // Glide over the time between fixes (1 s on real GPS, a quarter second in a fast simulated drive),
        // so the car never trails behind the camera.
        val now = SystemClock.elapsedRealtime()
        carAnimMs = (now - carLastFixAt).coerceIn(80L, 1000L)
        carLastFixAt = now
        carAnimStart = now
        ui.removeCallbacks(carAnim)
        ui.post(carAnim)
    }

    private fun currentLookIsNight(): Boolean = lookNow() == "night" || lookNow() == "satellite"

    private val recenter = Runnable { if (mode == Mode.NAV && !following) follow() }

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            lastGpsAt = SystemClock.elapsedRealtime()
            onFix(l, false)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val netListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            // The rough network position only counts while the GPS has nothing.
            if (SystemClock.elapsedRealtime() - lastGpsAt > 15000) onFix(l, false)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startGps() {
        if (!hasLocationPermission()) return
        val m = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        lm = m
        runCatching {
            if (m.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 700L, 0f, gpsListener, Looper.getMainLooper())
            }
        }
        runCatching {
            if (m.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 10f, netListener, Looper.getMainLooper())
            }
        }
        if (lastFix == null) {
            val known = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { p -> runCatching { m.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
            if (known != null) onFix(known, false)
        }
    }

    private fun stopGps() {
        runCatching { lm?.removeUpdates(gpsListener) }
        runCatching { lm?.removeUpdates(netListener) }
    }

    /** Every position, real or simulated, comes through here. */
    private fun onFix(loc: Location, sim: Boolean) {
        if (simulating && !sim) return
        val first = lastFix == null
        lastFix = loc
        val m = ml
        if (m != null && locating) runCatching { m.locationComponent.forceLocationUpdate(loc) }
        moveCar(loc)
        if (first && following && mode == Mode.BROWSE) follow(animated = false)
        paintSpeed(loc)
        if (!sim) {
            MapTiles.prefetchAround(loc.latitude, loc.longitude)
            if (!simulating) MapBuildings.prefetchAhead(loc.latitude, loc.longitude,
                if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 3f) loc.bearing else null, MapTiles.online)
            val now = SystemClock.elapsedRealtime()
            if (now - lastSavedAt > 30000) {
                lastSavedAt = now
                mp.lastLat = loc.latitude
                mp.lastLon = loc.longitude
            }
        }
        if (mode == Mode.NAV || (mode == Mode.CINEMA && nav != null)) navTick(loc)
        reportsTick(loc)
        if (::hud.isInitialized) hud.update(loc)
        if (::rail.isInitialized && !sim) rail.place(loc.latitude, loc.longitude, MapTiles.online)
    }

    /** Pick an app to add to the side bar. */
    private fun showAppChooser() {
        val c = this
        hideKeyboard()
        val col = sheet()
        col.addView(title("ضيف برنامج للشريط"))
        val have = rail.favourites().toSet()
        val grid = Ui.vbox(c)
        val all = Apps.launchable(c).filter { it.pkg !in have && !rail.isProjection(it.pkg) }
        for (row in all.chunked(4)) {
            val line = Ui.hbox(c)
            for (a in row) {
                val cell = Ui.vbox(c).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 8))
                    background = Ui.ripple(c, null, 14f)
                    isClickable = true
                    setOnClickListener { rail.add(a.pkg); closePanel(); flash("انضاف ${a.label} ✓") }
                }
                cell.addView(ImageView(c).apply { setImageDrawable(a.icon) }, Ui.lp(Ui.dp(c, 52), Ui.dp(c, 52)))
                cell.addView(Ui.label(c, a.label, 12f, Ui.SUB).apply {
                    maxLines = 2; gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
                })
                line.addView(cell, Ui.lp(0, Ui.WRAP, 1f))
            }
            repeat(4 - row.size) { line.addView(View(c), Ui.lp(0, 1, 1f)) }
            grid.addView(line)
        }
        if (all.isEmpty()) grid.addView(note("كل البرامج موجودة بالشريط."))
        col.addView(grid)
        showPanel("apps", col)
    }

    // ================================================================== road reports (Waze-like)

    /** Speed cameras inside the visible map (drawn from zoom 11). */
    private fun paintCameras() {
        val m = ml ?: return
        val src = m.style?.getSourceAs<GeoJsonSource>(SRC_CAMS) ?: return
        if (m.cameraPosition.zoom < 11) { src.setGeoJson(FeatureCollection.fromFeatures(arrayOf<Feature>())); return }
        val b = m.projection.visibleRegion.latLngBounds
        val cams = runCatching { SpeedCameras.inBox(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast) }.getOrDefault(emptyList())
        src.setGeoJson(FeatureCollection.fromFeatures(cams.take(400).map { cam ->
            Feature.fromGeometry(Point.fromLngLat(cam.lon, cam.lat)).apply { addStringProperty("label", cam.speed?.toString() ?: "") }
        }))
    }

    private val onReports: () -> Unit = {
        (style?.getSource(SRC_REPORTS) as? GeoJsonSource)?.setGeoJson(MapReports.geoJson())
    }

    /** Fetches reports around, warns before the next one ahead, and asks about the one just passed. */
    private fun reportsTick(loc: Location) {
        MapReports.refresh(loc.latitude, loc.longitude, MapTiles.online)
        val moving = loc.hasSpeed() && loc.speed > 2.5f
        val bearing = if (moving && loc.hasBearing()) loc.bearing else null
        // Further ahead at speed (about 12 seconds of driving, at least 250 m).
        val range = maxOf(250.0, (if (loc.hasSpeed()) loc.speed.toDouble() else 0.0) * 12)
        if (moving) MapReports.ahead(loc.latitude, loc.longitude, bearing, range)?.let { (r, d) ->
            val t = MapReports.type(r.type)
            val dist = if (d >= 100) "${(d / 50).toInt() * 50} متر" else "شوية"
            flash("⚠ ${t.name} بعد $dist", 5000)
            if (mp.voice) {
                if (voice == null) voice = NavVoice(this)
                voice?.say("انتبه، ${t.name} بعد $dist")
            }
        }
        MapReports.justPassed(loc.latitude, loc.longitude)?.let { askStillThere(it) }
    }

    private var askCard: View? = null

    /** "Still there?" after driving past a report: yes keeps it, no removes it (when others agree). */
    private fun askStillThere(r: MapReports.Report) {
        val c = this
        askCard?.let { root.removeView(it) }
        val t = MapReports.type(r.type)
        val card = Ui.hbox(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.floatingGlass(c, 22f, opaque = true)
            setPadding(Ui.dp(c, 16), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 10))
        }
        card.addView(ImageView(c).apply { setImageBitmap(MapReports.icon(c, t, 30f)) }, Ui.lp(Ui.dp(c, 46), Ui.dp(c, 46)))
        card.addView(Ui.label(c, "${t.name} بعدها موجودة؟", 17f, bold = true), Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = Ui.dp(c, 8); marginEnd = Ui.dp(c, 12) })
        fun answer(still: Boolean) {
            MapReports.vote(r.id, still)
            root.removeView(card)
            askCard = null
            flash(if (still) "شكراً ✓" else "شكراً، راح تنشال إذا غيرك هم أكد")
        }
        card.addView(Ui.button(c, "نعم", primary = true) { answer(true) })
        card.addView(Ui.button(c, "لا") { answer(false) }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = Ui.dp(c, 8) })
        root.addView(card, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.dp(c, 110) })
        askCard = card
        ui.postDelayed({ if (askCard === card) { root.removeView(card); askCard = null } }, 12000)
    }

    /** The report menu: one big button per kind, reported where the car is now. */
    private fun showReportMenu() {
        val c = this
        hideKeyboard()
        val col = sheet()
        col.addView(title("بلّغ عن شي بالطريق"))
        col.addView(note("ينضاف بمكانك هسه ويشوفه كل اللي عدهم ZMD Car، ويحذرهم قبل ما يوصلون."))
        val grid = Ui.vbox(c)
        for (row in MapReports.types.chunked(4)) {
            val line = Ui.hbox(c)
            for (t in row) {
                val cell = Ui.vbox(c).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 8))
                    background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Ui.dp(c, 16).toFloat(); setColor(0x14FFFFFF) }
                    setOnClickListener {
                        val fix = lastFix
                        if (fix == null) { flash("ما معروف مكانك بعد (GPS)"); return@setOnClickListener }
                        closePanel()
                        flash("دا يرسل البلاغ...")
                        MapReports.add(t.id, fix.latitude, fix.longitude,
                            if (fix.hasBearing() && fix.hasSpeed() && fix.speed > 2.5f) fix.bearing else null) { msg -> flash(msg, 3500) }
                    }
                }
                cell.addView(ImageView(c).apply { setImageBitmap(MapReports.menuIcon(c, t)) }, Ui.lp(Ui.dp(c, 70), Ui.dp(c, 70)))
                cell.addView(Ui.label(c, t.name, 14f).apply { gravity = Gravity.CENTER })
                line.addView(cell, Ui.lp(0, Ui.WRAP, 1f).apply { marginEnd = Ui.dp(c, 6); bottomMargin = Ui.dp(c, 6) })
            }
            grid.addView(line)
        }
        col.addView(grid)
        showPanel("report", col)
    }

    private fun here(): LatLng {
        lastFix?.let { return LatLng(it.latitude, it.longitude) }
        return ml?.cameraPosition?.target ?: LatLng(mp.lastLat, mp.lastLon)
    }

    /** Camera back on the car: heading-up and tilted while navigating, north-up otherwise. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun follow(animated: Boolean = true) {
        val m = ml ?: return
        ui.removeCallbacks(recenter)
        if (!locating || lastFix == null) {
            if (!hasLocationPermission()) requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            else flash("دا ندوّر على موقعك...")
            return
        }
        following = true
        runCatching {
            val lc = m.locationComponent
            val navigating = mode == Mode.NAV || (mode == Mode.CINEMA && nav != null)
            val tilt = if (!mp.tilt3d) 0.0 else if (navigating) 50.0 else 40.0
            val h = map.height.toDouble()
            lc.paddingWhileTracking(doubleArrayOf(0.0, if (navigating) h * 0.30 else 0.0, 0.0, 0.0))
            lc.cameraMode = if (navigating) CameraMode.TRACKING_GPS else CameraMode.TRACKING
            val zoom = if (navigating) 17.6 else max(m.cameraPosition.zoom, 16.0)
            lastZoomSet = zoom
            lc.zoomWhileTracking(zoom, if (animated) 900L else 1L)
            lc.tiltWhileTracking(tilt, if (animated) 900L else 1L)
        }
        paintLocate()
    }

    private fun unfollow() {
        following = false
        runCatching { ml?.locationComponent?.cameraMode = CameraMode.NONE }
        paintLocate()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) startGps()
    }

    // ================================================================== lifecycle

    /**
     * Started again from the launcher / car start (singleTask): hand over to the chosen maps app.
     * Coming back to this screen with the back key does not, so it can't bounce in a loop.
     */
    /** Left of the search column (after the side bar). */
    private var leftShift = 0

    /**
     * Width of the search column for the window as it is now: wide on the full screen, and
     * filling the space next to the side bar when the map is one half of a split screen.
     */
    private fun columnWidth(): Int {
        val c = this
        val w = resources.configuration.screenWidthDp.let { if (it > 0) Ui.dp(c, it.toFloat()) else resources.displayMetrics.widthPixels }
        val room = w - leftShift - Ui.dp(c, 96)   // keep the zoom buttons on the right free
        return max(Ui.dp(c, 220), min(Ui.dp(c, 460), if (w < Ui.dp(c, 900)) room else (w * 0.40f).toInt()))
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Split screen resizes the window without restarting: fit the column again.
        if (::left.isInitialized) {
            val cw = columnWidth()
            (left.layoutParams as? FrameLayout.LayoutParams)?.let { it.width = cw; left.layoutParams = it }
            (navCard.layoutParams as? LinearLayout.LayoutParams)?.let { it.width = min(Ui.dp(this, 400), cw); navCard.layoutParams = it }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && !intent.getBooleanExtra(EXTRA_FROM_HOME, false)) openChosenMap()
        handleViewIntent(intent)
    }

    private fun openChosenMap() {
        val pkg = prefs.mapPackage ?: return
        if (pkg != packageName) Apps.launch(this, pkg)
    }

    /** "geo:33.3,44.4?q=..." or "google.navigation:q=33.3,44.4" from another app: show that spot. */
    private fun handleViewIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_VIEW) return
        val text = i.data?.toString() ?: return
        val m = Regex("(-?\\d{1,2}\\.\\d+)\\s*,\\s*(-?\\d{1,3}\\.\\d+)").findAll(text).lastOrNull() ?: return
        val la = m.groupValues[1].toDoubleOrNull() ?: return
        val lo = m.groupValues[2].toDoubleOrNull() ?: return
        if (abs(la) > 85 || abs(lo) > 180 || (la == 0.0 && lo == 0.0)) return
        ui.postDelayed({ if (::root.isInitialized && mode == Mode.BROWSE) dropPin(LatLng(la, lo)) }, 600)
    }

    override fun onStart() {
        super.onStart()
        if (::map.isInitialized) {
            map.onStart()
            startGps()
            ui.postDelayed(autoLook, 5 * 60 * 1000L)
        }
    }

    override fun onStop() {
        if (::map.isInitialized) {
            ui.removeCallbacks(autoLook)
            stopGps()
            ml?.cameraPosition?.let {
                val t = it.target
                if (t != null && lastFix == null) {
                    mp.lastLat = t.latitude
                    mp.lastLon = t.longitude
                }
                mp.lastZoom = it.zoom.coerceIn(5.0, 17.0)
            }
            map.onStop()
        }
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        MapData.removeListener(dataListener)
        MapBuildings.removeListener(onBuildings)
        MapReports.removeListener(onReports)
        if (::hud.isInitialized) hud.release()
        if (::rail.isInitialized) { rail.release(); if (RailHost.mapRail === rail.view) RailHost.mapRail = null }
        voice?.shutdown()
        bg.shutdown()
        router.shutdown()
        if (::map.isInitialized) map.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::map.isInitialized) map.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::map.isInitialized) map.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (!::map.isInitialized) return
        voice?.recheck()   // back from downloading the Arabic voice
        if (themeAtStart != null && Ui.theme(this).id != themeAtStart) { recreate(); return }
        // Day / night look may have changed while the map was away.
        applyStyle()
        map.onResume()
        HubService.start(this)
        // The service may not exist yet on the very first start; it shows the bar itself then.
        if (mode == Mode.CINEMA) HubService.instance?.hideBarForSheet() else HubService.instance?.onMapShown()
        showCardIfNeeded()
        paintDownload()
    }

    override fun onPause() {
        if (::map.isInitialized) map.onPause()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            mode == Mode.CINEMA -> exitCinema()
            mode == Mode.NAV -> flash("دوس «إنهاء» حتى توقف الملاحة")
            panel.isNotEmpty() || selected != null || route != null -> clearAll()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ================================================================== building the screen

    private fun buildUi() {
        val c = this
        root = FrameLayout(c)
        root.addView(map, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val edge = Ui.dp(c, 18)

        // Required credit for the map data, small in the bottom-right corner.
        root.addView(Ui.label(c, "© OpenStreetMap  © OpenMapTiles  © OpenFreeMap", 10f, 0xB3FFFFFF.toInt()).apply {
            setShadowLayer(3f, 0f, 0f, 0xCC000000.toInt())
        }, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.RIGHT).apply {
            val m = Ui.dp(c, 6)
            setMargins(m, m, m, m)
        })

        // Left column: search (or the turn banner), and under it whatever panel is open.
        left = Ui.vbox(c)
        searchBar = buildSearchBar()
        left.addView(searchBar, Ui.lp(Ui.MATCH, Ui.WRAP))
        banner = buildBanner()
        banner.visibility = View.GONE
        left.addView(banner, Ui.lp(Ui.MATCH, Ui.WRAP))
        panelHost = FrameLayout(c)
        left.addView(panelHost, Ui.lp(Ui.MATCH, 0, 1f))
        val dm = resources.displayMetrics
        val colWidth = min(Ui.dp(c, 460), (dm.widthPixels * 0.40f).toInt())
        rail = MapRail(c, root, onAdd = { showAppChooser() }, onBattery = {
            flash("شحن السيارة يحتاج قطعة OBD بلوتوث (ELM327): الشاشة ما توصل لبيانات السيارة", 5000)
        }, shortcuts = listOf(
            Triple("🛍", "المتجر") { startActivity(Intent(c, StoreActivity::class.java)) },
            Triple("⚙", "الإعدادات") { startActivity(Intent(c, MainActivity::class.java)) },
            Triple("🗺", "إعدادات الخريطة") { showLook() },
            Triple("⬇", "تنزيل الخرائط") { showOffline() },
        ))
        val railW = Ui.dp(c, 92)
        val shift = if (rail.shown) railW + Ui.dp(c, 10) else 0
        leftShift = edge + shift
        rail.view.visibility = if (rail.shown) View.VISIBLE else View.GONE
        RailHost.mapRail = rail.view
        root.addView(rail.view, FrameLayout.LayoutParams(railW, Ui.MATCH, Gravity.TOP or Gravity.LEFT).apply {
            setMargins(edge, edge, 0, edge)
        })
        root.addView(left, FrameLayout.LayoutParams(columnWidth(), Ui.MATCH, Gravity.TOP or Gravity.LEFT).apply {
            setMargins(edge + shift, edge, edge, Ui.dp(c, 104))
        })

        // Bottom-left: the tool dock (or the trip card while navigating) and the speed.
        bottomRow = Ui.hbox(c).apply { gravity = Gravity.BOTTOM }
        dock = buildDock()
        bottomRow.addView(dock)
        navCard = buildNavCard()
        navCard.visibility = View.GONE
        bottomRow.addView(navCard, LinearLayout.LayoutParams(min(Ui.dp(c, 400), colWidth), Ui.WRAP))
        speedChip = Ui.vbox(c).apply {
            gravity = Gravity.CENTER
            background = Ui.floatingGlass(c, 38f, opaque = true)
            visibility = View.GONE
        }
        speedText = Ui.label(c, "0", 26f, bold = true).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER }
        speedChip.addView(speedText)
        speedChip.addView(Ui.label(c, "كم/س", 11f, Ui.SUB).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER })
        bottomRow.addView(speedChip, LinearLayout.LayoutParams(Ui.dp(c, 76), Ui.dp(c, 76)).apply { leftMargin = Ui.dp(c, 12) })
        speedChip.visibility = View.GONE
        hud = DriveHud(c) { text -> if (mp.voice) { if (voice == null) voice = NavVoice(c); voice?.say(text) } }
        bottomRow.addView(hud.view, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = Ui.dp(c, 12) })
        root.addView(bottomRow, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.LEFT).apply {
            setMargins(edge + shift, edge, edge, edge)
        })

        // Right, middle: compass and zoom. Kept clear of the hub's edge-swipe strip.
        zoomCol = Ui.vbox(c)
        compassBtn = roundButton(MapIcons.icon(MapIcons.COMPASS, Ui.WHITE)) {
            ml?.animateCamera(CameraUpdateFactory.bearingTo(0.0), 500)
        }
        compassBtn.visibility = View.INVISIBLE
        zoomCol.addView(compassBtn)
        zoomCol.addView(roundButton(MapIcons.icon(MapIcons.PLUS, Ui.WHITE, 2.4f)) { zoomBy(1.0) },
            Ui.lp(Ui.dp(c, 56), Ui.dp(c, 56)).apply { topMargin = Ui.dp(c, 12) })
        zoomCol.addView(roundButton(MapIcons.icon(MapIcons.MINUS, Ui.WHITE, 2.4f)) { zoomBy(-1.0) },
            Ui.lp(Ui.dp(c, 56), Ui.dp(c, 56)).apply { topMargin = Ui.dp(c, 12) })
        zoomCol.addView(ImageView(c).apply {
            setImageBitmap(MapReports.icon(c, MapReports.type("hazard"), 44f))
            contentDescription = "بلّغ"
            setOnClickListener { showReportMenu() }
        }, Ui.lp(Ui.dp(c, 64), Ui.dp(c, 64)).apply { topMargin = Ui.dp(c, 18) })
        root.addView(zoomCol, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply {
            rightMargin = Ui.dp(c, 26)
        })

        // Short messages ("دا نحسب الطريق...").
        flashView = Ui.label(c, "", 16f, bold = true).apply {
            background = Ui.floatingGlass(c, 22f, opaque = true)
            setPadding(Ui.dp(c, 18), Ui.dp(c, 10), Ui.dp(c, 18), Ui.dp(c, 10))
            visibility = View.GONE
        }
        root.addView(flashView, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = Ui.dp(c, 112)
        })
    }

    private fun roundButton(icon: android.graphics.drawable.Drawable, onClick: () -> Unit): ImageView {
        val s = Ui.dp(this, 56)
        return ImageView(this).apply {
            setImageDrawable(icon)
            val p = Ui.dp(this@MapActivity, 15)
            setPadding(p, p, p, p)
            background = Ui.ripple(this@MapActivity, Ui.floatingGlass(this@MapActivity, 28f, opaque = true), 28f)
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
    }

    private fun dockButton(icon: android.graphics.drawable.Drawable, onClick: () -> Unit): ImageView {
        val s = Ui.dp(this, 54)
        return ImageView(this).apply {
            setImageDrawable(icon)
            val p = Ui.dp(this@MapActivity, 14)
            setPadding(p, p, p, p)
            background = Ui.ripple(this@MapActivity, null, 27f)
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
    }

    private fun buildDock(): LinearLayout {
        val c = this
        val d = Ui.hbox(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.floatingGlass(c, 32f, opaque = true)
            val p = Ui.dp(c, 5)
            setPadding(p, p, p, p)
        }
        locateBtn = dockButton(MapIcons.icon(MapIcons.LOCATE, Ui.WHITE)) { follow() }
        d.addView(locateBtn)
        d.addView(dockButton(MapIcons.icon(MapIcons.CUBE, Ui.WHITE)) { toggle3d() })
        d.addView(dockButton(MapIcons.icon(MapIcons.LAYERS, Ui.WHITE)) { if (panel == "look") clearAll() else showLook() })
        // Downloads and settings now live in the map settings and in the apps window (side bar).
        downloadBtn = dockButton(MapIcons.icon(MapIcons.DOWNLOAD, Ui.WHITE)) { if (panel == "offline") clearAll() else showOffline() }
        d.addView(dockButton(MapIcons.icon(MapIcons.VIDEO, Ui.WHITE)) { enterCinema() })
        return d
    }

    private fun paintLocate() {
        if (!::locateBtn.isInitialized) return
        locateBtn.setImageDrawable(MapIcons.icon(MapIcons.LOCATE, if (following && lastFix != null) 0xFF6DB6FF.toInt() else Ui.WHITE, 2.2f))
    }

    private fun paintDownload() {
        if (!::downloadBtn.isInitialized) return
        val color = when {
            MapData.busy() -> 0xFFFFD166.toInt()
            MapData.complete(this) -> Ui.OK
            else -> Ui.WHITE
        }
        downloadBtn.setImageDrawable(MapIcons.icon(MapIcons.DOWNLOAD, color))
    }

    private fun updateCompass() {
        if (!::compassBtn.isInitialized) return
        val b = ml?.cameraPosition?.bearing ?: 0.0
        val turned = abs(b) > 1.0 && abs(b - 360.0) > 1.0
        compassBtn.visibility = if (turned && mode != Mode.CINEMA) View.VISIBLE else View.INVISIBLE
        compassBtn.rotation = -b.toFloat() - 45f // the icon's needle points to the top-right
    }

    private fun zoomBy(delta: Double) {
        val m = ml ?: return
        val z = (m.cameraPosition.zoom + delta).coerceIn(3.0, 20.0)
        if (following && locating && lastFix != null) {
            lastZoomSet = z
            runCatching { m.locationComponent.zoomWhileTracking(z, 300L) }
        } else {
            m.animateCamera(CameraUpdateFactory.zoomTo(z), 300)
        }
    }

    private fun toggle3d() {
        val m = ml ?: return
        mp.tilt3d = !mp.tilt3d
        val tilt = if (!mp.tilt3d) 0.0 else if (mode == Mode.NAV) 50.0 else 40.0
        if (following && locating && lastFix != null) runCatching { m.locationComponent.tiltWhileTracking(tilt, 600L) }
        else m.animateCamera(CameraUpdateFactory.tiltTo(tilt), 600)
        flash(if (mp.tilt3d) "عرض ثلاثي الأبعاد" else "عرض مسطّح")
    }

    private fun flash(text: String, ms: Long = 2600) {
        if (!::flashView.isInitialized) return
        flashView.text = text
        flashView.visibility = if (mode == Mode.CINEMA) View.GONE else View.VISIBLE
        ui.removeCallbacks(hideFlash)
        ui.postDelayed(hideFlash, ms)
    }

    private val hideFlash = Runnable { flashView.visibility = View.GONE }

    private fun paintSpeed(loc: Location) {
        val kmh = if (loc.hasSpeed()) (loc.speed * 3.6f).roundToInt() else 0
        val show = mode != Mode.CINEMA && (mode == Mode.NAV || kmh >= 5)
        speedChip.visibility = View.GONE   // the HUD shows the speed now
        speedText.text = kmh.toString()
        if (::hud.isInitialized) hud.setVisible(mode != Mode.CINEMA)
    }

    // ================================================================== panels (left column)

    private fun sheet(): LinearLayout = Ui.vbox(this).apply {
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        background = Ui.floatingGlass(this@MapActivity, 26f, opaque = true)
        val p = Ui.dp(this@MapActivity, 12)
        setPadding(p, p, p, p)
    }

    private fun showPanel(name: String, content: View) {
        panel = name
        panelHost.removeAllViews()
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }
        panelHost.addView(scroll, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP).apply {
            topMargin = Ui.dp(this@MapActivity, 8)
        })
    }

    private fun closePanel() {
        panel = ""
        panelHost.removeAllViews()
    }

    /** Back to the bare map: no panel, no selection, no route preview. */
    private fun clearAll() {
        searchToken++
        setQuery("")
        hideKeyboard()
        closePanel()
        selected = null
        if (mode == Mode.BROWSE) {
            route = null
            routeDest = null
            nav = null
        }
        drawRoute()
        drawPins()
    }

    private fun title(text: String, size: Float = 19f): TextView = Ui.label(this, text, size, bold = true).apply {
        setPadding(Ui.dp(this@MapActivity, 8), Ui.dp(this@MapActivity, 6), Ui.dp(this@MapActivity, 8), Ui.dp(this@MapActivity, 6))
    }

    private fun note(text: String): TextView = Ui.label(this, text, 14.5f, Ui.SUB).apply {
        setPadding(Ui.dp(this@MapActivity, 8), Ui.dp(this@MapActivity, 4), Ui.dp(this@MapActivity, 8), Ui.dp(this@MapActivity, 8))
        setLineSpacing(0f, 1.2f)
    }

    private fun row(icon: String, text: String, sub: String?, trailing: String?, tint: Int = Ui.SUB, onClick: (() -> Unit)?): LinearLayout {
        val c = this
        val r = Ui.hbox(c).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(c, 10), Ui.dp(c, 9), Ui.dp(c, 10), Ui.dp(c, 9))
            minimumHeight = Ui.dp(c, 58)
            if (onClick != null) {
                background = Ui.ripple(c, null, 16f)
                isClickable = true
                setOnClickListener { onClick() }
            }
        }
        r.addView(ImageView(c).apply { setImageDrawable(MapIcons.icon(icon, tint)) },
            LinearLayout.LayoutParams(Ui.dp(c, 26), Ui.dp(c, 26)).apply { marginEnd = Ui.dp(c, 14) })
        val col = Ui.vbox(c)
        col.addView(Ui.label(c, text, 17f, bold = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        if (!sub.isNullOrEmpty()) {
            col.addView(Ui.label(c, sub, 13.5f, Ui.SUB).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        r.addView(col, Ui.lp(0, Ui.WRAP, 1f))
        if (!trailing.isNullOrEmpty()) {
            r.addView(Ui.label(c, trailing, 13.5f, Ui.SUB).apply { setPadding(Ui.dp(c, 8), 0, 0, 0) })
        }
        return r
    }

    private fun placeRow(p: Place, icon: String = MapIcons.ofKind(p.kind), onClick: () -> Unit): View {
        val kind = MapSearch.kindTitle(p.kind)
        val sub = listOf(kind.takeIf { it.isNotEmpty() && it != p.name }, p.sub.takeIf { it.isNotEmpty() }).filterNotNull().joinToString(" • ")
        val far = if (p.distM >= 0) NavText.distance(p.distM) else null
        return row(icon, p.name, sub, far, onClick = onClick)
    }

    // ---------------------------------------------------------------- search

    private fun buildSearchBar(): View {
        val c = this
        val bar = Ui.hbox(c).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.floatingGlass(c, 30f, opaque = true)
            setPadding(Ui.dp(c, 18), Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4))
            minimumHeight = Ui.dp(c, 60)
        }
        bar.addView(ImageView(c).apply { setImageDrawable(MapIcons.icon(MapIcons.SEARCH, Ui.SUB)) },
            LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24)).apply { marginEnd = Ui.dp(c, 10) })
        searchBox = EditText(c).apply {
            hint = "وين تريد تروح؟"
            setTextColor(Ui.WHITE)
            setHintTextColor(0x99FFFFFF.toInt())
            textSize = 18f
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) {
                    hideKeyboard()
                    search(text.toString(), typed = false)
                    true
                } else false
            }
            setOnFocusChangeListener { _, has -> if (has && text.isEmpty() && mode == Mode.BROWSE) showSearchHome() }
            setOnClickListener { if (text.isEmpty() && panel != "search") showSearchHome() }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (ignoreText) return
                    ui.removeCallbacks(typing)
                    ui.postDelayed(typing, 220)
                }
            })
        }
        bar.addView(searchBox, Ui.lp(0, Ui.WRAP, 1f))
        bar.addView(ImageView(c).apply {
            setImageDrawable(MapIcons.icon(MapIcons.CLOSE, Ui.SUB))
            val p = Ui.dp(c, 12)
            setPadding(p, p, p, p)
            background = Ui.ripple(c, null, 24f)
            isClickable = true
            setOnClickListener { clearAll() }
        }, LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)))
        return bar
    }

    private val typing = Runnable {
        val q = searchBox.text.toString()
        if (q.isBlank()) {
            if (searchBox.hasFocus()) showSearchHome()
        } else {
            search(q, typed = true)
        }
    }

    private fun setQuery(text: String) {
        ignoreText = true
        searchBox.setText(text)
        searchBox.setSelection(searchBox.text.length)
        ignoreText = false
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchBox.windowToken, 0)
        searchBox.clearFocus()
    }

    /** What the search box shows before anything is typed: near-me buttons, home, work, saved, recent. */
    private fun showSearchHome() {
        val c = this
        val col = sheet()
        val chips = Ui.hbox(c).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        for (cat in MapSearch.CATEGORIES) {
            val chip = Ui.hbox(c).apply {
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.ripple(c, Ui.glass(c, 22f, Ui.GLASS_HI), 22f)
                setPadding(Ui.dp(c, 14), Ui.dp(c, 9), Ui.dp(c, 16), Ui.dp(c, 9))
                isClickable = true
                setOnClickListener { searchCategory(cat) }
            }
            chip.addView(ImageView(c).apply { setImageDrawable(MapIcons.icon(MapIcons.ofKind(cat.kinds[0]), Ui.WHITE)) },
                LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20)).apply { marginEnd = Ui.dp(c, 8) })
            chip.addView(Ui.label(c, cat.title, 15f, bold = true))
            chips.addView(chip, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = Ui.dp(c, 8) })
        }
        col.addView(HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(chips)
        }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = Ui.dp(c, 6) })

        fun fixed(p: Place?, icon: String, name: String) {
            if (p != null) col.addView(row(icon, name, p.name, null, Ui.OK) { showPlace(p) })
            else col.addView(row(icon, name, "دوس مطوّل على مكان بالخريطة، واختار «$name»", null, onClick = null))
        }
        fixed(mp.home, MapIcons.HOME, "البيت")
        fixed(mp.work, MapIcons.WORK, "الشغل")
        val near = here()
        mp.saved().forEach { p ->
            p.distM = MapSearch.dist(near.latitude, near.longitude, p.lat, p.lon)
            col.addView(placeRow(p, MapIcons.STAR) { showPlace(p) })
        }
        val recents = mp.recents()
        if (recents.isNotEmpty()) {
            col.addView(note("آخر الأماكن"))
            recents.take(6).forEach { p ->
                p.distM = MapSearch.dist(near.latitude, near.longitude, p.lat, p.lon)
                col.addView(placeRow(p, MapIcons.CLOCK) { showPlace(p) })
            }
        }
        if (!MapSearch.ready(this)) {
            col.addView(note("البحث هسه يشتغل بالنت. نزّل خريطة العراق من زر التنزيل حتى يشتغل بدون نت وهو تكتب."))
        }
        showPanel("search", col)
    }

    private fun version(): String = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "1"

    /**
     * [typed] = still typing: only our own offline index answers (instant, no internet).
     * The keyboard's search key also asks the internet when the offline index has nothing.
     */
    private fun search(query: String, typed: Boolean) {
        val q = query.trim()
        if (q.isEmpty()) return
        val token = ++searchToken
        MapSearch.coordinates(q)?.let {
            showPlace(it)
            return
        }
        val near = here()
        val cat = MapSearch.categoryOf(q)
        val offline = MapSearch.ready(this)
        if (!offline && typed) return // online search is only sent when the user presses search
        if (!offline) showResults(q, emptyList(), "دا يدوّر...", false)
        bg.execute {
            var list = when {
                !offline -> emptyList()
                cat != null -> MapSearch.nearby(this, cat.kinds, near.latitude, near.longitude)
                else -> MapSearch.search(this, q, near.latitude, near.longitude)
            }
            var fromNet = false
            var failed = false
            if (list.isEmpty() && !typed && MapTiles.online) {
                runCatching { MapSearch.online(q, near.latitude, near.longitude, version()) }
                    .onSuccess { list = it; fromNet = true }
                    .onFailure { failed = true }
            }
            ui.post {
                if (token != searchToken || isFinishing || isDestroyed) return@post
                val msg = when {
                    list.isNotEmpty() -> null
                    failed -> "ما وصلنا للبحث بالنت"
                    !offline && !MapTiles.online -> "البحث يحتاج إنترنت، أو نزّل خريطة العراق"
                    else -> "ما لكيت شي بهالاسم"
                }
                showResults(q, list, msg, offline && !fromNet && MapTiles.online)
            }
        }
    }

    private fun searchCategory(cat: MapSearch.Category) {
        hideKeyboard()
        setQuery(cat.title)
        val token = ++searchToken
        val near = here()
        if (!MapSearch.ready(this)) {
            search(cat.title, typed = false)
            return
        }
        bg.execute {
            val list = MapSearch.nearby(this, cat.kinds, near.latitude, near.longitude)
            ui.post {
                if (token != searchToken || isFinishing || isDestroyed) return@post
                showResults(cat.title, list, if (list.isEmpty()) "ما لكيت ${cat.title} قريبة" else null, false)
            }
        }
    }

    private fun showResults(query: String, list: List<Place>, message: String?, offerOnline: Boolean) {
        val col = sheet()
        if (message != null) col.addView(note(message))
        list.forEach { p -> col.addView(placeRow(p) { showPlace(p) }) }
        if (offerOnline) {
            col.addView(row(MapIcons.GLOBE, "دوّر بالنت على «$query»", "للأماكن خارج العراق أو الجديدة", null) {
                val token = ++searchToken
                val near = here()
                showResults(query, emptyList(), "دا يدوّر بالنت...", false)
                bg.execute {
                    val r = runCatching { MapSearch.online(query, near.latitude, near.longitude, version()) }
                    ui.post {
                        if (token != searchToken || isFinishing || isDestroyed) return@post
                        val found = r.getOrDefault(emptyList())
                        showResults(query, found, if (r.isFailure) "ما وصلنا للبحث بالنت" else if (found.isEmpty()) "ما لكيت شي" else null, false)
                    }
                }
            })
        }
        showPanel("search", col)
    }

    // ---------------------------------------------------------------- a place

    private fun dropPin(at: LatLng) {
        val pin = Place("مكان محدد", "%.5f, %.5f".format(Locale.US, at.latitude, at.longitude), at.latitude, at.longitude, "pin")
        showPlace(pin, move = false)
        bg.execute {
            val known = MapSearch.reverse(this, at.latitude, at.longitude) ?: return@execute
            ui.post {
                if (selected === pin && !isFinishing && !isDestroyed) showPlace(known, move = false)
            }
        }
    }

    /** A tap on a shop / mosque / town label on the map opens it like a search result. */
    private fun onMapTap(at: LatLng): Boolean {
        val m = ml ?: return false
        if (mode == Mode.CINEMA) {
            exitCinema()
            return true
        }
        if (mode != Mode.BROWSE) return false
        if (searchBox.hasFocus()) hideKeyboard()
        val pt = m.projection.toScreenLocation(at)
        val r = Ui.dp(this, 16).toFloat()
        val feats = runCatching {
            m.queryRenderedFeatures(RectF(pt.x - r, pt.y - r, pt.x + r, pt.y + r), "poi_r1", "poi_r7", "poi_r20", "poi_transit", "airport")
        }.getOrDefault(emptyList())
        for (f in feats) {
            val name = listOf("name:ar", "name", "name:en").firstNotNullOfOrNull { k ->
                if (f.hasProperty(k)) f.getStringProperty(k)?.takeIf { it.isNotBlank() } else null
            } ?: continue
            val g = f.geometry() as? Point ?: continue
            showPlace(Place(name, "", g.latitude(), g.longitude(), "poi"), move = false)
            return true
        }
        return false
    }

    private fun showPlace(p: Place, move: Boolean = true) {
        val c = this
        hideKeyboard()
        selected = p
        if (route != null && mode == Mode.BROWSE) {
            route = null
            routeDest = null
            nav = null
            drawRoute()
        }
        drawPins()
        val near = here()
        p.distM = MapSearch.dist(near.latitude, near.longitude, p.lat, p.lon)
        if (p.kind != "pin") setQuery(p.name)

        val col = sheet()
        col.addView(title(p.name, 22f))
        val kind = MapSearch.kindTitle(p.kind)
        val where = listOf(kind.takeIf { it.isNotEmpty() && it != p.name }, p.sub.takeIf { it.isNotEmpty() }).filterNotNull().joinToString(" • ")
        if (where.isNotEmpty()) col.addView(note(where))
        if (lastFix != null) col.addView(note("يبعد ${NavText.distance(p.distM)} عنك"))

        val buttons = Ui.hbox(c).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        buttons.addView(Ui.button(c, "اتجاهات", primary = true) { directionsTo(p) }, Ui.lp(0, Ui.WRAP, 1.4f).apply { marginEnd = Ui.dp(c, 8) })
        val save = Ui.button(c, if (mp.isSaved(p)) "محفوظ ★" else "حفظ ☆") {}
        save.setOnClickListener {
            val now = mp.toggleSaved(p)
            save.text = if (now) "محفوظ ★" else "حفظ ☆"
        }
        buttons.addView(save, Ui.lp(0, Ui.WRAP, 1f))
        col.addView(buttons, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(c, 6) })

        val more = Ui.hbox(c).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        more.addView(Ui.button(c, "خلّيه البيت") {
            mp.home = p
            flash("انحفظ مكان البيت")
        }, Ui.lp(0, Ui.WRAP, 1f).apply { marginEnd = Ui.dp(c, 8) })
        more.addView(Ui.button(c, "خلّيه الشغل") {
            mp.work = p
            flash("انحفظ مكان الشغل")
        }, Ui.lp(0, Ui.WRAP, 1f))
        col.addView(more, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(c, 8) })
        showPanel("place", col)

        if (move) {
            unfollow()
            ml?.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(p.lat, p.lon)).zoom(15.8).tilt(if (mp.tilt3d) 45.0 else 0.0).build()
            ), 1200)
        }
    }

    // ---------------------------------------------------------------- directions

    private fun directionsTo(p: Place) {
        val from = lastFix
        if (from == null) {
            flash("ما عرفنا موقعك بعد. انتظر الـGPS شوية")
            return
        }
        mp.addRecent(p)
        flash("دا نحسب الطريق...", 15000)
        val token = ++searchToken
        val heading = if (from.hasBearing() && from.hasSpeed() && from.speed > 2.5f) from.bearing.toDouble() else null
        router.execute {
            val r = runCatching { MapRoute.routes(this, from.latitude, from.longitude, p.lat, p.lon, heading, 3) }
            ui.post {
                if (token != searchToken || isFinishing || isDestroyed) return@post
                r.onSuccess { list -> routeAlts = list; showRoute(list[0], p) }
                    .onFailure { flash(it.message ?: "ما كدرنا نحسب الطريق", 5000) }
            }
        }
    }

    private fun clock(msFromNow: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() + msFromNow }
        val h24 = cal.get(Calendar.HOUR_OF_DAY)
        val h = if (h24 % 12 == 0) 12 else h24 % 12
        return "%d:%02d %s".format(Locale.US, h, cal.get(Calendar.MINUTE), if (h24 < 12) "ص" else "م")
    }

    /** The routes offered for the current destination (the first is the shortest). */
    private var routeAlts: List<Route> = emptyList()

    private fun showRoute(r: Route, dest: Place) {
        val c = this
        ui.removeCallbacks(hideFlash)
        flashView.visibility = View.GONE
        route = r
        routeDest = dest
        nav = null
        selected = dest
        drawRoute()
        drawPins()

        val col = sheet()
        col.addView(title("إلى ${dest.name}", 20f))
        val big = Ui.hbox(c).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.BOTTOM
            setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0)
        }
        big.addView(Ui.label(c, NavText.duration(r.timeMs), 34f, 0xFF7CC4FF.toInt(), true))
        big.addView(Ui.label(c, "  ${NavText.distance(r.distM)}  •  توصل ${clock(r.timeMs)}", 16f, Ui.SUB))
        col.addView(big)
        col.addView(note(if (r.offline) "محسوب من خريطتنا، بدون نت" else "محسوب بالنت"))
        if (routeAlts.size > 1) {
            col.addView(title("الطرق (${routeAlts.size})", 15f))
            routeAlts.forEachIndexed { i, alt ->
                val on = alt === r
                val card = Ui.vbox(c).apply {
                    setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = Ui.dp(c, 14).toFloat()
                        setColor(if (on) 0x332E8BFF else 0x12FFFFFF)
                        setStroke(Ui.dp(c, 2), if (on) Ui.ACCENT else 0x00000000)
                    }
                    setOnClickListener { if (!on) showRoute(alt, dest) }
                }
                val top = Ui.hbox(c).apply { gravity = Gravity.CENTER_VERTICAL }
                top.addView(Ui.label(c, NavText.duration(alt.timeMs), 18f, if (on) 0xFF7CC4FF.toInt() else Ui.WHITE, true))
                top.addView(Ui.label(c, "  ${NavText.distance(alt.distM)}", 15f, Ui.SUB), Ui.lp(0, Ui.WRAP, 1f))
                if (i == 0) top.addView(Ui.label(c, "الأقرب", 13f, 0xFF5FD3A4.toInt(), true))
                card.addView(top)
                if (alt.via.isNotEmpty()) card.addView(Ui.label(c, "عن طريق ${alt.via}", 14f, Ui.SUB))
                col.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = Ui.dp(c, 6) })
            }
        }

        val buttons = Ui.hbox(c).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        buttons.addView(Ui.button(c, "ابدأ", primary = true) { startNav(sim = false) }, Ui.lp(0, Ui.WRAP, 1.4f).apply { marginEnd = Ui.dp(c, 8) })
        buttons.addView(Ui.button(c, "محاكاة") { startNav(sim = true) }, Ui.lp(0, Ui.WRAP, 1f))
        col.addView(buttons, Ui.lp(Ui.MATCH, Ui.WRAP).apply {
            topMargin = Ui.dp(c, 4)
            bottomMargin = Ui.dp(c, 8)
        })
        r.steps.take(60).forEach { s ->
            col.addView(row(MapIcons.arrow(s.sign), NavText.line(s), null, if (s.distM > 0) NavText.distance(s.distM) else null, Ui.WHITE, null).apply {
                minimumHeight = Ui.dp(c, 48)
            })
        }
        showPanel("route", col)
        fitRoute(r)
    }

    private fun fitRoute(r: Route) {
        val m = ml ?: return
        unfollow()
        val b = LatLngBounds.Builder()
        val stride = max(1, r.size / 400)
        var i = 0
        while (i < r.size) {
            b.include(LatLng(r.lat(i), r.lon(i)))
            i += stride
        }
        b.include(LatLng(r.lat(r.size - 1), r.lon(r.size - 1)))
        val pad = Ui.dp(this, 70)
        val leftPad = if (mode == Mode.BROWSE) left.width + Ui.dp(this, 40) else pad
        runCatching {
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 0.0, 0.0, leftPad, Ui.dp(this, 110), pad + Ui.dp(this, 60), pad), 1100)
        }
    }

    // ================================================================== navigation

    private fun buildBanner(): LinearLayout {
        val c = this
        val b = Ui.hbox(c).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.floatingGlass(c, 28f, opaque = true)
            setPadding(Ui.dp(c, 14), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 14))
        }
        bannerArrow = ImageView(c).apply {
            background = Ui.solid(c, Ui.ACCENT, 20f)
            val p = Ui.dp(c, 12)
            setPadding(p, p, p, p)
        }
        b.addView(bannerArrow, LinearLayout.LayoutParams(Ui.dp(c, 84), Ui.dp(c, 84)).apply { marginEnd = Ui.dp(c, 16) })
        val col = Ui.vbox(c)
        bannerDist = Ui.label(c, "", 34f, bold = true)
        bannerText = Ui.label(c, "", 20f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        bannerThen = Ui.label(c, "", 14.5f, Ui.SUB).apply { maxLines = 1 }
        col.addView(bannerDist)
        col.addView(bannerText)
        col.addView(bannerThen)
        b.addView(col, Ui.lp(0, Ui.WRAP, 1f))
        return b
    }

    private fun buildNavCard(): LinearLayout {
        val c = this
        val card = Ui.vbox(c).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = Ui.floatingGlass(c, 28f, opaque = true)
            setPadding(Ui.dp(c, 16), Ui.dp(c, 12), Ui.dp(c, 16), Ui.dp(c, 12))
        }
        navEta = Ui.label(c, "", 24f, bold = true)
        navLeft = Ui.label(c, "", 16f, Ui.SUB)
        card.addView(navEta)
        card.addView(navLeft)
        val row = Ui.hbox(c).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(Ui.button(c, "إنهاء") { endNav() }.apply {
            background = Ui.ripple(c, Ui.solid(c, 0xFFD64545.toInt(), 22f), 22f)
        }, Ui.lp(0, Ui.WRAP, 1f).apply { marginEnd = Ui.dp(c, 8) })
        muteBtn = dockButton(MapIcons.icon(MapIcons.VOLUME, Ui.WHITE)) {
            mp.voice = !mp.voice
            if (!mp.voice) voice?.stop()
            val v = voice
            if (mp.voice && v != null && v.checked && !v.available && v.missingData) {
                // Google's engine knows Arabic but the voice is not on the screen yet: open its download page.
                flash("الصوت العربي مو منزّل، نزّله من الصفحة اللي تنفتح وارجع", 5000)
                runCatching { startActivity(v.installVoiceIntent()) }
                paintMute()
                return@dockButton
            }
            paintMute()
            flash(
                when {
                    !mp.voice -> "الصوت مطفي"
                    voice?.checked == true && voice?.available != true -> "الشاشة ما بيها صوت عربي، راح يصفّر قبل كل لفة"
                    else -> "الصوت شغّال"
                }
            )
        }
        row.addView(muteBtn)
        row.addView(dockButton(MapIcons.icon(MapIcons.OVERVIEW, Ui.WHITE)) { route?.let { fitRoute(it) } })
        row.addView(dockButton(MapIcons.icon(MapIcons.VIDEO, Ui.WHITE)) { enterCinema() })
        simSpeedBtn = Ui.button(c, "1×") {
            simFactor = if (simFactor >= 8) 1 else simFactor * 2
            simSpeedBtn.text = "$simFactor×"
        }
        simSpeedBtn.visibility = View.GONE
        row.addView(simSpeedBtn, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = Ui.dp(c, 6) })
        card.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(c, 10) })
        return card
    }

    private fun paintMute() {
        muteBtn.setImageDrawable(MapIcons.icon(if (mp.voice) MapIcons.VOLUME else MapIcons.MUTE, Ui.WHITE))
    }

    private fun startNav(sim: Boolean) {
        val r = route ?: return
        val start = lastFix
        if (!sim && start == null) {
            flash("ما عرفنا موقعك بعد")
            return
        }
        mode = Mode.NAV
        nav = NavEngine(r)
        MapBuildings.prefetchRoute(r, MapTiles.online)
        offCount = 0
        rerouting = false
        arrivedShown = false
        closePanel()
        hideKeyboard()
        searchBar.visibility = View.GONE
        banner.visibility = View.VISIBLE
        dock.visibility = View.GONE
        navCard.visibility = View.VISIBLE
        simSpeedBtn.visibility = if (sim) View.VISIBLE else View.GONE
        paintMute()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (voice == null) voice = NavVoice(this)
        simulating = sim
        if (sim) {
            simAlong = 0.0
            simFactor = 1
            simSpeedBtn.text = "1×"
            simLast = SystemClock.elapsedRealtime()
            ui.removeCallbacks(simTick)
            ui.post(simTick)
        } else if (start != null) {
            navTick(start)
        }
        following = true
        ui.postDelayed({ if (mode == Mode.NAV) follow() }, 300)
    }

    private val simTick = object : Runnable {
        override fun run() {
            val n = nav
            if (!simulating || n == null) return
            val now = SystemClock.elapsedRealtime()
            val dt = (now - simLast).coerceIn(0L, 1000L) / 1000.0
            simLast = now
            val v = n.speedAt(simAlong)
            simAlong += v * simFactor * dt
            val p = n.pointAt(simAlong)
            val loc = Location("sim").apply {
                latitude = p[0]
                longitude = p[1]
                bearing = p[2].toFloat()
                speed = v.toFloat()
                accuracy = 3f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            onFix(loc, true)
            if (simulating && simAlong < n.route.lengthM) ui.postDelayed(this, 250)
        }
    }

    private fun navTick(loc: Location) {
        val n = nav ?: return
        n.update(loc.latitude, loc.longitude)
        if (n.arrived) {
            arrive()
            return
        }
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        if (!simulating && !rerouting) {
            // Well off the line for a few fixes in a row: the driver took another road.
            val allowed = max(45.0, (if (loc.hasAccuracy()) loc.accuracy.toDouble() else 20.0) * 1.5)
            if (n.offM > allowed) offCount++ else offCount = 0
            if (offCount >= 3 && SystemClock.elapsedRealtime() - lastRerouteAt > 8000) reroute(loc)
        }
        paintNav(n)
        if (mp.voice) n.announcement(speed)?.let { voice?.say(it) }
        val now = SystemClock.elapsedRealtime()
        if (now - lastDoneAt > 1500) {
            lastDoneAt = now
            drawDone()
            // Closer in town and before a turn, wider on the highway.
            var z = when {
                speed < 9 -> 17.8
                speed < 17 -> 17.2
                speed < 25 -> 16.6
                else -> 16.0
            }
            if (n.distToNextM < 250) z += 0.4
            if (following && abs(z - lastZoomSet) >= 0.3) {
                lastZoomSet = z
                runCatching { ml?.locationComponent?.zoomWhileTracking(z, 1500L) }
            }
        }
    }

    private fun paintNav(n: NavEngine) {
        if (rerouting) return
        val s = n.next
        bannerArrow.setImageDrawable(MapIcons.icon(MapIcons.arrow(s.sign), Ui.WHITE, 2.6f))
        bannerDist.text = NavText.distance(n.distToNextM)
        bannerText.text = NavText.line(s)
        val then = n.then
        bannerThen.text = if (then != null) "وبعدها ${NavText.action(then)}" else n.street
        bannerThen.visibility = if (bannerThen.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        val ms = n.remainingMs
        navEta.text = "توصل ${clock(ms)}"
        navLeft.text = "${NavText.duration(ms)}  •  ${NavText.distance(n.remainingM)}"
    }

    private fun reroute(loc: Location) {
        val dest = routeDest ?: return
        rerouting = true
        lastRerouteAt = SystemClock.elapsedRealtime()
        offCount = 0
        bannerDist.text = "…"
        bannerText.text = "دا نحسب طريق جديد"
        bannerThen.visibility = View.GONE
        val heading = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 2.5f) loc.bearing.toDouble() else null
        router.execute {
            val r = runCatching { MapRoute.route(this, loc.latitude, loc.longitude, dest.lat, dest.lon, heading) }
            ui.post {
                rerouting = false
                if (mode == Mode.BROWSE || isFinishing || isDestroyed) return@post
                r.onSuccess {
                    route = it
                    nav = NavEngine(it)
                    MapBuildings.prefetchRoute(it, MapTiles.online)
                    drawRoute()
                    if (mp.voice) voice?.say("تغيّر الطريق")
                    lastFix?.let { fix -> navTick(fix) }
                }.onFailure { nav?.let { n -> paintNav(n) } }
            }
        }
    }

    private fun arrive() {
        if (arrivedShown) return
        arrivedShown = true
        simulating = false
        ui.removeCallbacks(simTick)
        val name = routeDest?.name ?: ""
        if (mp.voice) voice?.say("وصلت، بالسلامة")
        bannerArrow.setImageDrawable(MapIcons.icon(MapIcons.FLAG, Ui.WHITE, 2.4f))
        bannerDist.text = "وصلت"
        bannerText.text = name
        bannerThen.visibility = View.GONE
        ui.postDelayed({ if (arrivedShown && mode != Mode.BROWSE) endNav() }, 7000)
    }

    private fun endNav() {
        if (mode == Mode.CINEMA) exitCinema()
        simulating = false
        ui.removeCallbacks(simTick)
        ui.removeCallbacks(recenter)
        voice?.stop()
        mode = Mode.BROWSE
        nav = null
        route = null
        routeDest = null
        selected = null
        arrivedShown = false
        rerouting = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        banner.visibility = View.GONE
        navCard.visibility = View.GONE
        searchBar.visibility = View.VISIBLE
        dock.visibility = View.VISIBLE
        setQuery("")
        drawRoute()
        drawPins()
        // After a simulated drive the arrow goes back to where the car really is.
        lastFix = null
        startGps()
        following = true
        ui.postDelayed({ if (mode == Mode.BROWSE && lastFix != null) follow() }, 400)
    }

    // ================================================================== offline maps

    private fun mb(bytes: Long): String = if (bytes >= 1024L * 1048576L) "%.1f كيكا".format(Locale.US, bytes / 1073741824.0) else "${max(1L, bytes / 1048576L)} ميكا"

    private var manifestSize = 0L
    private var manifestVersion: String? = null
    private var manifestTodo = -1

    private fun showOffline() {
        val c = this
        hideKeyboard()
        val col = sheet()
        col.addView(title("خريطة العراق بدون نت"))
        val st = MapData.status
        val have = MapData.complete(this)
        fun part(ok: Boolean, text: String) = row(if (ok) MapIcons.CHECK else MapIcons.CLOSE, text, null, null, if (ok) Ui.OK else Ui.BAD, null).apply { minimumHeight = Ui.dp(c, 42) }
        col.addView(part(MapData.tilesFile(this) != null, "الخريطة: كل الشوارع والمباني والأسماء"))
        col.addView(part(MapData.placesFile(this) != null, "البحث: الأماكن والشوارع والمحطات"))
        col.addView(part(MapData.routingDir(this) != null, "الاتجاهات والملاحة"))

        when (st.phase) {
            MapData.Phase.CHECKING -> col.addView(note("دا نتأكد من الملفات..."))
            MapData.Phase.DOWNLOADING, MapData.Phase.INSTALLING -> {
                val pct = if (st.total > 0) (st.done * 100 / st.total).toInt().coerceIn(0, 100) else 0
                col.addView(note(if (st.phase == MapData.Phase.INSTALLING) "دا نثبّت ${st.note}..." else "دا ينزل: $pct%  (${mb(st.done)} من ${mb(st.total)})"))
                val bar = Ui.hbox(c).apply { background = Ui.solid(c, 0x33FFFFFF, 6f) }
                bar.addView(View(c).apply { background = Ui.solid(c, Ui.ACCENT, 6f) }, Ui.lp(0, Ui.dp(c, 10), pct.toFloat().coerceAtLeast(1f)))
                bar.addView(View(c), Ui.lp(0, Ui.dp(c, 10), (100 - pct).toFloat()))
                col.addView(bar, Ui.lp(Ui.MATCH, Ui.dp(c, 10)).apply {
                    val m = Ui.dp(c, 8)
                    setMargins(m, 0, m, m)
                })
                col.addView(Ui.button(c, "وقّف التنزيل") { MapData.cancel() })
            }
            else -> {
                if (st.phase == MapData.Phase.ERROR) col.addView(note("ما كمل التنزيل: ${st.note}"))
                val v = MapData.installedVersion(this)
                if (have) col.addView(note("منزّلة ✓  بيانات ${v ?: ""}  •  ${mb(MapData.installedBytes(this))}"))
                else col.addView(note("إذا مو منزّلة، الخريطة تشتغل بالنت وتحفظ كل شارع تمرّ بيه. نزّلها مرة وحدة وتشتغل بكل العراق بدون نت."))
                val label = when {
                    !have && manifestSize > 0 -> "نزّل خريطة العراق (${mb(manifestSize)})"
                    !have -> "نزّل خريطة العراق"
                    manifestTodo > 0 -> "حدّث الخريطة (${mb(manifestSize)})"
                    else -> "تأكد من وجود تحديث"
                }
                col.addView(Ui.button(c, label, primary = !have || manifestTodo > 0) {
                    if (!MapTiles.online) flash("التنزيل يحتاج إنترنت") else {
                        MapData.start(this)
                        paintDownload()
                    }
                })
                if (have || MapData.installedBytes(this) > 0) {
                    col.addView(Ui.button(c, "امسح الخريطة المنزّلة") {
                        bg.execute {
                            MapData.delete(this)
                            ui.post {
                                manifestTodo = -1
                                paintDownload()
                                if (panel == "offline") showOffline()
                                flash("انمسحت")
                            }
                        }
                    }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(c, 8) })
                }
            }
        }

        col.addView(title("حفظ الشوارع تلقائياً", 17f), Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(c, 10) })
        col.addView(note("أي شارع أو مكان تمرّ بيه والنت شغّال ينحفظ، وبعدها يشتغل بدون نت."))
        col.addView(Ui.segmented(c, listOf("شغّال", "مطفي"), if (mp.autoSave) 0 else 1) { mp.autoSave = it == 0 })
        val savedNote = note("")
        col.addView(savedNote)
        col.addView(Ui.button(c, "امسح الشوارع المحفوظة") {
            bg.execute {
                MapTiles.clearSaved()
                ui.post { if (panel == "offline") showOffline() }
            }
        })
        addBuildingsSection(col)
        showPanel("offline", col)

        bg.execute {
            val (count, bytes) = MapTiles.savedStats()
            ui.post { savedNote.text = "محفوظ هسه: $count قطعة خريطة • ${mb(bytes)}" }
            // Ask the store what the pack weighs / whether a newer one exists (once per screen).
            if (manifestTodo < 0 && MapTiles.online && !MapData.busy()) {
                runCatching {
                    val m = MapData.fetchManifest(this)
                    val todo = MapData.missing(this, m)
                    manifestVersion = m.version
                    manifestSize = todo.sumOf { it.size }
                    manifestTodo = todo.size
                }.onSuccess { ui.post { if (panel == "offline" && !MapData.busy()) showOffline() } }
            }
        }
    }

    /** "Buildings of Iraq": by governorate, or automatically along the way. Kept on the screen for good. */
    private fun addBuildingsSection(col: LinearLayout) {
        val c = this
        col.addView(title("بنايات العراق والمجسمات", 17f), Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(c, 14) })
        col.addView(note("كل بنايات العراق بارتفاعاتها، تطلع مجسمات بالعرض الثلاثي. اللي ينزل ينحفظ ويشتغل بدون نت."))
        col.addView(title("تنزل وحدها على طريقي", 15f))
        col.addView(note("والنت شغّال: البنايات اللي على طريقك (وقدّامك لما تمشي بدون وجهة) تنزل قبل ما توصلها وتنحفظ."))
        col.addView(Ui.segmented(c, listOf("شغّال", "مطفي"), if (mp.buildingsAlongRoute) 0 else 1) { mp.buildingsAlongRoute = it == 0 })

        val j = MapBuildings.job
        if (j != null && !j.finished) {
            val pct = if (j.total > 0) j.done * 100 / j.total else 0
            col.addView(note(if (j.total > 0) "دا ينزّل بنايات ${j.gov}: $pct٪ (${j.done} من ${j.total})" else "دا يحضّر بنايات ${j.gov}..."))
            col.addView(Ui.button(c, "وقّف التنزيل") { MapBuildings.cancel() })
        } else if (j?.error != null) {
            col.addView(note("ما كمل تنزيل بنايات ${j.gov}: ${j.error}"))
        }

        val here = lastFix?.let { MapBuildings.governorateAt(it.latitude, it.longitude) }
            ?: ml?.cameraPosition?.target?.let { MapBuildings.governorateAt(it.latitude, it.longitude) }
        val busy = j != null && !j.finished
        if (here != null && !busy) {
            col.addView(Ui.button(c, if (MapBuildings.downloaded(here.id)) "بنايات ${here.name} منزّلة ✓ (حدّثها)" else "نزّل بنايات محافظتي (${here.name})", primary = !MapBuildings.downloaded(here.id)) {
                startBuildings(here)
            }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(c, 8) })
        }
        col.addView(title("المحافظات", 15f), Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(c, 8) })
        col.addView(note("دوس «نزّل» على أي محافظة. اللي دا تنزل يطلعلها شريط ونسبة."))
        // One row per governorate: name, state, and a bar while it downloads.
        val grid = Ui.vbox(c)
        val govs = runCatching { MapBuildings.governorates() }.getOrDefault(emptyList())
        for (g in govs) {
            val done = MapBuildings.downloaded(g.id)
            val active = j != null && !j.finished && j.gov == g.name
            val row = Ui.vbox(c).apply {
                setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = Ui.dp(c, 14).toFloat()
                    setColor(if (active) 0x332E8BFF else 0x12FFFFFF)
                    if (active) setStroke(Ui.dp(c, 2), Ui.ACCENT)
                }
            }
            val line = Ui.hbox(c).apply { gravity = Gravity.CENTER_VERTICAL }
            line.addView(Ui.label(c, g.name, 16f, bold = true), Ui.lp(0, Ui.WRAP, 1f))
            val state = when {
                active && j!!.total > 0 -> "${j.done * 100 / j.total}٪"
                active -> "دا يحضّر..."
                here?.id == g.id && done -> "✓ منزّلة · محافظتك"
                done -> "✓ منزّلة"
                here?.id == g.id -> "محافظتك"
                else -> ""
            }
            line.addView(Ui.label(c, state, 14f, if (done) 0xFF5FD3A4.toInt() else Ui.SUB), Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = Ui.dp(c, 10) })
            if (!active) line.addView(Ui.button(c, if (done) "حدّث" else "نزّل", primary = !done) { if (!busy) startBuildings(g) })
            else line.addView(Ui.button(c, "وقّف") { MapBuildings.cancel() })
            row.addView(line)
            if (active) {
                row.addView(android.widget.ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    isIndeterminate = j!!.total == 0
                    if (j.total > 0) progress = j.done * 100 / j.total
                    progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
                }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(c, 6) })
            }
            grid.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = Ui.dp(c, 6) })
        }
        col.addView(grid)
        val stat = note("")
        col.addView(stat)
        col.addView(Ui.button(c, "امسح البنايات المحفوظة") {
            bg.execute {
                MapBuildings.clear()
                ui.post { if (panel == "offline") showOffline() }
            }
        })
        bg.execute {
            val (n, bytes) = MapBuildings.stats()
            ui.post { stat.text = "محفوظ هسه: $n قطعة بنايات • ${mb(bytes)}" }
        }
    }

    private fun startBuildings(g: MapBuildings.Governorate) {
        if (!MapTiles.online) { flash("التنزيل يحتاج إنترنت"); return }
        MapBuildings.downloadGovernorate(g)
        flash("بدأ تنزيل بنايات ${g.name}")
        if (panel == "offline") showOffline()
    }

    @Volatile private var lastBuildingsPaint = 0L
    private val onBuildings: (MapBuildings.Job) -> Unit = { j ->
        val now = SystemClock.elapsedRealtime()
        if (j.finished || now - lastBuildingsPaint > 1500) {
            lastBuildingsPaint = now
            if (!isFinishing && !isDestroyed && panel == "offline") showOffline()
            if (j.finished && j.error == null) {
                flash("بنايات ${j.gov} نزلت وانحفظت ✓", 4000)
                MapTiles.retryFailed()
            }
        }
    }

    private fun onDataStatus(s: MapData.Status) {
        if (isFinishing || isDestroyed || !::root.isInitialized) return
        paintDownload()
        if (panel == "offline") showOffline()
        when (s.phase) {
            MapData.Phase.DONE -> {
                manifestTodo = 0
                flash("خريطة العراق جاهزة، تشتغل بدون نت ✓", 5000)
                bg.execute { MapRoute.warmUp(this) }
            }
            MapData.Phase.ERROR -> flash("ما كمل تنزيل الخريطة", 4000)
            else -> {}
        }
    }

    // ---------------------------------------------------------------- look

    private fun showLook() {
        val c = this
        hideKeyboard()
        val col = sheet()
        col.addView(Ui.button(c, "⬇  تنزيل الخرائط والبنايات (بدون نت)") { showOffline() }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = Ui.dp(c, 12) })
        col.addView(title("شكل الخريطة"))
        // Two rows of looks; picking in one row clears the other.
        val row1 = listOf(MapLook.AUTO, MapLook.COLOR, MapLook.NIGHT)
        val row2 = listOf(MapLook.VIVID, MapLook.LIGHT, MapLook.SATELLITE)
        col.addView(Ui.segmented(c, listOf("تلقائي", "نهاري", "ليلي"), row1.indexOf(prefs.mapLook)) {
            prefs.mapLook = row1[it]
            applyStyle()
            showLook()
        })
        col.addView(Ui.segmented(c, listOf("ملوّن", "فاتح", "قمر صناعي"), row2.indexOf(prefs.mapLook)) {
            prefs.mapLook = row2[it]
            applyStyle()
            showLook()
        }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(c, 8) })
        if (prefs.mapLook == MapLook.SATELLITE) col.addView(note("صور القمر الصناعي تحتاج نت (الشوارع والأسماء تبقى تشتغل بدون نت)."))
        col.addView(note("تلقائي: نهاري بالنهار وليلي من الستة المغرب."))
        col.addView(title("حجم الخريطة", 17f))
        val scales = listOf(1.0f, 1.2f, 1.45f)
        val at = scales.indices.minByOrNull { abs(scales[it] - mp.scale) } ?: 1
        col.addView(Ui.segmented(c, listOf("صغير", "وسط", "كبير"), at) {
            if (scales[it] != mp.scale) {
                mp.scale = scales[it]
                recreate()
            }
        })
        col.addView(title("الشريط الجانبي", 17f), Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(c, 8) })
        col.addView(note("الساعة والطقس وبرامجك المفضلة. دوس مطوّل على أي برنامج حتى تشيله."))
        col.addView(Ui.segmented(c, listOf("شغّال", "مطفي"), if (rail.shown) 0 else 1) {
            val on = it == 0
            if (on != rail.shown) { rail.shown = on; recreate() }
        })
        col.addView(note("فوق البرامج الثانية (يوتيوب، Waze...): الشريط يطلع على يسارها."))
        val overModes = listOf("all", "map", "off")
        col.addView(Ui.segmented(c, listOf("كل البرامج", "تطبيق الخرائط بس", "لا"), overModes.indexOf(RailOverlay.mode(c)).coerceAtLeast(0)) {
            RailOverlay.setMode(c, overModes[it])
        })
        col.addView(title("مركبتك على الخريطة", 17f), Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(c, 8) })
        col.addView(vehiclePicker())
        col.addView(note("مجسمات السيارات (CC BY): BYD Dolphin وSeal من Nazh Design، Camry من Ford mondeo، Picanto من Nieve5677 (Sketchfab)."))
        col.addView(title("صوت التوجيه", 17f), Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(c, 8) })
        col.addView(Ui.segmented(c, listOf("شغّال", "مطفي"), if (mp.voice) 0 else 1) { mp.voice = it == 0 })
        showPanel("look", col)
    }

    /** Rows of vehicles (arrow, motorbike, BYD models) and a row of paint colours, with live previews. */
    private fun vehiclePicker(): View {
        val c = this
        val box = Ui.vbox(c)
        val models = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false }
        val modelRow = Ui.hbox(c)
        val colorRow = Ui.hbox(c)
        val colors = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(colorRow) }
        models.addView(modelRow)
        fun paint() {
            modelRow.removeAllViews()
            colorRow.removeAllViews()
            for (m in VehicleArt.models) {
                val on = m.id == mp.vehicle
                val cell = Ui.vbox(c).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 8), Ui.dp(c, 8))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = Ui.dp(c, 16).toFloat()
                        setColor(if (on) 0x332E8BFF else 0x14FFFFFF)
                        setStroke(Ui.dp(c, 2), if (on) Ui.ACCENT else 0x00000000)
                    }
                    setOnClickListener {
                        mp.vehicle = m.id
                        applyVehicle()
                        paint()
                    }
                }
                val img = ImageView(c).apply {
                    setImageBitmap(VehicleArt.bitmap(c, m,
                        if (m.kind == VehicleArt.Kind.ARROW && mp.vehicleColor == VehicleArt.colors[0].color) Ui.ACCENT else mp.vehicleColor, Ui.ACCENT, 46f))
                }
                cell.addView(img, Ui.lp(Ui.dp(c, 86), Ui.dp(c, 86)))
                cell.addView(Ui.label(c, m.name, 13f, if (on) Ui.WHITE else 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER })
                modelRow.addView(cell, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = Ui.dp(c, 8) })
            }
            colors.visibility = View.VISIBLE
            for (pw in VehicleArt.colors) {
                val on = pw.color == mp.vehicleColor
                val dot = View(c).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(pw.color)
                        setStroke(Ui.dp(c, if (on) 4 else 2), if (on) Ui.ACCENT else 0x55FFFFFF)
                    }
                    contentDescription = pw.name
                    setOnClickListener {
                        mp.vehicleColor = pw.color
                        applyVehicle()
                        paint()
                    }
                }
                colorRow.addView(dot, Ui.lp(Ui.dp(c, 44), Ui.dp(c, 44)).apply { marginEnd = Ui.dp(c, 12) })
            }
        }
        paint()
        box.addView(models)
        box.addView(colors, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(c, 10) })
        return box
    }

    // ================================================================== filming mode

    /**
     * Nothing on screen but the map: for filming the screen. Standing still, the camera circles
     * slowly around the car; while driving (or in a simulated drive) it follows from behind.
     * A tap anywhere brings the controls back.
     */
    private fun enterCinema() {
        if (mode == Mode.CINEMA) return
        beforeCinema = mode
        mode = Mode.CINEMA
        closePanel()
        hideKeyboard()
        left.visibility = View.GONE
        rail.view.visibility = View.GONE
        bottomRow.visibility = View.GONE
        zoomCol.visibility = View.GONE
        flashView.visibility = View.GONE
        HubService.instance?.hideBarForSheet()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cinemaBearing = ml?.cameraPosition?.bearing ?: 0.0
        ui.removeCallbacks(orbit)
        if (nav != null) follow() else ui.post(orbit)
    }

    private val orbit = object : Runnable {
        override fun run() {
            val m = ml
            if (mode != Mode.CINEMA || m == null) return
            if (nav != null) return // the chase camera is doing the work
            unfollow()
            cinemaBearing = (cinemaBearing + 9.0) % 360.0
            val pos = CameraPosition.Builder()
                .target(here())
                .zoom(max(m.cameraPosition.zoom, 16.3))
                .tilt(60.0)
                .bearing(cinemaBearing)
                .build()
            // Linear easing, chained: one smooth, endless turn.
            m.easeCamera(CameraUpdateFactory.newCameraPosition(pos), 1500, false)
            ui.postDelayed(this, 1500)
        }
    }

    private fun exitCinema() {
        if (mode != Mode.CINEMA) return
        ui.removeCallbacks(orbit)
        mode = if (beforeCinema == Mode.NAV && nav != null) Mode.NAV else Mode.BROWSE
        left.visibility = View.VISIBLE
        rail.view.visibility = if (rail.shown) View.VISIBLE else View.GONE
        bottomRow.visibility = View.VISIBLE
        zoomCol.visibility = View.VISIBLE
        HubService.instance?.onMapShown()
        if (mode == Mode.BROWSE) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateCompass()
        if (lastFix != null) follow()
    }

    // ================================================================== first-run cards

    /** One card at a time: overlay permission, each bundled app still to install, then the offline map. */
    private fun showCardIfNeeded() {
        card?.let { root.removeView(it) }
        card = null
        if (mode != Mode.BROWSE) return
        if (!Settings.canDrawOverlays(this)) {
            showCard(
                "خلّي الأيقونات تطلع فوق الخريطة",
                "فعّل «الظهور فوق البرامج» لـ ${getString(R.string.app_name)}",
                "فعّل",
                onSkip = { skipBundled = true }
            ) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
                }
            }
            return
        }
        val app = if (skipBundled) null else Bundled.needed(this).firstOrNull()
        if (app != null) {
            val update = Bundled.isUpdate(this, app)
            val sub = if (!packageManager.canRequestPackageInstalls()) {
                "أول شي اسمح للبرنامج يحط برامج، وارجع هنا"
            } else {
                "أندرويد راح يسألك توافق على التنصيب"
            }
            showCard(if (update) "أكو تحديث لـ ${app.title}" else "نصّب ${app.title}", sub, if (update) "حدّث" else "نصّب", onSkip = { skipBundled = true }) {
                Bundled.install(this, app)
            }
            return
        }
        if (!skipBundled && Bundled.oldAutoLeft(this)) {
            showCard("احذف DiAuto القديم", "ياخذ التلفون قبل ZMD Auto. أندرويد راح يسألك توافق على الحذف",
                "احذف", onSkip = { skipBundled = true }) {
                Bundled.removeOldAuto(this)
            }
            return
        }
        if (!skipPack && !MapData.complete(this) && !MapData.busy() && MapTiles.online) {
            showCard(
                "نزّل خريطة العراق",
                "مرة وحدة (حوالي 290 ميكا)، وبعدها الخريطة والبحث والاتجاهات تشتغل بكل العراق بدون نت",
                "نزّل",
                onSkip = { skipPack = true }
            ) {
                skipPack = true
                card?.let { root.removeView(it) }
                card = null
                MapData.start(this)
                paintDownload()
                showOffline()
            }
        }
    }

    private fun showCard(title: String, sub: String, action: String, onSkip: () -> Unit, onAction: () -> Unit) {
        val c = this
        val box = Ui.vbox(c).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = Ui.floatingGlass(c, 22f, opaque = true)
            val p = Ui.dp(c, 18)
            setPadding(p, p, p, p)
        }
        box.addView(Ui.label(c, title, 19f, bold = true))
        box.addView(Ui.label(c, sub, 14f, Ui.SUB), Ui.lp(Ui.MATCH, Ui.WRAP).apply {
            topMargin = Ui.dp(c, 4)
            bottomMargin = Ui.dp(c, 12)
        })
        val row = Ui.hbox(c)
        row.addView(Ui.button(c, action, primary = true) { onAction() },
            Ui.lp(0, Ui.WRAP, 1f).apply { marginEnd = Ui.dp(c, 8) })
        row.addView(Ui.button(c, "مو هسه") {
            onSkip()
            card?.let { root.removeView(it) }
            card = null
            showCardIfNeeded()
        }, Ui.lp(0, Ui.WRAP, 1f))
        box.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
        val w = min(Ui.dp(c, 420), (resources.displayMetrics.widthPixels * 0.9f).toInt())
        root.addView(box, FrameLayout.LayoutParams(w, Ui.WRAP, Gravity.CENTER))
        card = box
    }

    private companion object {
        const val SRC_ROUTE = "zmd-route"
        const val SRC_DONE = "zmd-route-done"
        const val SRC_ALTS = "zmd-route-alts"
        const val L_ALTS = "zmd-route-alts-line"
        const val SRC_PINS = "zmd-pins"
        const val L_ROUTE_CASING = "zmd-route-casing"
        const val L_ROUTE = "zmd-route-line"
        const val L_DONE = "zmd-route-done-line"
        const val L_PINS = "zmd-pins-layer"
        const val PIN_RED = "zmd-pin-red"
        const val PIN_BLUE = "zmd-pin-blue"
        const val VEHICLE_IMG = "zmd-vehicle"
        const val SRC_ME = "zmd-me"
        const val L_ME = "zmd-me-car"
        const val SRC_REPORTS = "zmd-reports"
        const val SRC_CAMS = "zmd-cams"
        const val L_CAMS = "zmd-cams-layer"
        const val L_REPORTS = "zmd-reports-layer"
        const val VEHICLE_BG = "zmd-vehicle-bg"
    }
}

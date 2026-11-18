# ZMD Car — built-in map (offline Iraq + online + search + navigation)

Owner of this work: the map session ("شونك"). Publishing (release build, store, car install) is done
by the store session. **If the map session stops, continue from the "Status" list at the bottom.**

## What the user asked for (2026-11-16/17)
- A complete map screen inside ZMD Car, very good looking, accurate, good "for filming".
- All of Iraq, downloadable, **our own offline map that the app relies on**.
- If the offline map is not downloaded: work online.
- Any street / place the car drives through is saved automatically, so it works offline later.
- Search, directions, and navigation that takes you anywhere.

## Design (decided)
One map engine (MapLibre 11.13.5, already in the app). Everything the map loads goes through
**one OkHttp interceptor** (`MapTiles.kt`) on a fixed fake host `https://map.zmd.local/…`:

| Request | Served from, in order |
|---|---|
| `/tiles/{z}/{x}/{y}.pbf` | 1. Iraq pack `iraq.mbtiles` → 2. auto-save cache `tiles-cache.db` → 3. internet (OpenFreeMap), then saved in 2 |
| `/fonts/{stack}/{range}.pbf` | 1. APK assets `map/fonts` → 2. cache → 3. internet → 4. empty glyph set (never an error) |
| `/sprites/…` | APK assets `map/sprites` |

- `MapLibre.setConnected(true)` is forced so MapLibre always asks; the interceptor decides.
- MapLibre's own ambient cache is not relied on (OpenFreeMap tile URLs change every week, so its
  cache keys break). Our cache is keyed by z/x/y only.
- Styles are our own JSON in `assets/map/` (day + night), OpenMapTiles schema, Arabic labels first.
- Zoom ≤ 7 prefers internet/cache over the pack (the pack only has Iraq, neighbours look empty).
- While driving online, tiles around the car (z14 block + parents) are prefetched into the cache.

### Offline data pack "iraq" (built on the PC, `A:\byd\MapData`)
Lives on the car in `getExternalFilesDir("maps")` = `/sdcard/Android/data/com.zmd.carhub/files/maps/`:

| File | What | Built by |
|---|---|---|
| `iraq.mbtiles` | vector tiles z0–14, OpenMapTiles schema | Planetiler (`scripts/build-tiles.ps1`) |
| `iraq-places.db` | SQLite + FTS4 search index (places, POIs, streets) | `scripts/BuildPlaces.java` |
| `iraq-gh/` (zip: `iraq-routing.zip`) | GraphHopper 1.0 car graph with CH | `scripts/build-routing.ps1` |

The app reads a manifest `maps.json` (URL next to the store's `index.json`) listing these files
(`url` or `parts`, `size`, `sha256`, `version`) and downloads them with resume. They can also be
pushed by adb straight into the folder above.

### Search
Offline: FTS4 prefix search over `iraq-places.db` with Arabic normalisation, ranked by importance
and distance. Online fallback: Nominatim (one request per search, Iraq only).

### Routing / navigation
Offline: GraphHopper 1.0 (`graphhopper-core`, car, fastest, CH) loaded memory-mapped from `iraq-gh`.
Online fallback: OSRM (routing.openstreetmap.de). Both map to one `Route` model.
`NavEngine`: snaps GPS to the route, next-turn banner, remaining distance/time, re-route when off
the road, Arabic voice (Android TTS when the car has an Arabic voice), simulated drive (for filming).

## Files
| File | Owner | Notes |
|---|---|---|
| `MapActivity.kt` | map session | rewritten; keeps the setup hook, `openChosenMap()`, install card |
| `MapTiles.kt`, `MapStyle.kt`, `MapData.kt`, `MapSearch.kt`, `MapRoute.kt`, `NavEngine.kt`, `MapUi.kt` | map session | new |
| `assets/map/**` | map session | styles, sprites, fonts |
| `app/build.gradle.kts` | store session | map session adds: okhttp 4.12.0, graphhopper-core 1.0 (+ excludes) |
| everything else | as before | |

## PC build pipeline (all on A:)
```
A:\byd\MapData\scripts\build-tiles.ps1     -> out\iraq.mbtiles
A:\byd\MapData\scripts\build-places.ps1    -> out\iraq-places.db
A:\byd\MapData\scripts\build-routing.ps1   -> out\iraq-routing.zip
A:\byd\MapData\scripts\make-manifest.ps1   -> out\maps.json
```
Sources: OpenStreetMap extract from Geofabrik (iraq), Natural Earth, OSM water polygons.
Credit shown in the app: © OpenStreetMap contributors, OpenMapTiles, OpenFreeMap.

## Status
- [x] Tools downloaded (Planetiler, GraphHopper 1.0 web jar), styles fetched
- [x] iraq.mbtiles built (189 MB)
- [x] iraq-places.db built (35 MB)
- [x] iraq-routing.zip built (80 MB)
- [x] MapTiles interceptor + styles + assets
- [x] MapActivity rewrite (look, controls)
- [x] Search (tested on the car: "Erbil" -> أربيل, offline)
- [x] Routing + navigation (tested on the car: Mosul -> Erbil 90 km offline route, simulated drive)
- [x] Offline maps manager + filming mode (UI present; filming mode not tested in depth)
- [x] Car test instead of emulator (no emulator on this PC) — store session, 2026-11-17 10:05-10:13
- [x] Handover done by the store session (map session was paused):
      build.gradle.kts: okhttp 4.12.0 + graphhopper-core 1.0 (xmlgraphics excluded) + META-INF excludes;
      MapRoute's `Step` renamed `RouteStep` (clashed with Overlay.kt's `Step`);
      ZMD Car 1.8 (code 18) installed on the car; pack pushed by adb to the car's maps folder;
      pack + maps.json uploaded to the "maps" release (MapData.DEFAULT_MANIFEST).

### Open / next
- Map is shown only when opened directly; the hub's map button follows Prefs map_pkg (Waze on the car).
- Reinstalling the app turns the accessibility service off on this screen: re-enable after installs.

### Added by the store session, 2026-11-17 (ZMD Car 1.9)
- VehicleArt.kt: map marker = 3D arrow (default) / motorbike / BYD models (real L×W), 8 colours, glow.
  Picker in the map's look panel; MapPrefs.vehicle / vehicleColor.
- enhance_styles.py (A:\byd\MapData\scripts): light for 3D, height-coloured buildings, night road glow,
  a second building source "zmd-buildings" (https://map.zmd.local/btiles/...). Re-run after make_styles.py.
- MapBuildings.kt: all-Iraq building footprints from one PMTiles file on GitHub (release "maps",
  iraq-buildings.pmtiles), read by HTTP range; kept for good in maps/buildings.db; per-governorate
  download (assets/map/governorates.json) + along-route / straight-ahead prefetch. MapTiles drops the
  OSM "building" layer of a tile where ours exists. (Data file still being built.)
- MapReports.kt + Cloudflare Durable Object (zmd-store worker, /api/reports): Waze-like shared road
  reports (pothole, bump, accident, police, traffic, hazard, camera, closure), voice warning ahead,
  "still there?" after passing. Yellow report button under the zoom buttons.
- StoreActivity: description behind a button / tap on the app, one open at a time, glowing card.

### Added by the map session, 2026-11-17 (after it came back)
- **Multiple routes** (user request): `MapRoute.routes(ctx, fromLat, fromLon, toLat, toLon, heading, max = 3)`
  → 1..3 `Route`s sorted by distance (index 0 = shortest, pre-selected in the store session's chooser UI);
  `Route.via` = what sets each one apart (street in town, town on long trips, e.g. «الكوت»).
  Offline: `RouteAlternatives.java` (plain Java, also runs on the PC:
  `java -cp tools\graphhopper\graphhopper-web-1.0.jar <app>\RouteAlternatives.java build\iraq-gh`) forces
  a via point on a main road left/right of the main route; drops U-turns at the via point, routes > 1.4×
  longer and routes > 70% shared. Online: OSRM `alternatives=true`, same filter. `route()` (re-route) unchanged.
  PC results: Baghdad→Basra 532 / 539 / 633 km, Baghdad→Erbil 358 / 382 / 420 km, Mosul→Erbil 89 / 104 / 120 km,
  alternatives in 26–220 ms on the PC.
- `MapSearch.nearestTown()` (for the via labels).
- NavVoice: tries every installed TTS engine for Arabic; with none, a short double beep per announcement.
- Setup-screen bug: HubService starts with the bar hidden while setup is pending; the permission alert
  now sits under the bar on the right instead of on top of it.
- Routing pack: `gh-config.yml` now has `datareader.preferred_language: ar` (Arabic street names where
  OSM has name:ar). Rebuilt 2026-11-17 13:40; new zip sha256 ed04804f…25d3. Re-upload iraq-routing.zip + maps.json.
- Zip bug fixed: the first iraq-routing.zip had "iraq-gh\file" (backslash) entries, which broke the in-app
  install. Now packed by `scripts\zip_routing.py` ("iraq-gh/file"), and MapData.unzipRouting accepts both.

### Speed limits + speed cameras (map session, 2026-11-17)
- `MapRoute.roadAt(ctx, lat, lon, bearing)` → road name + OSM maxspeed for the driving direction (offline
  graph). OSM coverage: motorway 74%, trunk 35%, primary 20%, residential 2%. Iraq's law gives no
  numeric defaults (set by directorate statements), so no guessed limits are shown.
- `SpeedCameras.kt`: cameras.json in the app (assets/map) + daily refresh from the "maps" release +
  driver reports (MapReports type "camera"). `ahead()` = nearest camera in a 30° cone ahead, watching
  this direction, 350 m–1.2 km by speed. Zones = announced radar expressways (Baghdad: محمد القاسم,
  اليوسفية/الدورة; lanes 80/100/120/120, announced 12/3/2024).
- `DriveHud.kt`: speed-limit sign, car speed (red when over), camera card + voice + bell (mute).
- Data: `scripts\ExtractCameras.java` (OSM → cameras-osm.json), `scripts\make_cameras.py` +
  `cameras\cameras-official.json` → `out\cameras.json` (393 cameras, 345 with a speed).
  Weekly cloud job: store repo `.github/workflows/cameras.yml` + `tools/cameras/`.
- No complete official camera list is published (Baghdad: 139 camera points + 60 radar points
  reported, locations not listed), so OSM + announcements + driver reports is the best available.

### CarPlay auto mode (map session, 2026-11-17, from the car's logs)
- DiPlay asks for USB itself (also from its background service on attach); after "allow" the iPhone
  re-enumerates into its CarPlay (NCM) configuration and DiPlay asks again. DiPlay's device_filter is a
  WCH chip (6790/21778), so Android never offers "always" for the iPhone.
- Fix: the hub asks no USB permission of its own; `HubService.iphoneAutoOpen()` opens CarPlay once no
  system dialog has been on screen for 2 s (`HubA11yService.systemDialogOpen()`).
- Optional switch (default OFF, user's choice): `HubA11yService` taps «Allow» on DiPlay's prompt.

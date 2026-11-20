# UsoolMap – AI 3D Global Navigation

Android navigation app (application ID `com.usool.map`) built on OpenStreetMap data and MapLibre.
It opens on Qatar by default, but nothing in the code limits it to a country: maps, search,
routing and positioning work wherever the underlying data exists.

## What is in this version

| Area | What the app does | Where |
| --- | --- | --- |
| Multi-GNSS positioning | Reads the device receiver through Android's `LocationManager` and `GnssStatus`. Reports GPS, GLONASS, BeiDou, Galileo, NavIC, QZSS and SBAS satellites as the hardware tracks them. Works with no internet. | `gnss/` |
| Signal-loss handling | Detects a stale fix, bridges short outages with dead reckoning (last speed plus rotation-sensor heading), snaps the position to the route, and re-registers the receiver after long silences. | `gnss/Continuity.kt`, `gnss/GnssMonitor.kt` |
| UsoolMap Satellite Center | Coordinates, accuracy, altitude, speed, heading, fix state, satellites in view / used, per-constellation counts, sky plot, signal bars, signal-loss warnings. | `ui/SatelliteCenterScreen.kt` |
| Worldwide map, 2D and 3D | Planet-wide OpenStreetMap vector tiles, tilt to 3D with building extrusion. Optional hillshade from a terrain source you configure. | `map/MapController.kt` |
| Build Missing 3D Buildings | For the area on screen, reads real OSM outlines and extrudes them only when a height, floor count or low-rise type is mapped. Estimated shapes are a different colour with a legend; outlines with no height clue stay flat. | `map/Buildings3D.kt` |
| Map appearance | Day and night palettes in Usool gold, black and white (`assets/map_themes.json`), switching automatically with the phone's dark mode or after sunset; 3D buildings shaded by height; place names in one language (phone language, Arabic, English or local). | `map/MapTheme.kt`, `map/MapController.kt` |
| 3D globe | Optional exploration view drawn by CesiumJS (bundled, Apache-2.0) in a WebView: globe with the chosen satellite imagery, the route and your position, fly-around, and a live frame-rate readout. Real terrain and OpenStreetMap 3D buildings with a Cesium ion token. Navigation stays on MapLibre. | `ui/GlobeScreen.kt`, `assets/globe/`, `assets/cesium/` |
| Assistant | The search box also takes commands in English and Arabic ("take me home", "nearest petrol station", "switch to 3D", "avoid toll roads"). Common ones are understood on the phone; others go to Grok, whose answer must match a fixed list of actions before anything happens. | `assistant/Assistant.kt` |
| Satellite view | Top-down imagery under the map labels, zoomable to level 21, with nearby tiles fetched ahead and a 512 MB on-phone tile cache for speed. Free Sentinel-2 (about 10 m per pixel) by default; 30 cm-class imagery when a MapTiler key or a licensed tile URL is configured. The map states the real detail of what is on screen. | `imagery/Satellite.kt`, `map/MapController.kt` |
| Street view | Real street-level photos from Mapillary (free token) and Panoramax (no key). 360° photos are rendered as a true sphere (OpenGL) and turned in any direction with a finger; arrows step to neighbouring photos without leaving the viewer; the map stays visible below with a camera marker and view cone at the photo's recorded position, and tapping the map moves the camera. Coordinates of each photo are shown and can be copied. | `imagery/StreetView.kt`, `ui/StreetViewScreen.kt`, `ui/PanoView.kt` |
| Search | Worldwide place and address search (Nominatim), multilingual names, plus coordinates in decimal, DMS and `geo:` form resolved on the device. | `search/`, `core/CoordinateParser.kt` |
| Nearby search in your language | Type or speak a kind of place in your own language ("مطعم حلال", "فندق", "pharmacy"): it is searched by category inside the area on the map, using the local term where it matters (halal is 清真 in Chinese). Results show the name as written locally, a translation, and the address in your language. Optional on-device translation (ML Kit). | `search/PoiConcepts.kt`, `search/Search.kt`, `search/MlKitTranslator.kt` |
| Routing | Drive, walk, cycle, motorcycle, truck (Valhalla), with alternative routes to choose from and optional toll avoidance; bus, metro, rail, public transport (Transitous). Only modes that can be routed right now are shown. | `routing/OnlineRouting.kt` |
| Offline maps and routing | Download any rectangle on Earth. Map tiles are stored by MapLibre; a road graph is built from OSM so drive / motorcycle / walk / cycle routes are calculated on the phone. | `map/OfflineRegions.kt`, `routing/RoadGraph.kt`, `routing/OfflineRouter.kt` |
| Navigation | Turn-by-turn progress, advance and at-turn announcements, rerouting, arrival, foreground service that runs only during a route. | `nav/` |
| Voice | **Professional**: the phone's own speech engine, no network, no AI. **Funny** and **Smart mixed**: professional guidance plus AI-generated humorous reactions to navigation events. Clips come from the Azure voice backend (`backend/`: Grok text, Azure Speech audio, Blob Storage, PostgreSQL) or, with no backend, are generated on the phone with an xAI key. They are cached locally, never repeat within 90 days, never replace an instruction, and are never played near a junction. An audio scheduler ranks safety alerts, instructions, information and jokes. | `voice/`, `voice/humour/`, `backend/`, `infra/` |
| Traffic, cameras, limits | Provider interface with per-country selection. Ships with TomTom Traffic (incidents, closures, construction, hazards, flow) and OpenStreetMap (speed limits, fixed cameras). Camera alerts are disabled in countries on a restricted list. | `traffic/` |
| Regional conventions | km or miles, km/h or mph, driving side, per country, detected from the mobile network, SIM or device language. | `core/RegionProfile.kt` |

## Real limits, stated plainly

- **Satellites.** The app shows what Android reports. It cannot switch constellations on, pick
  satellites or get around hardware limits. NavIC and QZSS are regional and many phones do not
  track them.
- **Map completeness** depends on OpenStreetMap coverage, which varies by country.
- **Satellite detail.** Imagery of 30 to 50 cm per pixel is commercial data (Maxar, Airbus and
  similar). The app shows it only with a provider key (Esri, Mapbox, MapTiler, or a licensed tile
  URL), and only where that provider holds detailed pictures: providers enlarge coarse imagery to
  fill deep zoom levels elsewhere, so the app never states a centimetre figure for them. Without
  a key, imagery is Sentinel-2 at about 10 m per pixel. No imagery is AI-generated or sharpened.
- **Street view** shows photos other people have uploaded. Many streets, especially outside
  Europe and North America, have none; the app then says so. It is not Google Street View, whose
  licence does not allow use on a non-Google map. Only photos taken with a 360° camera can be
  turned all the way round; ordinary photos can only be zoomed and panned.
- **China.** Positions are true WGS-84, matching OpenStreetMap and the app's tiles, so there is no
  GCJ-02 shift on the map; coordinates copied from Chinese map apps can be entered as
  `gcj:lat,lon`. BeiDou is used whenever the phone's receiver tracks it. OpenStreetMap has far
  fewer shops and restaurants in China than local apps, and Google-hosted pieces (translation
  model downloads, speech recognition on most phones) are unreachable there without preparation.
  Whether the default map, search and routing servers are reachable from inside China has not
  been tested.
- **Live traffic** needs a TomTom key and TomTom coverage. Without a key the app says the service
  is unavailable; it never shows invented traffic.
- **Public transport** only works where operators publish timetables that Transitous has loaded.
- **Truck routing** is online only and uses Valhalla's default truck profile (no per-vehicle
  dimensions yet).
- **Offline routing** is limited to regions up to 2,500 km² (about a metropolitan area), because
  the road graph is fetched from the public Overpass service. Larger downloads keep the map but
  not routing. Offline routes ignore live traffic, and turn restrictions are not modelled yet.
- **Terrain** is hillshading only, and only when `TERRAIN_TILES_URL` points at a terrain source you
  are licensed to use.
- **3D buildings** only cover simple outlines (OSM ways); multipolygon buildings are skipped.
- **Speed-camera legality.** The restricted-country list in `RegionProfile` is a conservative
  starting point and must be checked by a lawyer before release.
- **Public servers.** The default endpoints (OpenFreeMap, Nominatim, Valhalla at FOSSGIS,
  Overpass, Transitous) are community services with usage policies and no uptime guarantee. A
  store release should use hosted or commercial equivalents; each URL is a constructor parameter.
- **API keys in the APK.** TomTom, MapTiler and the voice-service app key are compiled into the app when set. That is fine
  for testing but they can be extracted; for release, put them behind your own server.

See `docs/STATUS.md` for what is implemented and the evidence for it, and
`docs/LICENSES-AND-DEPENDENCIES.md` for licences. The voice backend is in `backend/` (API in
`backend/API.md`) with Azure templates in `infra/`.

## Build

Requirements: JDK 17 or newer, Android SDK platform 35.

```bash
cp local.properties.example local.properties   # set sdk.dir, add keys if you have them
./gradlew testDebugUnitTest                    # 118 unit tests
./gradlew assembleDebug                        # app/build/outputs/apk/debug/UsoolMap-1.0.0-debug.apk
./gradlew bundleRelease                        # UsoolMap-1.0.0-release.aab (needs your signing key)
```

The MapTiler key (sharp satellite imagery) and the Mapillary token (worldwide street photos) can also
be pasted into the app under Settings, where they are stored on the phone only.

All keys are optional. With none set the app still runs: professional voice, maps, search,
routing, offline regions and the Satellite Center work; humorous voice and live traffic report
themselves as unavailable.

## Tests

Unit tests cover geometry, coordinate parsing, regional rules, constellation mapping, signal-loss
detection, dead reckoning, map matching, the offline road graph and router, instruction wording in
five languages, navigation announcements and rerouting, provider selection, 3D building
classification, and parsing of responses recorded from the live Valhalla, Transitous and
Nominatim services (`app/src/test/resources`).

## Data and licences

Map data © OpenStreetMap contributors, ODbL. Tiles: OpenFreeMap (OpenMapTiles schema).
Rendering: MapLibre Native (BSD). Routing: Valhalla. Transit: Transitous / MOTIS.

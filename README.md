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
| Search | Worldwide place and address search (Nominatim), multilingual names, plus coordinates in decimal, DMS and `geo:` form resolved on the device. | `search/`, `core/CoordinateParser.kt` |
| Routing | Drive, walk, cycle, motorcycle, truck (Valhalla); bus, metro, rail, public transport (Transitous). Only modes that can be routed right now are shown. | `routing/OnlineRouting.kt` |
| Offline maps and routing | Download any rectangle on Earth. Map tiles are stored by MapLibre; a road graph is built from OSM so drive / motorcycle / walk / cycle routes are calculated on the phone. | `map/OfflineRegions.kt`, `routing/RoadGraph.kt`, `routing/OfflineRouter.kt` |
| Navigation | Turn-by-turn progress, advance and at-turn announcements, rerouting, arrival, foreground service that runs only during a route. | `nav/` |
| Voice | Professional voice through the phone's own speech engine (no network, no AI). Optional humorous voice: Grok writes the line, Azure Speech voices it, clips are cached on disk and replayed offline. If a clip is not ready the professional voice speaks. | `voice/` |
| Traffic, cameras, limits | Provider interface with per-country selection. Ships with TomTom Traffic (incidents, closures, construction, hazards, flow) and OpenStreetMap (speed limits, fixed cameras). Camera alerts are disabled in countries on a restricted list. | `traffic/` |
| Regional conventions | km or miles, km/h or mph, driving side, per country, detected from the mobile network, SIM or device language. | `core/RegionProfile.kt` |

## Real limits, stated plainly

- **Satellites.** The app shows what Android reports. It cannot switch constellations on, pick
  satellites or get around hardware limits. NavIC and QZSS are regional and many phones do not
  track them.
- **Map completeness** depends on OpenStreetMap coverage, which varies by country.
- **Live traffic** needs a TomTom key and TomTom coverage. Without a key the app says the service
  is unavailable; it never shows invented traffic.
- **Alternative routes** have no provider yet and are reported as unavailable.
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
- **API keys in the APK.** Grok, Azure and TomTom keys are compiled into the app. That is fine
  for testing but they can be extracted; for release, put them behind your own server.

## Build

Requirements: JDK 17 or newer, Android SDK platform 35.

```bash
cp local.properties.example local.properties   # set sdk.dir, add keys if you have them
./gradlew testDebugUnitTest                    # 44 unit tests
./gradlew assembleDebug                        # app/build/outputs/apk/debug/UsoolMap-1.0.0-debug.apk
./gradlew bundleRelease                        # UsoolMap-1.0.0-release.aab (needs your signing key)
```

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

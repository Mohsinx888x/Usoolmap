# UsoolMap: what is implemented, and what the evidence is

Every claim here is backed by a build or a test, or says that it is not. "Device" means a real
phone: apart from the owner's own screenshots of the map and satellite layer, nothing has been
tested on one.

Evidence used: 118 Android unit tests and a debug APK plus release AAB that build; 124 backend
tests including runs against real PostgreSQL 16 and the Azurite storage emulator; responses
recorded from the live Valhalla, Nominatim, Transitous and Panoramax services; browser renders of
the map style, the 360° shader and the Cesium globe.

## Implemented

| Area | State | Evidence | Not verified |
| --- | --- | --- | --- |
| MapLibre Native map, 2D/3D, day/night, buildings, route line, markers | Done | Builds; style rules unit tested; rendered in a browser with the same style; owner's screenshots show it running | Performance on devices |
| Multi-GNSS positioning, Satellite Center | Done | Unit tests for constellations, signal loss, dead reckoning, map matching | Behaviour on real receivers |
| Search worldwide; nearby places in the user's language | Done | Tests on real Nominatim results (Doha, Guangzhou) | Translation model downloads |
| Routing with Valhalla: drive, walk, cycle, motorcycle, truck | Done | Tests on real Valhalla responses | |
| Alternative routes | Done | Test on a real response with two alternatives | |
| Avoid toll roads | Done | Request option unit tested | Effect on real routes |
| Public transport | Done where timetables exist | Test on a real Transitous itinerary | Coverage per country |
| Navigation: progress, announcements, rerouting, arrival, background service | Done | Unit tests simulate drives | A real drive |
| Offline regions and on-device routing | Done for areas up to 2,500 km² | Router unit tests on a synthetic network | Downloads from the live Overpass service, which refused connections from the build machine |
| Satellite imagery (free 10 m; sharper with the user's provider key) | Done | Free tiles fetched and viewed; owner confirmed MapTiler loads | Esri and Mapbox sources |
| Street view: 360° viewer, arrows, camera on map | Done | Shader rendered in a browser; navigation logic unit tested; real Panoramax data | Mapillary against the live service; the viewer on a device |
| CesiumJS 3D globe | Done, opt-in | Page run in a headless browser with imagery, route and position | WebView on a device: frame rate, memory, battery. Terrain and 3D buildings need a Cesium ion token and were not exercised |
| Voice: Professional, Funny, Smart mixed; audio priorities; joke safety rules; clip library; no repeat within 90 days | Done | Unit tests for every rule | Audio on a device |
| Voice backend on Azure (Grok text, Azure Speech audio, Blob, PostgreSQL, queue, quotas, budget, pronunciation dictionary) | Code complete | 124 tests, including real PostgreSQL and the Azurite emulator | Not deployed. No real Grok or Azure Speech call was made, so no audio has been produced or listened to. Bicep templates were not compiled |
| Assistant commands ("take me home", "nearest pharmacy", Arabic equivalents) | Done | Unit tests; every AI result passes a fixed whitelist | AI fallback against the real model |
| Privacy: delete my history | Done | Backend store and API tests | |
| APK and AAB builds on GitHub Actions | Done | APK workflow has passed on GitHub; AAB built locally | The AAB job on GitHub; signing needs the owner's keystore |

## Partly implemented

| Area | What exists | What is missing |
| --- | --- | --- |
| Live traffic, incidents, closures | Provider interface, per-country selection, TomTom adapter, "show traffic" command | A TomTom key and licence. Without one the app says traffic is unavailable. No traffic overlay colouring of roads yet, only incident markers |
| Speed limits and cameras | From OpenStreetMap, with a restricted-country list | Depends on the Overpass service (see above). The country list needs legal review |
| Terrain | Hillshade when a terrain tile URL is configured; real terrain on the Cesium globe with an ion token | No terrain source is configured by default |
| Natural Qatari / Gulf speech | Gulf-dialect prompts, full tashkeel, regional Azure voices, reviewed-dictionary mechanism, pronunciation reports, a QA script | Nobody has listened to real output. A regional voice does not guarantee colloquial Qatari speech; the dictionary's seed entries are unreviewed |
| Anti-repetition | Exact, opening-phrase and lexical-similarity checks, per-device history | Similarity is lexical, not a measure of meaning |
| Self-hosted map services | Design and tool choices documented | Tiles, routing and search still use free public servers, which are not suitable for a store launch |

## Not implemented

- Multiple waypoints on a route.
- Offline place search (only coordinates work offline).
- Levantine Arabic voice (Azure voices for it are not in the backend's catalogue yet).
- Room database: preferences and small JSON indexes are used instead; nothing so far needs relational storage on the phone.
- Measured performance, battery and memory figures on physical devices.

## Needs paid or licensed data

Live traffic (TomTom or similar), sharp satellite imagery (Esri, Mapbox, MapTiler), Cesium ion
terrain and buildings beyond its free tier, xAI Grok and Azure Speech usage, and the Azure
resources themselves.

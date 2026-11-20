# Dependencies and licence compatibility

Licences below are stated as understood when this was written. Check each project's own LICENSE
file before a commercial release; this is an engineering summary, not legal advice.

## What UsoolMap actually ships

| Component | Version | Role | Licence | Notes |
| --- | --- | --- | --- | --- |
| MapLibre Native for Android (`org.maplibre.gl:android-sdk`) | 11.13.5 | Primary map engine | BSD-2-Clause | Attribution in the About screen |
| CesiumJS (bundled in `assets/cesium`) | 1.147.0 | Optional 3D globe in a WebView | Apache-2.0 | Licence file bundled alongside. Cesium ion data (terrain, OSM Buildings) is a separate service with its own terms and needs the user's own token |
| Jetpack Compose, AndroidX, WebKit | BOM 2025.05.00 | UI | Apache-2.0 | |
| Kotlin, kotlinx.coroutines | 2.1.21 / 1.10.2 | Language, concurrency | Apache-2.0 | |
| OkHttp | 4.12.0 | HTTP | Apache-2.0 | |
| Gson | 2.13.1 | JSON | Apache-2.0 | |
| Coil | 2.7.0 | Image loading | Apache-2.0 | |
| ML Kit Translation | 17.0.3 | On-device translation | Google ML Kit Terms (proprietary, free to use) | Not open source. Removing it removes only name translation |
| Backend: Fastify, node-postgres, Azure SDKs | see `backend/package.json` | Voice service | MIT | |

All of these are permissive and compatible with distributing UsoolMap under any licence,
including a closed one. Nothing copyleft is linked into the app.

## Data and online services (terms, not code licences)

| Source | Used for | Terms to respect |
| --- | --- | --- |
| OpenStreetMap data | Maps, search, routing, buildings | ODbL: attribute "© OpenStreetMap contributors"; share-alike applies to derived databases |
| OpenFreeMap tiles | Default base map | Free public service, OpenMapTiles schema; no uptime guarantee |
| Nominatim public server | Search | 1 request per second, no bulk use; host your own for production |
| Valhalla public server (FOSSGIS) | Routing | Fair-use demo server; host your own for production |
| Overpass API | Building outlines, cameras, offline road data | Fair use |
| Transitous | Public transport | Community service; feed licences vary |
| Mapillary, Panoramax | Street photos | CC BY-SA 4.0 and per-instance licences; attribution shown in the viewer |
| Sentinel-2 cloudless 2016 (EOX) | Free satellite layer | CC BY 4.0 |
| Esri, Mapbox, MapTiler | Sharper satellite imagery | Commercial; the user's own key and plan |
| TomTom | Live traffic | Commercial licence required |
| xAI Grok, Azure AI Speech | Jokes and their audio | Paid per use |

## Projects in the plan and what was decided

| Project | Licence | Decision |
| --- | --- | --- |
| MapLibre Native | BSD-2-Clause | **Used** as the map engine |
| Valhalla | MIT | **Used** through its HTTP API (public server by default). Self-hosting on Azure is recommended for production and is not done here |
| CesiumJS | Apache-2.0 | **Used** for a separate, opt-in 3D globe. Not used for navigation: it runs in a WebView and is heavier than MapLibre. The screen shows its own frame rate so the cost can be judged per device |
| Organic Maps | Apache-2.0 | **Reference only.** No code taken. UsoolMap's offline design (region downloads, on-device routing graph) follows the same idea in its own, much simpler implementation |
| OsmAnd | GPL-3.0 (with additional terms on some assets) | **Reference only, and no code may be copied**: GPL code would force the whole app under the GPL |
| Nominatim | GPL-3.0 (server) | **Used as a network service only.** The GPL does not reach an app that calls its API |
| Pelias | MIT | Not used; Nominatim covers the need |
| GraphHopper | Apache-2.0 | Not used; Valhalla is the routing engine. A reasonable alternative if on-device routing is ever needed at scale |
| OpenTripPlanner | LGPL-3.0 | Not used; transit comes from Transitous (MOTIS). Worth evaluating for a self-hosted transit service |
| Planetiler | Apache-2.0 | Not used yet. The tool to generate UsoolMap's own vector tiles when moving off public tile servers |
| Martin | MIT / Apache-2.0 | Not used yet. Would serve those tiles |
| PMTiles | BSD-3-Clause | Not used yet. Good fit for downloadable regional archives |
| Osmium Tool | GPL-3.0 | Not used. As a command-line data tool run on a server, its licence would not affect the app |

## Compatibility conclusions

1. Everything linked into the APK is BSD, MIT or Apache-2.0, plus ML Kit under Google's terms. No conflict.
2. GPL projects (OsmAnd, Nominatim, Osmium) are either not used or used only as separate services or tools, never as linked code.
3. The real obligations are about data and services, not code: OSM attribution and share-alike, the usage limits of the free public servers, and commercial licences for traffic and sharp imagery.

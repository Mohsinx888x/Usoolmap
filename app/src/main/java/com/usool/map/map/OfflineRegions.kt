package com.usool.map.map

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.usool.map.core.BBox
import com.usool.map.core.Http
import com.usool.map.routing.RoadGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.io.File
import java.net.URLEncoder

enum class RoadDataState { NONE, DOWNLOADING, READY, TOO_LARGE, FAILED }

data class OfflineRegionInfo(
    val id: Long,
    val name: String,
    val bbox: BBox,
    val tilesDone: Long = 0,
    val tilesRequired: Long = 0,
    val sizeBytes: Long = 0,
    val mapComplete: Boolean = false,
    val mapDownloading: Boolean = false,
    val roadData: RoadDataState = RoadDataState.NONE,
    val error: String? = null,
) {
    val mapPercent: Int get() = if (tilesRequired > 0) (100 * tilesDone / tilesRequired).toInt().coerceIn(0, 100) else 0
}

/**
 * Downloadable regions for use without a connection. A region is any rectangle on Earth chosen by
 * the user. Each one stores two things:
 *  - map tiles, fonts and icons, kept by MapLibre's offline database, and
 *  - a road graph built from OpenStreetMap, which lets the app calculate routes on the device.
 *
 * The road graph is limited to [MAX_ROAD_AREA_KM2] per region because it is fetched from the
 * public Overpass service; larger regions still get their map but no offline routing, and the
 * region list says so.
 */
class OfflineRegions(context: Context, private val http: Http, private val scope: CoroutineScope, private val styleUrl: String = MapConfig.STYLE_URL) {
    private val appContext = context.applicationContext
    private val manager by lazy { OfflineManager.getInstance(appContext).also { it.setOfflineMapboxTileCountLimit(TILE_LIMIT) } }
    private val graphDir = File(appContext.filesDir, "road-graphs").also { it.mkdirs() }
    private val regionsById = HashMap<Long, OfflineRegion>()

    private val _regions = MutableStateFlow<List<OfflineRegionInfo>>(emptyList())
    val regions: StateFlow<List<OfflineRegionInfo>> = _regions.asStateFlow()

    @Volatile private var loadedGraphs: List<RoadGraph> = emptyList()

    /** Road graphs currently in memory, used by the offline router. */
    fun graphs(): List<RoadGraph> = loadedGraphs

    fun refresh() {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val list = offlineRegions.orEmpty()
                regionsById.clear()
                val infos = list.map { region ->
                    regionsById[region.id] = region
                    val meta = runCatching { JsonParser.parseString(String(region.metadata)).asJsonObject }.getOrNull()
                    val b = (region.definition as? OfflineTilePyramidRegionDefinition)?.bounds
                    val bbox = b?.let { BBox(it.latitudeSouth, it.longitudeWest, it.latitudeNorth, it.longitudeEast) } ?: BBox(0.0, 0.0, 0.0, 0.0)
                    val previous = _regions.value.firstOrNull { it.id == region.id }
                    OfflineRegionInfo(
                        id = region.id,
                        name = meta?.get("name")?.asString ?: "Region ${region.id}",
                        bbox = bbox,
                        roadData = when {
                            graphFile(region.id).isFile -> RoadDataState.READY
                            previous != null -> previous.roadData
                            bbox.areaKm2 > MAX_ROAD_AREA_KM2 -> RoadDataState.TOO_LARGE
                            else -> RoadDataState.NONE
                        },
                    )
                }
                _regions.value = infos
                list.forEach { observe(it) }
                loadGraphs()
                // Road data that never finished (app closed mid-download) is fetched again.
                infos.filter { it.roadData == RoadDataState.NONE || it.roadData == RoadDataState.DOWNLOADING }.forEach { downloadRoads(it.id, it.bbox) }
            }

            override fun onError(error: String) = Unit
        })
    }

    fun download(name: String, bbox: BBox, pixelRatio: Float) {
        val bounds = LatLngBounds.Builder().include(LatLng(bbox.north, bbox.east)).include(LatLng(bbox.south, bbox.west)).build()
        val definition = OfflineTilePyramidRegionDefinition(styleUrl, bounds, MIN_ZOOM, MAX_ZOOM, pixelRatio)
        val metadata = JsonObject().apply { addProperty("name", name) }.toString().toByteArray()
        manager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                regionsById[offlineRegion.id] = offlineRegion
                val tooLarge = bbox.areaKm2 > MAX_ROAD_AREA_KM2
                _regions.update { it + OfflineRegionInfo(offlineRegion.id, name, bbox, mapDownloading = true, roadData = if (tooLarge) RoadDataState.TOO_LARGE else RoadDataState.DOWNLOADING) }
                observe(offlineRegion)
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                if (!tooLarge) downloadRoads(offlineRegion.id, bbox)
            }

            override fun onError(error: String) {
                _regions.update { it + OfflineRegionInfo(-System.nanoTime(), name, bbox, error = error) }
            }
        })
    }

    /** Restarts anything left unfinished, for example after the connection dropped mid-download. */
    fun resumeIncomplete() {
        for (info in _regions.value) {
            val region = regionsById[info.id] ?: continue
            if (!info.mapComplete) {
                region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                patch(info.id) { it.copy(mapDownloading = true, error = null) }
            }
            if (info.roadData == RoadDataState.FAILED || info.roadData == RoadDataState.NONE) downloadRoads(info.id, info.bbox)
        }
    }

    fun delete(id: Long) {
        graphFile(id).delete()
        val region = regionsById.remove(id)
        _regions.update { list -> list.filterNot { it.id == id } }
        loadGraphs()
        region?.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() = Unit
            override fun onError(error: String) = Unit
        })
    }

    private fun observe(region: OfflineRegion) {
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                patch(region.id) {
                    it.copy(
                        tilesDone = status.completedResourceCount,
                        tilesRequired = status.requiredResourceCount,
                        sizeBytes = status.completedResourceSize,
                        mapComplete = status.isComplete,
                        mapDownloading = !status.isComplete && status.downloadState == OfflineRegion.STATE_ACTIVE,
                    )
                }
                if (status.isComplete) region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            }

            override fun onError(error: OfflineRegionError) {
                patch(region.id) { it.copy(error = error.message) }
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                patch(region.id) { it.copy(mapDownloading = false, error = "This region is too large to download in one piece. Choose a smaller area.") }
            }
        })
        region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) {
                status ?: return
                patch(region.id) {
                    it.copy(
                        tilesDone = status.completedResourceCount, tilesRequired = status.requiredResourceCount,
                        sizeBytes = status.completedResourceSize, mapComplete = status.isComplete, mapDownloading = !status.isComplete,
                    )
                }
                // An unfinished map download continues where it stopped.
                if (!status.isComplete) region.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }

            override fun onError(error: String?) = Unit
        })
    }

    private fun downloadRoads(id: Long, bbox: BBox) {
        if (bbox.areaKm2 > MAX_ROAD_AREA_KM2) { patch(id) { it.copy(roadData = RoadDataState.TOO_LARGE) }; return }
        patch(id) { it.copy(roadData = RoadDataState.DOWNLOADING) }
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val url = "$OVERPASS_URL?data=" + URLEncoder.encode(RoadGraph.overpassQuery(bbox), "UTF-8")
                val graph = http.getStream(url) { RoadGraph.fromOverpass(it, bbox) }
                val tmp = File(graphDir, "$id.tmp")
                tmp.outputStream().use { graph.write(it) }
                tmp.renameTo(graphFile(id))
            }.isSuccess
            patch(id) { it.copy(roadData = if (ok) RoadDataState.READY else RoadDataState.FAILED) }
            if (ok) loadGraphs()
        }
    }

    private fun loadGraphs() {
        scope.launch {
            loadedGraphs = withContext(Dispatchers.IO) {
                graphDir.listFiles { f -> f.name.endsWith(".graph") }.orEmpty().mapNotNull { f ->
                    runCatching { f.inputStream().use { RoadGraph.read(it) } }.getOrNull()
                }
            }
        }
    }

    private fun graphFile(id: Long) = File(graphDir, "$id.graph")

    private fun patch(id: Long, change: (OfflineRegionInfo) -> OfflineRegionInfo) {
        _regions.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    companion object {
        const val MAX_ROAD_AREA_KM2 = 2_500.0
        private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
        private const val MIN_ZOOM = 0.0
        private const val MAX_ZOOM = 15.0
        private const val TILE_LIMIT = 150_000L
    }
}

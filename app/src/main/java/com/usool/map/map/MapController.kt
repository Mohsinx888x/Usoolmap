package com.usool.map.map

import android.graphics.Color
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.usool.map.core.BBox
import com.usool.map.core.GeoPoint
import com.usool.map.traffic.RoadIncident
import com.usool.map.traffic.SpeedCamera
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterDemSource
import org.maplibre.android.style.sources.TileSet

/**
 * Map display settings. The base map is a worldwide OpenStreetMap vector tile set (OpenMapTiles
 * schema), so the same style renders every country. Swap [STYLE_URL] to use another compatible
 * tile host.
 */
object MapConfig {
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    /** Layer in the base style that extrudes buildings from OpenStreetMap footprints. */
    const val BASE_BUILDINGS_3D = "building-3d"
    const val PITCH_3D = 58.0

    const val COLOR_MEASURED = "#b9b4ab"
    const val COLOR_FROM_LEVELS = "#f0a53a"
    const val COLOR_FROM_TYPE = "#f6cf7d"
    const val COLOR_FOOTPRINT = "#c9d3de"
}

/** Everything the app draws on top of the base map. Created once the style has loaded. */
class MapController(val map: MapLibreMap, private val style: Style, terrainTilesUrl: String) {
    private val routeSource = GeoJsonSource(SRC_ROUTE, EMPTY)
    private val positionSource = GeoJsonSource(SRC_POSITION, EMPTY)
    private val destinationSource = GeoJsonSource(SRC_DESTINATION, EMPTY)
    private val buildingsSource = GeoJsonSource(SRC_BUILDINGS, EMPTY)
    private val roadInfoSource = GeoJsonSource(SRC_ROAD_INFO, EMPTY)

    var is3D = false
        private set
    var hasBuiltBuildings = false
        private set
    val terrainAvailable: Boolean = terrainTilesUrl.isNotBlank()

    /** First label layer of the base style; the app's fills and lines go underneath it. */
    private val firstLabelLayer: String? = style.layers.firstOrNull { it is SymbolLayer }?.id

    private fun addBelowLabels(layer: Layer) {
        val anchor = firstLabelLayer
        if (anchor != null) style.addLayerBelow(layer, anchor) else style.addLayer(layer)
    }

    init {
        if (terrainAvailable) {
            // Shaded relief from a raster elevation source (terrarium encoding).
            val tiles = TileSet("2.1.0", terrainTilesUrl).apply { encoding = "terrarium"; maxZoom = 14f }
            style.addSource(RasterDemSource(SRC_TERRAIN, tiles, 256))
            val hill = HillshadeLayer(LAYER_TERRAIN, SRC_TERRAIN).withProperties(PropertyFactory.hillshadeExaggeration(0.4f))
            val firstSymbol = style.layers.firstOrNull { it.id.contains("water") }?.id
            if (firstSymbol != null) style.addLayerBelow(hill, firstSymbol) else style.addLayer(hill)
        }

        style.addSource(buildingsSource)
        addBelowLabels(
            FillLayer(LAYER_BUILDINGS_FLAT, SRC_BUILDINGS).withProperties(
                PropertyFactory.fillColor(MapConfig.COLOR_FOOTPRINT),
                PropertyFactory.fillOpacity(0.7f),
                PropertyFactory.fillOutlineColor("#8a97a6"),
            ).withFilter(Expression.eq(Expression.get("quality"), Expression.literal("FOOTPRINT_ONLY"))),
        )
        addBelowLabels(
            FillExtrusionLayer(LAYER_BUILDINGS_3D, SRC_BUILDINGS).withProperties(
                PropertyFactory.fillExtrusionHeight(Expression.get("height")),
                PropertyFactory.fillExtrusionBase(Expression.get("min_height")),
                PropertyFactory.fillExtrusionOpacity(0.85f),
                PropertyFactory.fillExtrusionColor(
                    Expression.match(
                        Expression.get("quality"),
                        Expression.literal("MEASURED"), Expression.color(Color.parseColor(MapConfig.COLOR_MEASURED)),
                        Expression.literal("FROM_LEVELS"), Expression.color(Color.parseColor(MapConfig.COLOR_FROM_LEVELS)),
                        Expression.color(Color.parseColor(MapConfig.COLOR_FROM_TYPE)),
                    ),
                ),
            ).withFilter(Expression.neq(Expression.get("quality"), Expression.literal("FOOTPRINT_ONLY"))),
        )

        style.addSource(routeSource)
        addBelowLabels(
            LineLayer(LAYER_ROUTE_CASING, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor("#0b3d91"), PropertyFactory.lineWidth(9f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        addBelowLabels(
            LineLayer(LAYER_ROUTE, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor("#2f7df6"), PropertyFactory.lineWidth(6f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )

        style.addSource(roadInfoSource)
        style.addLayer(
            CircleLayer(LAYER_ROAD_INFO, SRC_ROAD_INFO).withProperties(
                PropertyFactory.circleRadius(7f), PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleColor(
                    Expression.match(
                        Expression.get("kind"),
                        Expression.literal("camera"), Expression.color(Color.parseColor("#7b2ff7")),
                        Expression.literal("CLOSURE"), Expression.color(Color.parseColor("#c62828")),
                        Expression.literal("ACCIDENT"), Expression.color(Color.parseColor("#e53935")),
                        Expression.literal("CONSTRUCTION"), Expression.color(Color.parseColor("#ef6c00")),
                        Expression.color(Color.parseColor("#f9a825")),
                    ),
                ),
            ),
        )

        style.addSource(destinationSource)
        style.addLayer(
            CircleLayer(LAYER_DESTINATION, SRC_DESTINATION).withProperties(
                PropertyFactory.circleRadius(9f), PropertyFactory.circleColor("#d81b60"),
                PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(3f),
            ),
        )

        style.addSource(positionSource)
        style.addLayer(
            CircleLayer(LAYER_POSITION_HALO, SRC_POSITION).withProperties(
                PropertyFactory.circleRadius(18f), PropertyFactory.circleColor("#2f7df6"), PropertyFactory.circleOpacity(0.2f),
            ),
        )
        style.addLayer(
            CircleLayer(LAYER_POSITION, SRC_POSITION).withProperties(
                PropertyFactory.circleRadius(8f),
                // Grey while the position is only estimated (dead reckoning), blue on a satellite fix.
                PropertyFactory.circleColor(
                    Expression.switchCase(Expression.get("estimated"), Expression.color(Color.parseColor("#7a8794")), Expression.color(Color.parseColor("#2f7df6"))),
                ),
                PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(3f),
            ),
        )
    }

    fun setRoute(points: List<GeoPoint>?) {
        routeSource.setGeoJson(if (points.isNullOrEmpty()) EMPTY else lineFeature(points))
    }

    fun setDestination(p: GeoPoint?) {
        destinationSource.setGeoJson(if (p == null) EMPTY else pointFeature(p, JsonObject()))
    }

    fun setPosition(p: GeoPoint?, estimated: Boolean) {
        positionSource.setGeoJson(if (p == null) EMPTY else pointFeature(p, JsonObject().apply { addProperty("estimated", estimated) }))
    }

    fun setRoadInfo(incidents: List<RoadIncident>, cameras: List<SpeedCamera>) {
        val features = JsonArray()
        incidents.forEach { features.add(pointFeatureObject(it.point, JsonObject().apply { addProperty("kind", it.kind.name) })) }
        cameras.forEach { features.add(pointFeatureObject(it.point, JsonObject().apply { addProperty("kind", "camera") })) }
        roadInfoSource.setGeoJson(JsonObject().apply { addProperty("type", "FeatureCollection"); add("features", features) }.toString())
    }

    /** Switches between flat 2D and tilted 3D with extruded buildings. */
    fun set3D(enabled: Boolean) {
        is3D = enabled
        val cam = map.cameraPosition
        map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(cam).tilt(if (enabled) MapConfig.PITCH_3D else 0.0).build()), 600)
        applyBuildingVisibility()
    }

    /** Shows buildings produced by [Buildings3D]; pass null to return to the base map's buildings. */
    fun setBuiltBuildings(geoJson: String?) {
        hasBuiltBuildings = geoJson != null
        buildingsSource.setGeoJson(geoJson ?: EMPTY)
        applyBuildingVisibility()
    }

    private fun applyBuildingVisibility() {
        // While a built area is shown, the base layer is hidden so the two never overlap and
        // every visible 3D shape has a known height quality.
        val base = if (is3D && !hasBuiltBuildings) Property.VISIBLE else Property.NONE
        style.getLayer(MapConfig.BASE_BUILDINGS_3D)?.setProperties(PropertyFactory.visibility(base))
        val built = if (is3D) Property.VISIBLE else Property.NONE
        style.getLayer(LAYER_BUILDINGS_3D)?.setProperties(PropertyFactory.visibility(built))
    }

    fun visibleBounds(): BBox {
        val b = map.projection.visibleRegion.latLngBounds
        return BBox(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast)
    }

    val zoom: Double get() = map.cameraPosition.zoom
    val center: GeoPoint get() = map.cameraPosition.target?.let { GeoPoint(it.latitude, it.longitude) } ?: GeoPoint(0.0, 0.0)

    fun moveTo(p: GeoPoint, zoom: Double = 15.0) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), zoom), 800)
    }

    fun fit(points: List<GeoPoint>) {
        if (points.size < 2) return
        val builder = LatLngBounds.Builder()
        points.forEach { builder.include(LatLng(it.lat, it.lon)) }
        runCatching { map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 120), 800) }
    }

    /** Camera used while navigating: behind the vehicle, looking along the direction of travel. */
    fun follow(p: GeoPoint, bearingDeg: Double) {
        val position = CameraPosition.Builder().target(LatLng(p.lat, p.lon)).zoom(16.8)
            .tilt(if (is3D) MapConfig.PITCH_3D else 0.0).bearing(bearingDeg).build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(position), 900)
    }

    companion object {
        private const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"
        private const val SRC_ROUTE = "usool-route"
        private const val SRC_POSITION = "usool-position"
        private const val SRC_DESTINATION = "usool-destination"
        private const val SRC_BUILDINGS = "usool-buildings"
        private const val SRC_ROAD_INFO = "usool-road-info"
        private const val SRC_TERRAIN = "usool-terrain"
        private const val LAYER_ROUTE = "usool-route-line"
        private const val LAYER_ROUTE_CASING = "usool-route-casing"
        private const val LAYER_POSITION = "usool-position-dot"
        private const val LAYER_POSITION_HALO = "usool-position-halo"
        private const val LAYER_DESTINATION = "usool-destination-dot"
        private const val LAYER_BUILDINGS_3D = "usool-buildings-3d"
        private const val LAYER_BUILDINGS_FLAT = "usool-buildings-flat"
        private const val LAYER_ROAD_INFO = "usool-road-info-dots"
        private const val LAYER_TERRAIN = "usool-terrain-hillshade"

        private fun pointFeatureObject(p: GeoPoint, props: JsonObject) = JsonObject().apply {
            addProperty("type", "Feature")
            add("geometry", JsonObject().apply {
                addProperty("type", "Point")
                add("coordinates", JsonArray().apply { add(p.lon); add(p.lat) })
            })
            add("properties", props)
        }

        private fun pointFeature(p: GeoPoint, props: JsonObject) = pointFeatureObject(p, props).toString()

        private fun lineFeature(points: List<GeoPoint>): String = JsonObject().apply {
            addProperty("type", "Feature")
            add("geometry", JsonObject().apply {
                addProperty("type", "LineString")
                add("coordinates", JsonArray().apply { points.forEach { p -> add(JsonArray().apply { add(p.lon); add(p.lat) }) } })
            })
            add("properties", JsonObject())
        }.toString()
    }
}

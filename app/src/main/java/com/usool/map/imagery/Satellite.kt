package com.usool.map.imagery

import kotlin.math.cos
import kotlin.math.pow

/**
 * A source of top-down (satellite or aerial) imagery, served as standard XYZ raster tiles.
 *
 * [maxZoom] is only the deepest tile level the service answers. Commercial providers enlarge
 * their pictures to fill deep zoom levels wherever they hold no detailed imagery, so the tile
 * level says nothing about how sharp a given place is. The app therefore states a ground
 * resolution only when it is a property of the sensor ([knownResolutionM]) and otherwise says
 * plainly that sharpness varies by place.
 */
data class SatelliteSource(
    val id: String,
    val name: String,
    /** XYZ template with {z}, {x}, {y}. */
    val tileUrl: String,
    val maxZoom: Int,
    val attribution: String,
    /** Real ground resolution in metres per pixel when it is the same everywhere; null when it varies. */
    val knownResolutionM: Double?,
)

/** Provider keys available to the app. Blank means "not provided". */
data class ImageryKeys(
    val esri: String = "",
    val mapbox: String = "",
    val mapTiler: String = "",
    val customUrl: String = "",
    val customName: String = "",
    val customMaxZoom: Int = 19,
    val customAttribution: String = "",
)

object SatelliteCatalog {
    const val AUTO = "auto"

    /**
     * Free worldwide base layer: Sentinel-2 cloudless 2016 by EOX (CC BY 4.0). Sentinel-2 records
     * about 10 m per pixel, enough to see fields, coastlines and city blocks, not cars or people.
     */
    val SENTINEL2 = SatelliteSource(
        id = "sentinel2",
        name = "Sentinel-2 (free)",
        tileUrl = "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless_3857/default/g/{z}/{y}/{x}.jpg",
        maxZoom = 14,
        attribution = "Sentinel-2 cloudless by EOX IT Services GmbH (contains modified Copernicus Sentinel data 2016)",
        knownResolutionM = 10.0,
    )

    /**
     * Every source usable with [keys], most detailed first. Sub-metre imagery is commercial data:
     * it appears only with the matching provider key, and only where that provider has flown or
     * bought detailed pictures.
     */
    fun available(keys: ImageryKeys): List<SatelliteSource> = buildList {
        if (keys.customUrl.isNotBlank()) {
            add(SatelliteSource("custom", keys.customName.ifBlank { "Licensed imagery" }, keys.customUrl, keys.customMaxZoom.coerceIn(1, 22), keys.customAttribution, null))
        }
        if (keys.esri.isNotBlank()) {
            // Esri World Imagery: detailed Maxar and aerial imagery for most cities worldwide.
            add(SatelliteSource(
                "esri", "Esri World Imagery",
                "https://ibasemaps-api.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}?token=${keys.esri.trim()}",
                19, "Powered by Esri. Source: Esri, Maxar, Earthstar Geographics, and the GIS User Community", null,
            ))
        }
        if (keys.mapbox.isNotBlank()) {
            add(SatelliteSource(
                "mapbox", "Mapbox Satellite",
                "https://api.mapbox.com/v4/mapbox.satellite/{z}/{x}/{y}.jpg90?access_token=${keys.mapbox.trim()}",
                19, "© Mapbox © Maxar", null,
            ))
        }
        if (keys.mapTiler.isNotBlank()) {
            add(SatelliteSource(
                "maptiler", "MapTiler Satellite",
                "https://api.maptiler.com/tiles/satellite-v2/{z}/{x}/{y}.jpg?key=${keys.mapTiler.trim()}",
                20, "© MapTiler and imagery providers", null,
            ))
        }
        add(SENTINEL2)
    }

    /** The source named by [choice], or the most detailed available one for [AUTO] or an unknown id. */
    fun select(keys: ImageryKeys, choice: String = AUTO): SatelliteSource {
        val all = available(keys)
        return all.firstOrNull { it.id == choice } ?: all.first()
    }

    /** Web-Mercator ground size of one screen pixel (256 px tiles) in metres. */
    fun groundResolutionM(latitude: Double, zoom: Double): Double =
        156_543.03392 * cos(Math.toRadians(latitude.coerceIn(-85.0, 85.0))) / 2.0.pow(zoom)

    fun formatResolution(meters: Double): String = when {
        meters < 1.0 -> "${Math.round(meters * 100)} cm"
        meters < 10.0 -> String.format(java.util.Locale.US, "%.1f m", meters)
        else -> "${Math.round(meters)} m"
    }

    /** One-line description of what the user is looking at, for the map overlay. */
    fun describe(source: SatelliteSource, latitude: Double, zoom: Double): String {
        val known = source.knownResolutionM
        if (known == null) {
            // No claim about centimetres: only the provider knows what it holds for this spot.
            return "${source.name}. Sharpness depends on what this provider has for this place. If it looks blurry here, the provider has no detailed imagery for this area: try another source in Settings."
        }
        val screen = groundResolutionM(latitude, zoom)
        return buildString {
            append(source.name).append(": real detail about ").append(formatResolution(known)).append(" per pixel")
            if (screen < known / 2) append(". You are zoomed in past that, so the picture is enlarged, not sharper")
            append(". Sharper imagery needs a provider key (Settings)")
            append('.')
        }
    }
}

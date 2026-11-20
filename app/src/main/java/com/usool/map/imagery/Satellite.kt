package com.usool.map.imagery

import kotlin.math.cos
import kotlin.math.pow

/**
 * A source of top-down (satellite or aerial) imagery, served as standard XYZ raster tiles.
 * [maxZoom] is the deepest zoom level the provider actually has pictures for; beyond it the map
 * only enlarges the same pixels, and the app says so instead of implying more detail.
 */
data class SatelliteSource(
    val name: String,
    /** XYZ template with {z}, {x}, {y}. */
    val tileUrl: String,
    val maxZoom: Int,
    val attribution: String,
    /** True for commercial very-high-resolution imagery (roughly 30–60 cm per pixel where flown). */
    val highResolution: Boolean,
) {
    /** Finest ground detail this source can show at [latitude], in metres per pixel. */
    fun bestResolutionM(latitude: Double): Double = SatelliteCatalog.groundResolutionM(latitude, maxZoom.toDouble())
}

object SatelliteCatalog {
    /**
     * Free worldwide base layer: Sentinel-2 cloudless 2016 by EOX (CC BY 4.0). Sentinel-2 records
     * about 10 m per pixel, enough to see fields, coastlines and city blocks, not cars or people.
     */
    val SENTINEL2 = SatelliteSource(
        name = "Sentinel-2 cloudless",
        tileUrl = "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless_3857/default/g/{z}/{y}/{x}.jpg",
        maxZoom = 14,
        attribution = "Sentinel-2 cloudless by EOX IT Services GmbH (contains modified Copernicus Sentinel data 2016)",
        highResolution = false,
    )

    /**
     * Picks the imagery source for this build, best first:
     *  1. a custom licensed tile URL (any provider of 30 cm-class imagery),
     *  2. MapTiler Satellite when a MapTiler key is present,
     *  3. the free Sentinel-2 layer.
     * Imagery of 30 cm per pixel is commercial data; it appears only with a provider key, and
     * only where that provider has it.
     */
    fun select(customUrl: String, customName: String, customMaxZoom: Int, customAttribution: String, mapTilerKey: String): SatelliteSource = when {
        customUrl.isNotBlank() -> SatelliteSource(
            name = customName.ifBlank { "Licensed imagery" },
            tileUrl = customUrl,
            maxZoom = customMaxZoom.coerceIn(1, 22),
            attribution = customAttribution,
            highResolution = customMaxZoom >= 18,
        )
        mapTilerKey.isNotBlank() -> SatelliteSource(
            name = "MapTiler Satellite",
            tileUrl = "https://api.maptiler.com/tiles/satellite-v2/{z}/{x}/{y}.jpg?key=$mapTilerKey",
            maxZoom = 20,
            attribution = "© MapTiler © Maxar, Airbus and other imagery providers",
            highResolution = true,
        )
        else -> SENTINEL2
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
        val best = source.bestResolutionM(latitude)
        val enlarged = zoom > source.maxZoom + 0.5
        return buildString {
            append(source.name).append(": finest detail about ").append(formatResolution(best)).append(" per pixel")
            if (enlarged) append(". You are zoomed in past that, so the picture is enlarged, not sharper")
            if (!source.highResolution) append(". Sharper (30 cm class) imagery needs a licensed provider key")
            append('.')
        }
    }
}

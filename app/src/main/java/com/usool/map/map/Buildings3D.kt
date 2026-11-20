package com.usool.map.map

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.usool.map.core.BBox
import com.usool.map.core.Http
import java.net.URLEncoder

/** How the height of a 3D building was obtained. Shown in the map legend. */
enum class HeightQuality(val label: String) {
    /** A surveyed `height` tag exists in OpenStreetMap. */
    MEASURED("Mapped height"),
    /** Height estimated from the mapped number of floors. */
    FROM_LEVELS("Estimated from floor count"),
    /** Height estimated from a low-rise building type (house, garage, shed). */
    FROM_TYPE("Estimated from building type"),
    /** Only the outline is known. Drawn flat: no height is invented. */
    FOOTPRINT_ONLY("Outline only, height unknown"),
}

data class BuildingSummary(val measured: Int, val fromLevels: Int, val fromType: Int, val footprintOnly: Int) {
    val total: Int get() = measured + fromLevels + fromType + footprintOnly
    val estimated: Int get() = fromLevels + fromType
}

class BuildingsResult(val geoJson: String, val summary: BuildingSummary)

/**
 * "Build missing 3D buildings": for the area on screen, reads real building outlines from
 * OpenStreetMap and gives each one a height only when the data supports it. Every feature carries
 * its [HeightQuality] so estimated shapes are drawn in a different colour from mapped ones.
 * Nothing is generated where no outline exists, and outlines with no height clue stay flat.
 */
class Buildings3D(private val http: Http, private val overpassUrl: String = "https://overpass-api.de/api/interpreter") {

    suspend fun build(area: BBox): BuildingsResult {
        require(area.areaKm2 <= MAX_AREA_KM2) { "Zoom in to build 3D buildings (the area on screen is too large)." }
        val q = "[out:json][timeout:40];way[\"building\"](${area.south},${area.west},${area.north},${area.east});out tags geom;"
        val text = http.getString("$overpassUrl?data=" + URLEncoder.encode(q, "UTF-8"))
        return toGeoJson(text)
    }

    companion object {
        const val MAX_AREA_KM2 = 6.0
        const val METERS_PER_LEVEL = 3.2

        // Building types that are single- or two-storey almost everywhere, with a typical height.
        private val TYPE_HEIGHT_M = mapOf(
            "house" to 6.5, "detached" to 6.5, "semidetached_house" to 6.5, "terrace" to 6.5, "bungalow" to 4.0,
            "garage" to 3.0, "garages" to 3.0, "carport" to 2.8, "shed" to 2.8, "hut" to 2.8, "cabin" to 3.0,
            "kiosk" to 3.0, "roof" to 3.5, "static_caravan" to 2.8,
        )

        /** Parses "12", "12.5 m", "40 ft" into metres. */
        fun parseHeightM(value: String?): Double? {
            val v = value?.trim()?.lowercase()?.replace(',', '.') ?: return null
            val number = Regex("""^\d+(\.\d+)?""").find(v)?.value?.toDoubleOrNull() ?: return null
            val meters = if (v.contains("ft") || v.contains("'")) number * 0.3048 else number
            return meters.takeIf { it > 0.5 && it < 1000 }
        }

        fun classify(tags: Map<String, String>): Pair<HeightQuality, Double> {
            parseHeightM(tags["height"] ?: tags["building:height"])?.let { return HeightQuality.MEASURED to it }
            tags["building:levels"]?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 && it < 200 }?.let {
                val roof = tags["roof:levels"]?.toDoubleOrNull() ?: 0.0
                return HeightQuality.FROM_LEVELS to (it + roof) * METERS_PER_LEVEL
            }
            TYPE_HEIGHT_M[tags["building"]]?.let { return HeightQuality.FROM_TYPE to it }
            return HeightQuality.FOOTPRINT_ONLY to 0.0
        }

        fun toGeoJson(overpassJson: String): BuildingsResult {
            val elements = JsonParser.parseString(overpassJson).asJsonObject.getAsJsonArray("elements") ?: JsonArray()
            val features = JsonArray()
            val counts = IntArray(HeightQuality.entries.size)
            for (el in elements) {
                val o = el.asJsonObject
                if (o.get("type")?.asString != "way") continue
                val geom = o.getAsJsonArray("geometry") ?: continue
                if (geom.size() < 4) continue
                val ring = JsonArray()
                for (g in geom) {
                    val p = g.asJsonObject
                    ring.add(JsonArray().apply { add(p.get("lon").asDouble); add(p.get("lat").asDouble) })
                }
                // Only closed ways are building outlines.
                if (ring.first() != ring.last()) continue
                val tags = HashMap<String, String>()
                o.getAsJsonObject("tags")?.entrySet()?.forEach { (k, v) -> if (v.isJsonPrimitive) tags[k] = v.asString }
                val (quality, height) = classify(tags)
                counts[quality.ordinal]++
                val minHeight = parseHeightM(tags["min_height"]) ?: 0.0
                features.add(JsonObject().apply {
                    addProperty("type", "Feature")
                    add("geometry", JsonObject().apply {
                        addProperty("type", "Polygon")
                        add("coordinates", JsonArray().apply { add(ring) })
                    })
                    add("properties", JsonObject().apply {
                        addProperty("quality", quality.name)
                        addProperty("height", height)
                        addProperty("min_height", if (quality == HeightQuality.MEASURED) minHeight.coerceAtMost(height) else 0.0)
                        addProperty("osm_id", o.get("id").asLong)
                    })
                })
            }
            val collection = JsonObject().apply { addProperty("type", "FeatureCollection"); add("features", features) }
            return BuildingsResult(collection.toString(), BuildingSummary(counts[0], counts[1], counts[2], counts[3]))
        }
    }
}

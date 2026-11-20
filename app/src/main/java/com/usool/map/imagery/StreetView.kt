package com.usool.map.imagery

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.usool.map.core.Geo
import com.usool.map.core.GeoPoint
import com.usool.map.core.Http
import java.time.Instant

/** One street-level photograph taken at a known position. */
data class StreetImage(
    val id: String,
    val point: GeoPoint,
    /** Direction the camera faced, degrees from north; null when not recorded. */
    val headingDeg: Double?,
    /** ISO date of capture ("2025-10-10"); null when not recorded. */
    val capturedOn: String?,
    /** True for a full 360° panorama (equirectangular image). */
    val isPanorama: Boolean,
    /** Small version that loads quickly, shown first. */
    val previewUrl: String,
    /** Full-detail version, loaded after the preview. */
    val fullUrl: String,
    val author: String?,
    val licence: String,
    val provider: String,
)

/** A vector-tile layer showing where street-level photos exist. */
data class CoverageTiles(val tileUrl: String, val sequenceLayer: String, val minZoom: Int, val maxZoom: Int)

class StreetViewException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface StreetImageryProvider {
    val name: String
    val configured: Boolean
    val coverage: CoverageTiles?
    /** Photos within [radiusM] of [point]. Empty when nobody has photographed the place. */
    suspend fun near(point: GeoPoint, radiusM: Double): List<StreetImage>
}

private fun bboxAround(p: GeoPoint, radiusM: Double): String {
    val sw = Geo.destination(Geo.destination(p, 180.0, radiusM), 270.0, radiusM)
    val ne = Geo.destination(Geo.destination(p, 0.0, radiusM), 90.0, radiusM)
    return "${sw.lon},${sw.lat},${ne.lon},${ne.lat}"
}

/**
 * Panoramax: an open, federated street-photo commons (no key needed). Coverage is strongest in
 * France and growing elsewhere; many places have no photos yet.
 */
class PanoramaxProvider(private val http: Http, private val baseUrl: String = "https://api.panoramax.xyz") : StreetImageryProvider {
    override val name = "Panoramax"
    override val configured = true
    override val coverage = CoverageTiles("$baseUrl/api/map/{z}/{x}/{y}.mvt", "sequences", 0, 15)

    override suspend fun near(point: GeoPoint, radiusM: Double): List<StreetImage> {
        val text = try {
            http.getString("$baseUrl/api/search?bbox=${bboxAround(point, radiusM)}&limit=60")
        } catch (e: Exception) {
            throw StreetViewException("Street photos could not be loaded from $name (${e.message}).", e)
        }
        return parse(text, name)
    }

    companion object {
        fun parse(json: String, provider: String): List<StreetImage> {
            val features = JsonParser.parseString(json).asJsonObject.getAsJsonArray("features") ?: return emptyList()
            return features.mapNotNull { el ->
                val f = el.asJsonObject
                val coords = f.getAsJsonObject("geometry")?.getAsJsonArray("coordinates") ?: return@mapNotNull null
                val props = f.getAsJsonObject("properties") ?: JsonObject()
                val assets = f.getAsJsonObject("assets") ?: return@mapNotNull null
                fun asset(key: String) = assets.getAsJsonObject(key)?.get("href")?.asString
                val full = asset("hd") ?: asset("sd") ?: return@mapNotNull null
                val fov = props.getAsJsonObject("pers:interior_orientation")?.get("field_of_view")?.takeIf { !it.isJsonNull }?.asDouble
                StreetImage(
                    id = f.get("id").asString,
                    point = GeoPoint(coords[1].asDouble, coords[0].asDouble),
                    headingDeg = props.get("view:azimuth")?.takeIf { !it.isJsonNull }?.asDouble,
                    capturedOn = props.get("datetime")?.takeIf { !it.isJsonNull }?.asString?.take(10),
                    isPanorama = fov != null && fov >= 359.0,
                    previewUrl = asset("sd") ?: asset("thumb") ?: full,
                    fullUrl = full,
                    author = f.getAsJsonArray("providers")?.firstOrNull()?.asJsonObject?.get("name")?.asString,
                    licence = props.get("license")?.takeIf { !it.isJsonNull }?.asString ?: "see provider",
                    provider = provider,
                )
            }
        }
    }
}

/**
 * Mapillary: the largest worldwide street-photo collection (CC BY-SA 4.0). Needs a free client
 * token from mapillary.com/developer, supplied as `MAPILLARY_TOKEN`.
 */
class MapillaryProvider(private val http: Http, private val tokenSource: () -> String) : StreetImageryProvider {
    constructor(http: Http, token: String) : this(http, { token })

    /** Read on every use, so a token typed into Settings takes effect without restarting. */
    private val token: String get() = tokenSource().trim()
    override val name = "Mapillary"
    override val configured get() = token.isNotBlank()
    override val coverage get() = if (configured) CoverageTiles("https://tiles.mapillary.com/maps/vtp/mly1_public/2/{z}/{x}/{y}?access_token=$token", "sequence", 6, 14) else null

    override suspend fun near(point: GeoPoint, radiusM: Double): List<StreetImage> {
        if (!configured) return emptyList()
        val fields = "id,computed_geometry,geometry,compass_angle,captured_at,is_pano,thumb_1024_url,thumb_2048_url,creator"
        val url = "https://graph.mapillary.com/images?fields=$fields&bbox=${bboxAround(point, radiusM)}&limit=60"
        val text = try {
            http.getString(url, mapOf("Authorization" to "OAuth $token"))
        } catch (e: Exception) {
            throw StreetViewException("Street photos could not be loaded from $name (${e.message}).", e)
        }
        return parse(text, name)
    }

    companion object {
        fun parse(json: String, provider: String): List<StreetImage> {
            val data = JsonParser.parseString(json).asJsonObject.getAsJsonArray("data") ?: return emptyList()
            return data.mapNotNull { el ->
                val o = el.asJsonObject
                val coords = (o.getAsJsonObject("computed_geometry") ?: o.getAsJsonObject("geometry"))?.getAsJsonArray("coordinates") ?: return@mapNotNull null
                val preview = o.get("thumb_1024_url")?.asString ?: return@mapNotNull null
                StreetImage(
                    id = o.get("id").asString,
                    point = GeoPoint(coords[1].asDouble, coords[0].asDouble),
                    headingDeg = o.get("compass_angle")?.asDouble,
                    capturedOn = o.get("captured_at")?.asLong?.let { Instant.ofEpochMilli(it).toString().take(10) },
                    isPanorama = o.get("is_pano")?.asBoolean ?: false,
                    previewUrl = preview,
                    fullUrl = o.get("thumb_2048_url")?.asString ?: preview,
                    author = o.getAsJsonObject("creator")?.get("username")?.asString,
                    licence = "CC BY-SA 4.0",
                    provider = provider,
                )
            }
        }
    }
}

/** Street-level photos from every configured provider, nearest first. */
class StreetViewRepository(private val providers: List<StreetImageryProvider>) {
    val activeProviders: List<StreetImageryProvider> get() = providers.filter { it.configured }
    val coverageLayers: List<CoverageTiles> get() = activeProviders.mapNotNull { it.coverage }

    /**
     * Photos around [point], nearest first. Throws only when every provider failed; an empty list
     * means the providers answered and simply have no photos there.
     */
    suspend fun near(point: GeoPoint, radiusM: Double = SEARCH_RADIUS_M): List<StreetImage> {
        val found = ArrayList<StreetImage>()
        var failures = 0
        var lastError: StreetViewException? = null
        for (p in activeProviders) {
            try { found += p.near(point, radiusM) } catch (e: StreetViewException) { failures++; lastError = e }
        }
        if (found.isEmpty() && failures == activeProviders.size && lastError != null) throw lastError
        return sortNearest(found, point, radiusM)
    }

    companion object {
        const val SEARCH_RADIUS_M = 80.0

        fun sortNearest(images: List<StreetImage>, point: GeoPoint, radiusM: Double): List<StreetImage> =
            images.distinctBy { it.provider to it.id }
                .map { it to Geo.distanceM(point, it.point) }
                .filter { it.second <= radiusM * 1.5 }
                .sortedBy { it.second }
                .map { it.first }
    }
}

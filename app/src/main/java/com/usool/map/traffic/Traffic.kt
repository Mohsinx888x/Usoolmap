package com.usool.map.traffic

import com.google.gson.JsonParser
import com.usool.map.core.Availability
import com.usool.map.core.BBox
import com.usool.map.core.GeoPoint
import com.usool.map.core.Http
import com.usool.map.core.RegionProfile
import java.net.URLEncoder

/** The road-information services the app can show. Each is supplied by a pluggable provider. */
enum class RoadService(val label: String) {
    LIVE_TRAFFIC("Live traffic flow"),
    INCIDENTS("Traffic incidents"),
    CLOSURES("Road closures"),
    CONSTRUCTION("Road construction"),
    HAZARDS("Road hazards"),
    SPEED_LIMITS("Speed limits"),
    SPEED_CAMERAS("Speed cameras"),
    ALTERNATIVE_ROUTES("Alternative routes"),
}

enum class IncidentKind { ACCIDENT, CONGESTION, CLOSURE, CONSTRUCTION, HAZARD, OTHER }

data class RoadIncident(val kind: IncidentKind, val point: GeoPoint, val description: String, val delayS: Int?, val source: String)
data class SpeedCamera(val point: GeoPoint, val limitKmh: Int?, val source: String)
data class TrafficFlow(val currentKmh: Double, val freeFlowKmh: Double, val roadClosed: Boolean, val source: String)

class RoadDataException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A source of road information. A provider declares where it is licensed to be used
 * ([countries], empty = worldwide) and which services it supplies; calls for anything else are
 * never made. New regional providers are added by implementing this interface and registering
 * them in [RoadDataRegistry]; nothing else in the app changes.
 */
interface RoadDataProvider {
    val name: String
    val services: Set<RoadService>
    /** ISO 3166-1 alpha-2 codes this provider serves. Empty means every country. */
    val countries: Set<String>
    val requiresInternet: Boolean get() = true
    /** False when the provider cannot work at all, for example because no API key is configured. */
    val configured: Boolean
    val notConfiguredReason: String get() = "$name is not configured."

    suspend fun incidents(area: BBox, language: String): List<RoadIncident> = emptyList()
    suspend fun flow(point: GeoPoint): TrafficFlow? = null
    suspend fun speedCameras(area: BBox): List<SpeedCamera> = emptyList()
    suspend fun speedLimitKmh(point: GeoPoint): Int? = null
}

/** Chooses the provider for a service in a given country and explains gaps honestly. */
class RoadDataRegistry(private val providers: List<RoadDataProvider>, private val online: () -> Boolean) {
    fun providerFor(service: RoadService, countryCode: String): RoadDataProvider? =
        candidates(service, countryCode).firstOrNull { it.configured && (!it.requiresInternet || online()) }

    fun availability(service: RoadService, countryCode: String): Availability {
        if (service == RoadService.SPEED_CAMERAS && !RegionProfile.forCountry(countryCode).speedCameraAlertsAllowed) {
            return Availability.Unavailable("Speed-camera alerts are switched off in $countryCode because local law restricts them.")
        }
        val all = candidates(service, countryCode)
        if (all.isEmpty()) return Availability.Unavailable("No provider covers ${service.label.lowercase()} in $countryCode.")
        val configured = all.filter { it.configured }
        if (configured.isEmpty()) return Availability.Unavailable(all.first().notConfiguredReason)
        if (configured.none { !it.requiresInternet || online() }) return Availability.Unavailable("Needs an internet connection.")
        return Availability.Available
    }

    /** Country-specific providers take priority over worldwide ones. */
    private fun candidates(service: RoadService, countryCode: String): List<RoadDataProvider> {
        val cc = countryCode.uppercase()
        return providers.filter { service in it.services && (it.countries.isEmpty() || cc in it.countries) }
            .sortedBy { if (it.countries.isEmpty()) 1 else 0 }
    }

    fun statusReport(countryCode: String): List<Pair<RoadService, Availability>> =
        RoadService.entries.map { it to availability(it, countryCode) }
}

/**
 * TomTom Traffic (commercial, worldwide where TomTom has coverage). Needs `TOMTOM_API_KEY`; use is
 * subject to the TomTom licence the key was issued under.
 */
class TomTomTrafficProvider(private val http: Http, private val apiKey: String) : RoadDataProvider {
    override val name = "TomTom Traffic"
    override val services = setOf(RoadService.LIVE_TRAFFIC, RoadService.INCIDENTS, RoadService.CLOSURES, RoadService.CONSTRUCTION, RoadService.HAZARDS)
    override val countries = emptySet<String>()
    override val configured get() = apiKey.isNotBlank()
    override val notConfiguredReason = "No licensed live-traffic provider is configured (TomTom API key missing)."

    override suspend fun incidents(area: BBox, language: String): List<RoadIncident> {
        val fields = URLEncoder.encode("{incidents{geometry{type,coordinates},properties{iconCategory,delay,events{description}}}}", "UTF-8")
        val url = "https://api.tomtom.com/traffic/services/5/incidentDetails?key=$apiKey" +
            "&bbox=${area.west},${area.south},${area.east},${area.north}&fields=$fields&language=${tomTomLanguage(language)}&timeValidityFilter=present"
        val text = try { http.getString(url) } catch (e: Exception) { throw RoadDataException("Traffic incidents could not be loaded (${e.message}).", e) }
        return parseIncidents(text, name)
    }

    override suspend fun flow(point: GeoPoint): TrafficFlow? {
        val url = "https://api.tomtom.com/traffic/services/4/flowSegmentData/absolute/12/json?key=$apiKey&point=${point.lat},${point.lon}&unit=KMPH"
        val text = try { http.getString(url) } catch (e: Exception) { return null }
        val d = JsonParser.parseString(text).asJsonObject.getAsJsonObject("flowSegmentData") ?: return null
        return TrafficFlow(d.get("currentSpeed").asDouble, d.get("freeFlowSpeed").asDouble, d.get("roadClosure")?.asBoolean ?: false, name)
    }

    companion object {
        private fun tomTomLanguage(tag: String) = when (tag.substringBefore('-').lowercase()) {
            "ar" -> "ar"; "fr" -> "fr-FR"; "es" -> "es-ES"; "de" -> "de-DE"; "hi" -> "en-GB"; else -> "en-GB"
        }

        fun parseIncidents(json: String, source: String): List<RoadIncident> {
            val arr = JsonParser.parseString(json).asJsonObject.getAsJsonArray("incidents") ?: return emptyList()
            return arr.mapNotNull { el ->
                val o = el.asJsonObject
                val geom = o.getAsJsonObject("geometry") ?: return@mapNotNull null
                val coords = geom.getAsJsonArray("coordinates") ?: return@mapNotNull null
                val first = if (geom.get("type").asString == "Point") coords else coords.firstOrNull()?.asJsonArray ?: return@mapNotNull null
                val props = o.getAsJsonObject("properties")
                val kind = when (props?.get("iconCategory")?.asInt) {
                    1 -> IncidentKind.ACCIDENT
                    6 -> IncidentKind.CONGESTION
                    7, 8 -> IncidentKind.CLOSURE
                    9 -> IncidentKind.CONSTRUCTION
                    2, 3, 4, 5, 10, 11 -> IncidentKind.HAZARD
                    else -> IncidentKind.OTHER
                }
                val text = props?.getAsJsonArray("events")?.firstOrNull()?.asJsonObject?.get("description")?.asString ?: kind.name.lowercase().replaceFirstChar { it.uppercase() }
                val delay = props?.get("delay")?.takeIf { !it.isJsonNull }?.asInt
                RoadIncident(kind, GeoPoint(first[1].asDouble, first[0].asDouble), text, delay, source)
            }
        }
    }
}

/**
 * Speed limits and fixed speed-camera positions as mapped by OpenStreetMap contributors
 * (ODbL). Coverage depends on local mappers: a missing result means "not mapped", never "none".
 */
class OsmRoadDataProvider(private val http: Http, private val overpassUrl: String = "https://overpass-api.de/api/interpreter") : RoadDataProvider {
    override val name = "OpenStreetMap"
    override val services = setOf(RoadService.SPEED_LIMITS, RoadService.SPEED_CAMERAS)
    override val countries = emptySet<String>()
    override val configured = true

    override suspend fun speedCameras(area: BBox): List<SpeedCamera> {
        val q = "[out:json][timeout:25];node[\"highway\"=\"speed_camera\"](${area.south},${area.west},${area.north},${area.east});out body 500;"
        val text = try { http.getString("$overpassUrl?data=" + URLEncoder.encode(q, "UTF-8")) } catch (e: Exception) {
            throw RoadDataException("Speed-camera data could not be loaded (${e.message}).", e)
        }
        return parseCameras(text, name)
    }

    override suspend fun speedLimitKmh(point: GeoPoint): Int? {
        val q = "[out:json][timeout:10];way(around:25,${point.lat},${point.lon})[\"highway\"][\"maxspeed\"];out tags 1;"
        val text = try { http.getString("$overpassUrl?data=" + URLEncoder.encode(q, "UTF-8")) } catch (e: Exception) { return null }
        val el = JsonParser.parseString(text).asJsonObject.getAsJsonArray("elements")?.firstOrNull()?.asJsonObject ?: return null
        return com.usool.map.routing.RoadGraph.parseMaxSpeed(el.getAsJsonObject("tags")?.get("maxspeed")?.asString).takeIf { it > 0 }
    }

    companion object {
        fun parseCameras(json: String, source: String): List<SpeedCamera> {
            val arr = JsonParser.parseString(json).asJsonObject.getAsJsonArray("elements") ?: return emptyList()
            return arr.mapNotNull { el ->
                val o = el.asJsonObject
                if (o.get("type")?.asString != "node") return@mapNotNull null
                val limit = com.usool.map.routing.RoadGraph.parseMaxSpeed(o.getAsJsonObject("tags")?.get("maxspeed")?.asString).takeIf { it > 0 }
                SpeedCamera(GeoPoint(o.get("lat").asDouble, o.get("lon").asDouble), limit, source)
            }
        }
    }
}

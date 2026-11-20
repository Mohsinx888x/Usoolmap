package com.usool.map.routing

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.usool.map.core.Availability
import com.usool.map.core.Geo
import com.usool.map.core.GeoPoint
import com.usool.map.core.Http
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Road routing on OpenStreetMap data through a Valhalla server. The default endpoint is the
 * public FOSSGIS server, which covers the whole planet but is rate limited and meant for
 * light use: a production release should point [baseUrl] at a self-hosted or commercial instance.
 */
class ValhallaRoutingProvider(
    private val http: Http,
    private val online: () -> Boolean,
    private val baseUrl: String = "https://valhalla1.openstreetmap.de",
) : RoutingProvider {
    override val name = "Valhalla (OpenStreetMap)"
    override val requiresInternet = true
    override val modes = setOf(TravelMode.DRIVING, TravelMode.WALKING, TravelMode.CYCLING, TravelMode.MOTORCYCLE, TravelMode.TRUCK)

    override fun availability(mode: TravelMode, from: GeoPoint?): Availability = when {
        mode !in modes -> Availability.Unavailable("Not supported by $name.")
        !online() -> Availability.Unavailable("Needs an internet connection.")
        else -> Availability.Available
    }

    override suspend fun route(from: GeoPoint, to: GeoPoint, mode: TravelMode, language: String): Route {
        val body = JsonObject().apply {
            add("locations", com.google.gson.JsonArray().apply {
                add(JsonObject().apply { addProperty("lat", from.lat); addProperty("lon", from.lon) })
                add(JsonObject().apply { addProperty("lat", to.lat); addProperty("lon", to.lon) })
            })
            addProperty("costing", costing(mode))
            addProperty("units", "kilometers")
            addProperty("language", valhallaLanguage(language))
        }
        val text = try {
            http.postString("$baseUrl/route", body.toString())
        } catch (e: Exception) {
            throw RoutingException("No route could be calculated online (${e.message}).", e)
        }
        return parse(text, mode, name)
    }

    companion object {
        fun costing(mode: TravelMode) = when (mode) {
            TravelMode.WALKING -> "pedestrian"
            TravelMode.CYCLING -> "bicycle"
            TravelMode.MOTORCYCLE -> "motorcycle"
            TravelMode.TRUCK -> "truck"
            else -> "auto"
        }

        // Languages Valhalla ships narrative text for; anything else falls back to English.
        private val LANGUAGES = mapOf(
            "en" to "en-US", "ar" to "ar", "fr" to "fr-FR", "es" to "es-ES", "hi" to "hi-IN", "de" to "de-DE",
            "it" to "it-IT", "pt" to "pt-PT", "ru" to "ru-RU", "tr" to "tr-TR", "ja" to "ja-JP", "zh" to "zh-CN",
            "nl" to "nl-NL", "pl" to "pl-PL", "sv" to "sv-SE", "uk" to "uk-UA", "ko" to "ko-KR", "el" to "el-GR",
        )

        fun valhallaLanguage(tag: String): String = LANGUAGES[tag.substringBefore('-').lowercase()] ?: "en-US"

        fun parse(json: String, mode: TravelMode, source: String): Route {
            val trip = JsonParser.parseString(json).asJsonObject.getAsJsonObject("trip")
                ?: throw RoutingException("The routing server returned no trip.")
            val summary = trip.getAsJsonObject("summary")
            val geometry = ArrayList<GeoPoint>()
            val maneuvers = ArrayList<Maneuver>()
            for (legEl in trip.getAsJsonArray("legs")) {
                val leg = legEl.asJsonObject
                val offset = geometry.size
                geometry += Geo.decodePolyline(leg.get("shape").asString, 6)
                for (mEl in leg.getAsJsonArray("maneuvers")) {
                    val m = mEl.asJsonObject
                    val instruction = m.get("instruction").asString
                    maneuvers += Maneuver(
                        type = maneuverType(m.get("type").asInt),
                        instruction = instruction,
                        spoken = m.get("verbal_pre_transition_instruction")?.asString ?: instruction,
                        street = m.getAsJsonArray("street_names")?.firstOrNull()?.asString,
                        lengthM = m.get("length").asDouble * 1000.0,
                        timeS = m.get("time").asDouble,
                        shapeIndex = offset + m.get("begin_shape_index").asInt,
                        roundaboutExit = m.get("roundabout_exit_count")?.asInt,
                    )
                }
            }
            if (geometry.size < 2) throw RoutingException("The routing server returned an empty route.")
            return Route(
                mode = mode,
                geometry = geometry,
                maneuvers = maneuvers,
                distanceM = summary.get("length").asDouble * 1000.0,
                durationS = summary.get("time").asDouble,
                source = source,
                offline = false,
                hasToll = summary.get("has_toll")?.asBoolean ?: false,
                hasFerry = summary.get("has_ferry")?.asBoolean ?: false,
            )
        }

        /** Maps Valhalla's numeric manoeuvre types onto the app's own set. */
        fun maneuverType(type: Int): ManeuverType = when (type) {
            1, 2, 3 -> ManeuverType.DEPART
            4, 5, 6 -> ManeuverType.ARRIVE
            7, 8, 17, 22, 23, 24 -> ManeuverType.CONTINUE
            9 -> ManeuverType.SLIGHT_RIGHT
            10 -> ManeuverType.RIGHT
            11 -> ManeuverType.SHARP_RIGHT
            12, 13 -> ManeuverType.UTURN
            14 -> ManeuverType.SHARP_LEFT
            15 -> ManeuverType.LEFT
            16 -> ManeuverType.SLIGHT_LEFT
            18, 20 -> ManeuverType.EXIT_RIGHT
            19, 21 -> ManeuverType.EXIT_LEFT
            25, 37, 38 -> ManeuverType.MERGE
            26, 27 -> ManeuverType.ROUNDABOUT
            28, 29 -> ManeuverType.FERRY
            else -> ManeuverType.OTHER
        }
    }
}

/**
 * Public-transport itineraries from a MOTIS server. The default is Transitous, a community-run
 * service built from the timetables that operators publish openly. Coverage is therefore uneven:
 * where no operator feed exists, it returns no itineraries and the app says so instead of
 * inventing a connection.
 */
class TransitRoutingProvider(
    private val http: Http,
    private val online: () -> Boolean,
    private val baseUrl: String = "https://api.transitous.org",
) : RoutingProvider {
    override val name = "Transitous (open timetables)"
    override val requiresInternet = true
    override val modes = setOf(TravelMode.PUBLIC_TRANSPORT, TravelMode.BUS, TravelMode.METRO, TravelMode.RAIL)

    override fun availability(mode: TravelMode, from: GeoPoint?): Availability = when {
        mode !in modes -> Availability.Unavailable("Not supported by $name.")
        !online() -> Availability.Unavailable("Timetables need an internet connection.")
        else -> Availability.Available
    }

    override suspend fun route(from: GeoPoint, to: GeoPoint, mode: TravelMode, language: String): Route {
        val modesParam = transitModes(mode)?.let { "&transitModes=" + URLEncoder.encode(it, "UTF-8") } ?: ""
        val url = "$baseUrl/api/v5/plan?fromPlace=${from.lat},${from.lon}&toPlace=${to.lat},${to.lon}$modesParam"
        val text = try {
            http.getString(url)
        } catch (e: Exception) {
            throw RoutingException("Timetable service could not be reached (${e.message}).", e)
        }
        return parse(text, mode, name)
    }

    companion object {
        fun transitModes(mode: TravelMode): String? = when (mode) {
            TravelMode.BUS -> "BUS,COACH"
            TravelMode.METRO -> "SUBWAY,TRAM"
            TravelMode.RAIL -> "RAIL"
            else -> null
        }

        private val TIME = DateTimeFormatter.ofPattern("HH:mm")

        fun parse(json: String, mode: TravelMode, source: String, zone: ZoneId = ZoneId.systemDefault()): Route {
            val root = JsonParser.parseString(json).asJsonObject
            val itinerary = root.getAsJsonArray("itineraries")?.firstOrNull()?.asJsonObject
                ?: throw RoutingException("No public-transport connection was found. Timetable data may not be published for this area.")
            val geometry = ArrayList<GeoPoint>()
            val maneuvers = ArrayList<Maneuver>()
            var distance = 0.0
            for (legEl in itinerary.getAsJsonArray("legs")) {
                val leg = legEl.asJsonObject
                val g = leg.getAsJsonObject("legGeometry")
                val start = geometry.size
                if (g != null) geometry += Geo.decodePolyline(g.get("points").asString, g.get("precision")?.asInt ?: 6)
                val legPoints = geometry.subList(start, geometry.size)
                val legDistance = leg.get("distance")?.asDouble ?: Geo.pathLengthM(legPoints)
                distance += legDistance
                val duration = leg.get("duration")?.asDouble ?: 0.0
                val legMode = leg.get("mode").asString
                val toName = leg.getAsJsonObject("to")?.get("name")?.asString ?: "your stop"
                val fromName = leg.getAsJsonObject("from")?.get("name")?.asString ?: ""
                val depart = leg.get("startTime")?.asString?.let { runCatching { TIME.format(Instant.parse(it).atZone(zone)) }.getOrNull() }
                if (legMode == "WALK" || legMode == "BIKE" || legMode == "CAR") {
                    val dest = if (toName == "END") "your destination" else toName
                    maneuvers += Maneuver(ManeuverType.CONTINUE, "Walk to $dest", lengthM = legDistance, timeS = duration, shapeIndex = start)
                } else {
                    val line = leg.get("displayName")?.asString ?: leg.get("routeShortName")?.asString ?: legMode.lowercase()
                    val headsign = leg.get("headsign")?.asString
                    val text = buildString {
                        append("Board $line")
                        if (!headsign.isNullOrBlank()) append(" towards $headsign")
                        if (fromName.isNotBlank()) append(" at $fromName")
                        if (depart != null) append(", departs $depart")
                    }
                    maneuvers += Maneuver(ManeuverType.TRANSIT_BOARD, text, street = line, lengthM = legDistance, timeS = duration, shapeIndex = start)
                    maneuvers += Maneuver(ManeuverType.TRANSIT_ALIGHT, "Get off at $toName", lengthM = 0.0, timeS = 0.0, shapeIndex = (geometry.size - 1).coerceAtLeast(0))
                }
            }
            if (geometry.size < 2) throw RoutingException("The timetable service returned an itinerary without a path.")
            maneuvers += Maneuver(ManeuverType.ARRIVE, "You have arrived", lengthM = 0.0, timeS = 0.0, shapeIndex = geometry.size - 1)
            val realTime = itinerary.getAsJsonArray("legs").any { it.asJsonObject.get("realTime")?.asBoolean == true }
            return Route(
                mode = mode,
                geometry = geometry,
                maneuvers = maneuvers,
                distanceM = distance,
                durationS = itinerary.get("duration").asDouble,
                source = source,
                offline = false,
                notes = listOf(if (realTime) "Includes live departure times." else "Scheduled times only; no live data for this trip."),
            )
        }
    }
}

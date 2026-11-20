package com.usool.map.routing

import com.usool.map.core.Availability
import com.usool.map.core.GeoPoint

enum class TravelMode(val label: String, val isTransit: Boolean = false) {
    DRIVING("Drive"),
    WALKING("Walk"),
    CYCLING("Cycle"),
    MOTORCYCLE("Motorcycle"),
    TRUCK("Truck"),
    PUBLIC_TRANSPORT("Transit", true),
    BUS("Bus", true),
    METRO("Metro", true),
    RAIL("Rail", true),
}

enum class ManeuverType {
    DEPART, ARRIVE, CONTINUE, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT,
    UTURN, ROUNDABOUT, MERGE, EXIT_LEFT, EXIT_RIGHT, FERRY, TRANSIT_BOARD, TRANSIT_ALIGHT, OTHER,
}

data class Maneuver(
    val type: ManeuverType,
    /** Text shown on screen. */
    val instruction: String,
    /** Text spoken by the professional voice; falls back to [instruction]. */
    val spoken: String = instruction,
    val street: String? = null,
    val lengthM: Double,
    val timeS: Double,
    /** Index into [Route.geometry] where the manoeuvre happens. */
    val shapeIndex: Int,
    val roundaboutExit: Int? = null,
)

data class Route(
    val mode: TravelMode,
    val geometry: List<GeoPoint>,
    val maneuvers: List<Maneuver>,
    val distanceM: Double,
    val durationS: Double,
    /** Name of the engine that produced the route, shown to the user. */
    val source: String,
    val offline: Boolean,
    val hasToll: Boolean = false,
    val hasFerry: Boolean = false,
    val notes: List<String> = emptyList(),
)

class RoutingException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface RoutingProvider {
    val name: String
    val requiresInternet: Boolean
    val modes: Set<TravelMode>

    /** Whether this provider can route [mode] from [from] right now. */
    fun availability(mode: TravelMode, from: GeoPoint?): Availability

    /** [language] is a BCP-47 tag for the instruction text; providers fall back to English. */
    suspend fun route(from: GeoPoint, to: GeoPoint, mode: TravelMode, language: String): Route
}

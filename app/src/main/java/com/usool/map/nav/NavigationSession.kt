package com.usool.map.nav

import com.usool.map.core.GeoPoint
import com.usool.map.core.RegionProfile
import com.usool.map.gnss.MapMatcher
import com.usool.map.gnss.PositionFix
import com.usool.map.routing.InstructionText
import com.usool.map.routing.Maneuver
import com.usool.map.routing.ManeuverType
import com.usool.map.routing.Route

data class NavigationProgress(
    val matched: GeoPoint,
    val bearingDeg: Double,
    val nextManeuver: Maneuver?,
    val distanceToManeuverM: Double,
    val remainingM: Double,
    val remainingS: Double,
    val offRoute: Boolean,
    val arrived: Boolean,
    val positionEstimated: Boolean,
    /** Index of [nextManeuver] in the route; rises each time a manoeuvre is completed. */
    val maneuverIndex: Int = 0,
    /** Distance from the next manoeuvre to the one after it; small when junctions follow closely. */
    val gapAfterNextM: Double? = null,
)

data class Announcement(val text: String, val priority: com.usool.map.voice.humour.AudioPriority = com.usool.map.voice.humour.AudioPriority.INSTRUCTION)

/**
 * Follows one route: matches each position to the route line, works out the next manoeuvre and
 * decides when to speak. Pure logic with no Android dependency, so it is covered by unit tests.
 */
class NavigationSession(val route: Route, private val region: RegionProfile, language: String) {
    private val matcher = MapMatcher(route.geometry)
    private val text = InstructionText.forLanguage(language)
    private var nextIndex = if (route.maneuvers.size > 1) 1 else 0
    private val announced = HashSet<Pair<Int, Int>>()
    private var offRouteCount = 0
    private var departed = false
    var arrived = false
        private set

    data class Update(val progress: NavigationProgress, val announcements: List<Announcement>, val needsReroute: Boolean)

    fun update(fix: PositionFix): Update {
        val match = matcher.match(fix.point, fix.bearingDeg?.toDouble())
            ?: return Update(NavigationProgress(fix.point, 0.0, null, 0.0, 0.0, 0.0, offRoute = false, arrived = false, positionEstimated = fix.estimated), emptyList(), false)

        val says = ArrayList<Announcement>()
        if (!departed) {
            departed = true
            route.maneuvers.firstOrNull()?.let { says += Announcement(it.spoken) }
        }

        // Off-route only counts for real satellite fixes that are accurate enough to judge.
        val tolerance = OFF_ROUTE_M + (fix.accuracyM ?: 0f)
        val off = !fix.estimated && match.distanceFromRouteM > tolerance
        offRouteCount = if (off) offRouteCount + 1 else 0
        val needsReroute = offRouteCount == OFF_ROUTE_FIXES && !route.mode.isTransit
        if (needsReroute) says += Announcement(text.offRoute, com.usool.map.voice.humour.AudioPriority.INFO)

        // Advance past manoeuvres already behind the matched position.
        while (nextIndex < route.maneuvers.size - 1 && matcher.alongAtIndex(route.maneuvers[nextIndex].shapeIndex) < match.alongM - 5) nextIndex++
        val next = route.maneuvers.getOrNull(nextIndex)
        val distToNext = next?.let { (matcher.alongAtIndex(it.shapeIndex) - match.alongM).coerceAtLeast(0.0) } ?: 0.0
        val remainingM = (matcher.totalLengthM - match.alongM).coerceAtLeast(0.0)

        if (next != null && !off) {
            val speed = (fix.speedMps ?: 0f).toDouble()
            if (next.type == ManeuverType.ARRIVE) {
                if (distToNext < ARRIVE_M && !arrived) {
                    arrived = true
                    says += Announcement(next.spoken)
                }
            } else {
                // Far warning ~20 s ahead (at least 250 m), near warning ~5 s ahead (at least 35 m).
                val far = (speed * 20).coerceIn(250.0, 1500.0)
                val near = (speed * 5).coerceIn(35.0, 120.0)
                if (distToNext <= near) {
                    if (announced.add(nextIndex to STAGE_NEAR)) { announced += nextIndex to STAGE_FAR; says += Announcement(text.now(next.spoken)) }
                } else if (distToNext <= far && distToNext > near * 2) {
                    if (announced.add(nextIndex to STAGE_FAR)) says += Announcement(text.inDistance(region.formatDistance(distToNext), next.spoken))
                }
            }
        }

        val fraction = if (matcher.totalLengthM > 0) remainingM / matcher.totalLengthM else 0.0
        val progress = NavigationProgress(
            matched = if (off) fix.point else match.point,
            bearingDeg = fix.bearingDeg?.toDouble() ?: match.bearingDeg,
            nextManeuver = next,
            distanceToManeuverM = distToNext,
            remainingM = remainingM,
            remainingS = route.durationS * fraction,
            offRoute = off,
            arrived = arrived,
            positionEstimated = fix.estimated,
            maneuverIndex = nextIndex,
            gapAfterNextM = route.maneuvers.getOrNull(nextIndex + 1)?.let { after -> next?.let { matcher.alongAtIndex(after.shapeIndex) - matcher.alongAtIndex(it.shapeIndex) } },
        )
        return Update(progress, says, needsReroute)
    }

    companion object {
        private const val OFF_ROUTE_M = 45.0
        private const val OFF_ROUTE_FIXES = 4
        private const val ARRIVE_M = 30.0
        private const val STAGE_FAR = 0
        private const val STAGE_NEAR = 1
    }
}

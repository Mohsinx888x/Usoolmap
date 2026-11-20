package com.usool.map.gnss

import com.usool.map.core.Geo
import com.usool.map.core.GeoPoint

/**
 * Decides when the satellite fix should be treated as lost. Pure logic so it can be unit tested.
 * A fix is "stale" once no update has arrived for [staleAfterMs]; dead reckoning then bridges the
 * gap for at most [maxBridgeMs] before the position is declared lost.
 */
class SignalLossDetector(
    private val staleAfterMs: Long = 4_000,
    private val maxBridgeMs: Long = 45_000,
) {
    fun evaluate(hasEverFixed: Boolean, msSinceLastFix: Long?, canDeadReckon: Boolean, providerEnabled: Boolean): FixState =
        when {
            !providerEnabled -> FixState.DISABLED
            !hasEverFixed || msSinceLastFix == null -> FixState.SEARCHING
            msSinceLastFix <= staleAfterMs -> FixState.FIXED
            canDeadReckon && msSinceLastFix <= maxBridgeMs -> FixState.DEAD_RECKONING
            else -> FixState.LOST
        }
}

/**
 * Carries the last good position forward during short outages (tunnels, urban canyons) using the
 * last known speed and the heading from the device's rotation sensor when one is available.
 * The result is always flagged `estimated`, with accuracy that degrades as time passes.
 */
class DeadReckoning(private val minSpeedMps: Float = 1.5f) {
    /** Heading from inertial sensors in degrees, or null when the device has none. */
    @Volatile var sensorHeadingDeg: Float? = null

    /** Change in sensor heading since the last GNSS fix is applied to the GNSS course. */
    private var sensorHeadingAtFix: Float? = null
    private var last: PositionFix? = null

    fun onFix(fix: PositionFix) {
        last = fix
        sensorHeadingAtFix = sensorHeadingDeg
    }

    fun canEstimate(): Boolean {
        val f = last ?: return false
        return (f.speedMps ?: 0f) >= minSpeedMps && f.bearingDeg != null
    }

    fun estimate(nowElapsedMs: Long): PositionFix? {
        val f = last ?: return null
        val speed = f.speedMps ?: return null
        val course = f.bearingDeg ?: return null
        if (speed < minSpeedMps) return null
        val dt = (nowElapsedMs - f.elapsedMs).coerceAtLeast(0) / 1000.0
        val turned = run {
            val now = sensorHeadingDeg
            val then = sensorHeadingAtFix
            if (now != null && then != null) Geo.angleDiffDeg(then.toDouble(), now.toDouble()) else 0.0
        }
        val heading = (course + turned + 360.0) % 360.0
        val point = Geo.destination(f.point, heading, speed * dt)
        // Error grows with time: assume up to ~15% speed error plus the original accuracy.
        val accuracy = (f.accuracyM ?: 10f) + (speed * dt * 0.15).toFloat()
        return f.copy(point = point, bearingDeg = heading.toFloat(), accuracyM = accuracy, elapsedMs = nowElapsedMs, estimated = true)
    }
}

/** Snaps a position to the active route line so the marker stays on the road. */
class MapMatcher(private val route: List<GeoPoint>) {
    data class Match(
        val point: GeoPoint,
        val segmentIndex: Int,
        val distanceFromRouteM: Double,
        /** Distance travelled along the route to the matched point. */
        val alongM: Double,
        val bearingDeg: Double,
    )

    private val cumulative = DoubleArray(route.size).also { c ->
        for (i in 1 until route.size) c[i] = c[i - 1] + Geo.distanceM(route[i - 1], route[i])
    }
    val totalLengthM: Double get() = if (cumulative.isEmpty()) 0.0 else cumulative.last()

    private var lastSegment = 0

    /**
     * Matches [p], preferring segments at or just after the previous match so the marker cannot
     * jump backwards or onto a later part of a route that loops over itself. A full search runs
     * when nothing nearby fits (for example after a detour).
     */
    fun match(p: GeoPoint, courseDeg: Double? = null): Match? {
        if (route.size < 2) return null
        val windowEnd = (lastSegment + WINDOW).coerceAtMost(route.size - 2)
        var best = bestIn(p, courseDeg, (lastSegment - 2).coerceAtLeast(0), windowEnd)
        if (best == null || best.distanceFromRouteM > REACQUIRE_M) {
            best = bestIn(p, courseDeg, 0, route.size - 2) ?: best
        }
        if (best != null) lastSegment = best.segmentIndex
        return best
    }

    private fun bestIn(p: GeoPoint, courseDeg: Double?, from: Int, to: Int): Match? {
        var best: Match? = null
        var bestScore = Double.MAX_VALUE
        for (i in from..to) {
            val a = route[i]
            val b = route[i + 1]
            val proj = Geo.projectOnSegment(p, a, b)
            val segBearing = Geo.bearingDeg(a, b)
            var score = proj.distanceM
            // Penalise segments pointing against the direction of travel.
            if (courseDeg != null && kotlin.math.abs(Geo.angleDiffDeg(segBearing, courseDeg)) > 100) score += 40.0
            if (score < bestScore) {
                bestScore = score
                val along = cumulative[i] + Geo.distanceM(a, proj.point)
                best = Match(proj.point, i, proj.distanceM, along, segBearing)
            }
        }
        return best
    }

    fun alongAtIndex(index: Int): Double = cumulative[index.coerceIn(0, cumulative.size - 1)]

    companion object {
        private const val WINDOW = 40
        private const val REACQUIRE_M = 60.0
    }
}

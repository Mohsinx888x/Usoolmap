package com.usool.map.routing

import com.usool.map.core.Availability
import com.usool.map.core.Geo
import com.usool.map.core.GeoPoint
import java.util.PriorityQueue
import kotlin.math.abs

/** Speed and access rules for one way of travelling over a [RoadGraph]. */
class RoutingProfile(val mode: TravelMode, val accessFlag: Int, val topSpeedKmh: Double, private val classSpeedKmh: DoubleArray) {
    /** Travel speed on [edge] in km/h, or 0 when the edge may not be used. */
    fun speedKmh(g: RoadGraph, edge: Int): Double {
        if (!g.allows(edge, accessFlag)) return 0.0
        val base = classSpeedKmh[g.edgeClass[edge].toInt()]
        if (base <= 0.0) return 0.0
        if (accessFlag != RoadGraph.CAR) return base
        val posted = g.edgeMaxSpeedKmh[edge].toInt()
        // Posted limits are a ceiling; real traffic averages somewhat below them.
        return if (posted > 0) minOf(posted * 0.9, topSpeedKmh) else base
    }

    companion object {
        //                         mwy   trunk prim  sec   tert  minor resid living serv track foot  cycle steps
        private val CAR = doubleArrayOf(105.0, 85.0, 65.0, 55.0, 45.0, 40.0, 30.0, 10.0, 15.0, 0.0, 0.0, 0.0, 0.0)
        private val FOOT = doubleArrayOf(0.0, 0.0, 4.8, 4.8, 4.8, 4.8, 4.8, 4.8, 4.8, 4.5, 5.0, 4.5, 2.5)
        private val BIKE = doubleArrayOf(0.0, 0.0, 16.0, 17.0, 17.0, 17.0, 16.0, 10.0, 12.0, 11.0, 0.0, 18.0, 0.0)

        fun forMode(mode: TravelMode): RoutingProfile? = when (mode) {
            TravelMode.DRIVING, TravelMode.MOTORCYCLE -> RoutingProfile(mode, RoadGraph.CAR, 130.0, CAR)
            TravelMode.WALKING -> RoutingProfile(mode, RoadGraph.FOOT, 5.0, FOOT)
            TravelMode.CYCLING -> RoutingProfile(mode, RoadGraph.BIKE, 18.0, BIKE)
            else -> null
        }
    }
}

/** Fastest-path search (A*) over a downloaded [RoadGraph]. Runs entirely on the device. */
class OfflineRouter(private val graph: RoadGraph) {
    class Path(val nodes: IntArray, val edges: IntArray, val durationS: Double, val distanceM: Double)

    fun findPath(from: GeoPoint, to: GeoPoint, profile: RoutingProfile): Path {
        val start = graph.nearestNode(from, profile.accessFlag)
            ?: throw RoutingException("Your start point is not near a downloaded road for ${profile.mode.label.lowercase()} routing.")
        val goal = graph.nearestNode(to, profile.accessFlag)
            ?: throw RoutingException("The destination is not near a downloaded road for ${profile.mode.label.lowercase()} routing.")
        return findPath(start, goal, profile) ?: throw RoutingException("No offline route connects these two points in the downloaded area.")
    }

    fun findPath(start: Int, goal: Int, profile: RoutingProfile): Path? {
        val n = graph.nodeCount
        val cost = FloatArray(n) { Float.POSITIVE_INFINITY }
        val viaEdge = IntArray(n) { -1 }
        val viaNode = IntArray(n) { -1 }
        val closed = BooleanArray(n)
        val goalPoint = graph.point(goal)
        val topMps = profile.topSpeedKmh / 3.6
        // Queue entries pack the priority (float bits, non-negative so they sort as ints) with the node.
        val open = PriorityQueue<Long>()
        fun push(node: Int, g: Float) {
            val f = g + (Geo.distanceM(graph.point(node), goalPoint) / topMps).toFloat()
            open.add((f.toRawBits().toLong() shl 32) or node.toLong())
        }
        cost[start] = 0f
        push(start, 0f)
        while (open.isNotEmpty()) {
            val node = (open.poll()!! and 0xffffffffL).toInt()
            if (closed[node]) continue
            closed[node] = true
            if (node == goal) break
            for (e in graph.edgeStart[node] until graph.edgeStart[node + 1]) {
                val speed = profile.speedKmh(graph, e)
                if (speed <= 0.0) continue
                val next = graph.edgeTo[e]
                if (closed[next]) continue
                val g = cost[node] + (graph.edgeLengthM[e] / (speed / 3.6)).toFloat()
                if (g < cost[next]) {
                    cost[next] = g
                    viaEdge[next] = e
                    viaNode[next] = node
                    push(next, g)
                }
            }
        }
        if (start != goal && viaEdge[goal] < 0) return null
        val nodes = ArrayList<Int>()
        val edges = ArrayList<Int>()
        var cur = goal
        while (cur != start) {
            nodes += cur
            edges += viaEdge[cur]
            cur = viaNode[cur]
        }
        nodes += start
        nodes.reverse(); edges.reverse()
        return Path(nodes.toIntArray(), edges.toIntArray(), cost[goal].toDouble(), edges.sumOf { graph.edgeLengthM[it].toDouble() })
    }

    /** Turns a node path into a [Route] with turn-by-turn instructions in [language]. */
    fun toRoute(path: Path, profile: RoutingProfile, language: String, from: GeoPoint, to: GeoPoint): Route {
        val text = InstructionText.forLanguage(language)
        val g = graph
        val geometry = ArrayList<GeoPoint>(path.nodes.size + 2)
        geometry += from
        path.nodes.forEach { geometry += g.point(it) }
        geometry += to
        // Geometry index of path node i is i + 1 because of the leading start point.
        val maneuvers = ArrayList<Maneuver>()
        var segStartIdx = 0
        var segLen = 0.0
        var segTime = 0.0
        var pending: Triple<ManeuverType, String?, Int?> = Triple(ManeuverType.DEPART, path.edges.firstOrNull()?.let { g.name(it) }, null)

        fun flush(nextShapeIndex: Int) {
            val (type, street, exit) = pending
            maneuvers += Maneuver(type, text.maneuver(type, street, exit), street = street, lengthM = segLen, timeS = segTime, shapeIndex = segStartIdx, roundaboutExit = exit)
            segStartIdx = nextShapeIndex
            segLen = 0.0
            segTime = 0.0
        }

        var i = 0
        while (i < path.edges.size) {
            val e = path.edges[i]
            if (i > 0) {
                val prev = path.edges[i - 1]
                val node = path.nodes[i]
                val enteringRoundabout = g.allows(e, RoadGraph.ROUNDABOUT) && !g.allows(prev, RoadGraph.ROUNDABOUT)
                if (enteringRoundabout) {
                    // Count the exits passed until the path leaves the roundabout.
                    var exits = 0
                    var j = i
                    while (j < path.edges.size && g.allows(path.edges[j], RoadGraph.ROUNDABOUT)) {
                        if (hasExit(path.nodes[j + 1], profile)) exits++
                        j++
                    }
                    flush(i + 1)
                    val exitStreet = path.edges.getOrNull(j)?.let { g.name(it) }
                    pending = Triple(ManeuverType.ROUNDABOUT, exitStreet, exits.coerceAtLeast(1))
                } else if (!g.allows(e, RoadGraph.ROUNDABOUT) && !g.allows(prev, RoadGraph.ROUNDABOUT)) {
                    val before = Geo.bearingDeg(g.point(path.nodes[i - 1]), g.point(node))
                    val after = Geo.bearingDeg(g.point(node), g.point(path.nodes[i + 1]))
                    val turn = Geo.angleDiffDeg(before, after)
                    val nameChanged = g.name(e) != g.name(prev)
                    val junction = usableExits(node, profile) > 2
                    val type = turnType(turn)
                    if ((junction && abs(turn) >= 35) || (nameChanged && abs(turn) >= 20) || abs(turn) >= 150) {
                        flush(i + 1)
                        pending = Triple(type, g.name(e), null)
                    }
                }
            }
            segLen += g.edgeLengthM[e]
            segTime += g.edgeLengthM[e] / (profile.speedKmh(g, e).coerceAtLeast(1.0) / 3.6)
            i++
        }
        flush(geometry.size - 1)
        maneuvers += Maneuver(ManeuverType.ARRIVE, text.maneuver(ManeuverType.ARRIVE, null, null), lengthM = 0.0, timeS = 0.0, shapeIndex = geometry.size - 1)

        val notes = ArrayList<String>()
        notes += "Calculated on this device from downloaded road data. Live traffic and closures are not considered."
        if (path.edges.any { g.allows(it, RoadGraph.TOLL) }) notes += "This route uses a toll road."
        return Route(
            mode = profile.mode, geometry = geometry, maneuvers = maneuvers,
            distanceM = Geo.pathLengthM(geometry), durationS = path.durationS,
            source = "UsoolMap offline router", offline = true,
            hasToll = path.edges.any { g.allows(it, RoadGraph.TOLL) }, notes = notes,
        )
    }

    private fun usableExits(node: Int, profile: RoutingProfile): Int {
        var count = 0
        for (e in graph.edgeStart[node] until graph.edgeStart[node + 1]) if (graph.allows(e, profile.accessFlag)) count++
        return count
    }

    /** True when [node] on a roundabout has a usable edge leaving the roundabout. */
    private fun hasExit(node: Int, profile: RoutingProfile): Boolean {
        for (e in graph.edgeStart[node] until graph.edgeStart[node + 1]) {
            if (graph.allows(e, profile.accessFlag) && !graph.allows(e, RoadGraph.ROUNDABOUT)) return true
        }
        return false
    }

    companion object {
        fun turnType(turnDeg: Double): ManeuverType = when {
            abs(turnDeg) >= 150 -> ManeuverType.UTURN
            turnDeg >= 110 -> ManeuverType.SHARP_RIGHT
            turnDeg >= 45 -> ManeuverType.RIGHT
            turnDeg >= 20 -> ManeuverType.SLIGHT_RIGHT
            turnDeg <= -110 -> ManeuverType.SHARP_LEFT
            turnDeg <= -45 -> ManeuverType.LEFT
            turnDeg <= -20 -> ManeuverType.SLIGHT_LEFT
            else -> ManeuverType.CONTINUE
        }
    }
}

/**
 * Routes over whichever downloaded road graphs cover both ends of the trip. Truck and transit
 * routing are not offered offline: the downloaded data carries no vehicle dimensions or timetables.
 */
class OfflineRoutingProvider(private val graphs: () -> List<RoadGraph>) : RoutingProvider {
    override val name = "UsoolMap offline router"
    override val requiresInternet = false
    override val modes = setOf(TravelMode.DRIVING, TravelMode.MOTORCYCLE, TravelMode.WALKING, TravelMode.CYCLING)

    override fun availability(mode: TravelMode, from: GeoPoint?): Availability = when {
        mode !in modes -> Availability.Unavailable("${mode.label} routing is not available offline.")
        graphs().isEmpty() -> Availability.Unavailable("No offline region with road data has been downloaded.")
        from != null && graphs().none { it.bbox.contains(from) } -> Availability.Unavailable("Your position is outside the downloaded offline regions.")
        else -> Availability.Available
    }

    override suspend fun route(from: GeoPoint, to: GeoPoint, mode: TravelMode, language: String): Route {
        val profile = RoutingProfile.forMode(mode) ?: throw RoutingException("${mode.label} routing is not available offline.")
        val graph = graphs().firstOrNull { it.bbox.contains(from) && it.bbox.contains(to) }
            ?: throw RoutingException("Both ends of the trip must be inside one downloaded offline region.")
        val router = OfflineRouter(graph)
        return router.toRoute(router.findPath(from, to, profile), profile, language, from, to)
    }
}

/** Picks the best provider for a request: online first, then the offline router. */
class RoutingRepository(private val providers: List<RoutingProvider>) {
    fun availability(mode: TravelMode, from: GeoPoint?): Availability {
        val reasons = ArrayList<String>()
        for (p in providers.filter { mode in it.modes }) {
            when (val a = p.availability(mode, from)) {
                Availability.Available -> return a
                is Availability.Unavailable -> reasons += a.reason
            }
        }
        return Availability.Unavailable(reasons.distinct().joinToString(" ").ifEmpty { "No routing data is available for ${mode.label.lowercase()}." })
    }

    /** Only the modes that can actually be routed now are offered in the UI. */
    fun routableModes(from: GeoPoint?): List<TravelMode> = TravelMode.entries.filter { availability(it, from).isAvailable }

    /** Route options for the user to choose from: the first provider that answers decides. */
    suspend fun routes(from: GeoPoint, to: GeoPoint, mode: TravelMode, language: String): List<Route> {
        var lastError: Exception? = null
        for (p in providers.filter { mode in it.modes && it.availability(mode, from).isAvailable }) {
            try {
                return p.routes(from, to, mode, language)
            } catch (e: RoutingException) {
                lastError = e
            }
        }
        throw lastError ?: RoutingException((availability(mode, from) as? Availability.Unavailable)?.reason ?: "No route found.")
    }

    suspend fun route(from: GeoPoint, to: GeoPoint, mode: TravelMode, language: String): Route {
        var lastError: Exception? = null
        for (p in providers.filter { mode in it.modes && it.availability(mode, from).isAvailable }) {
            try {
                return p.route(from, to, mode, language)
            } catch (e: RoutingException) {
                lastError = e
            }
        }
        throw lastError ?: RoutingException((availability(mode, from) as? Availability.Unavailable)?.reason ?: "No route found.")
    }
}

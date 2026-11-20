package com.usool.map

import com.usool.map.core.Availability
import com.usool.map.core.BBox
import com.usool.map.core.Geo
import com.usool.map.core.GeoPoint
import com.usool.map.core.RegionProfile
import com.usool.map.gnss.PositionFix
import com.usool.map.nav.NavigationSession
import com.usool.map.routing.InstructionText
import com.usool.map.routing.Maneuver
import com.usool.map.routing.ManeuverType
import com.usool.map.routing.OfflineRouter
import com.usool.map.routing.OfflineRoutingProvider
import com.usool.map.routing.RoadGraph
import com.usool.map.routing.Route
import com.usool.map.routing.RoutingException
import com.usool.map.routing.RoutingProfile
import com.usool.map.routing.RoutingProvider
import com.usool.map.routing.RoutingRepository
import com.usool.map.routing.TransitRoutingProvider
import com.usool.map.routing.TravelMode
import com.usool.map.routing.ValhallaRoutingProvider
import com.usool.map.search.NominatimProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZoneId

private fun resource(name: String): String =
    checkNotNull(Thread.currentThread().contextClassLoader!!.getResourceAsStream(name)) { "missing $name" }.readBytes().toString(Charsets.UTF_8)

/**
 * A small made-up town:
 *
 *   N8 . . . . N5 ── D St ── N7
 *              │             ↑  (C St is one-way, northbound)
 *              N4            N6
 *              │ B St        ↑
 *   N1 ─ A St ─ N2 ─ A St ─ N3
 *    ╲__ footpath straight to N5
 */
private val TOWN_JSON = """
{"elements":[
 {"type":"way","id":1,"nodes":[1,2,3],"tags":{"highway":"residential","name":"A St"}},
 {"type":"way","id":2,"nodes":[2,4,5],"tags":{"highway":"residential","name":"B St","maxspeed":"30"}},
 {"type":"way","id":3,"nodes":[3,6,7],"tags":{"highway":"primary","name":"C St","oneway":"yes"}},
 {"type":"way","id":4,"nodes":[5,7],"tags":{"highway":"residential","name":"D St"}},
 {"type":"way","id":5,"nodes":[1,5],"tags":{"highway":"footway"}},
 {"type":"way","id":6,"nodes":[1,2],"tags":{"highway":"proposed"}},
 {"type":"way","id":7,"nodes":[7,99],"tags":{"highway":"residential","access":"private","foot":"no","bicycle":"no"}},
 {"type":"node","id":1,"lat":25.000,"lon":51.000},{"type":"node","id":2,"lat":25.000,"lon":51.001},
 {"type":"node","id":3,"lat":25.000,"lon":51.002},{"type":"node","id":4,"lat":25.001,"lon":51.001},
 {"type":"node","id":5,"lat":25.002,"lon":51.001},{"type":"node","id":6,"lat":25.001,"lon":51.002},
 {"type":"node","id":7,"lat":25.002,"lon":51.002},{"type":"node","id":99,"lat":25.003,"lon":51.002}
]}"""

private val N1 = GeoPoint(25.000, 51.000)
private val N5 = GeoPoint(25.002, 51.001)
private val N6 = GeoPoint(25.001, 51.002)
private val N7 = GeoPoint(25.002, 51.002)
private val TOWN_BOX = BBox(24.99, 50.99, 25.01, 51.01)

class OfflineRoutingTest {
    private val graph = RoadGraph.fromOverpass(ByteArrayInputStream(TOWN_JSON.toByteArray()), TOWN_BOX)
    private val router = OfflineRouter(graph)
    private val car = RoutingProfile.forMode(TravelMode.DRIVING)!!
    private val foot = RoutingProfile.forMode(TravelMode.WALKING)!!

    private fun Route.passes(p: GeoPoint) = geometry.any { Geo.distanceM(it, p) < 1.0 }

    @Test fun buildsGraphFromOverpass() {
        assertEquals(7, graph.nodeCount) // node 99 is only on a private way, which is dropped
        assertTrue(graph.edgeCount > 0)
        assertEquals(30, RoadGraph.parseMaxSpeed("30"))
        assertEquals(48, RoadGraph.parseMaxSpeed("30 mph"))
        assertEquals(0, RoadGraph.parseMaxSpeed("signals"))
    }

    @Test fun carTakesTheFasterMainRoadAndTurnsLeft() {
        val route = router.toRoute(router.findPath(N1, N7, car), car, "en", N1, N7)
        assertTrue(route.passes(N6))
        assertFalse(route.passes(N5))
        assertTrue(route.offline)
        assertEquals(ManeuverType.DEPART, route.maneuvers.first().type)
        assertEquals(ManeuverType.ARRIVE, route.maneuvers.last().type)
        val turn = route.maneuvers.single { it.type == ManeuverType.LEFT }
        assertEquals("C St", turn.street)
        assertEquals("Turn left onto C St.", turn.instruction)
        assertEquals(422.0, route.distanceM, 8.0)
        assertTrue(route.durationS > 15 && route.durationS < 120)
    }

    @Test fun carRespectsOneWayStreets() {
        val route = router.toRoute(router.findPath(N7, N1, car), car, "en", N7, N1)
        assertFalse("must not drive C St against its direction", route.passes(N6))
        assertTrue(route.passes(N5))
    }

    @Test fun walkerUsesFootpathThatCarsCannot() {
        val walk = router.toRoute(router.findPath(N1, N5, foot), foot, "en", N1, N5)
        assertEquals(243.0, walk.distanceM, 6.0)
        val drive = router.toRoute(router.findPath(N1, N5, car), car, "en", N1, N5)
        assertEquals(322.0, drive.distanceM, 6.0)
        // Pedestrians may walk a one-way street in either direction.
        assertTrue(router.toRoute(router.findPath(N7, GeoPoint(25.000, 51.002), foot), foot, "en", N7, GeoPoint(25.000, 51.002)).passes(N6))
    }

    @Test fun survivesSaveAndLoad() {
        val bytes = ByteArrayOutputStream().also { graph.write(it) }.toByteArray()
        val loaded = RoadGraph.read(ByteArrayInputStream(bytes))
        assertEquals(graph.nodeCount, loaded.nodeCount)
        assertEquals(graph.edgeCount, loaded.edgeCount)
        assertEquals(TOWN_BOX, loaded.bbox)
        val path = OfflineRouter(loaded).findPath(N1, N7, car)
        assertEquals(router.findPath(N1, N7, car).distanceM, path.distanceM, 1e-6)
    }

    @Test fun refusesPointsFarFromAnyDownloadedRoad() {
        try {
            router.findPath(GeoPoint(26.0, 52.0), N7, car)
            fail("expected RoutingException")
        } catch (e: RoutingException) {
            assertTrue(e.message!!.contains("not near a downloaded road"))
        }
    }

    @Test fun providerOnlyOffersWhatItCanRoute() = runTest {
        val provider = OfflineRoutingProvider { listOf(graph) }
        assertTrue(provider.availability(TravelMode.DRIVING, N1).isAvailable)
        assertFalse(provider.availability(TravelMode.TRUCK, N1).isAvailable)
        assertFalse(provider.availability(TravelMode.BUS, N1).isAvailable)
        assertFalse(provider.availability(TravelMode.DRIVING, GeoPoint(48.85, 2.35)).isAvailable)
        assertFalse(OfflineRoutingProvider { emptyList() }.availability(TravelMode.DRIVING, N1).isAvailable)
        assertEquals(TravelMode.CYCLING, provider.route(N1, N7, TravelMode.CYCLING, "fr").mode)
        assertTrue(provider.route(N1, N7, TravelMode.DRIVING, "fr").maneuvers.any { it.instruction == "Tournez à gauche sur C St." })
    }

    @Test fun repositoryFallsBackToOfflineAndListsRoutableModes() = runTest {
        val failingOnline = object : RoutingProvider {
            override val name = "online"; override val requiresInternet = true
            override val modes = setOf(TravelMode.DRIVING, TravelMode.TRUCK)
            override fun availability(mode: TravelMode, from: GeoPoint?) = Availability.Available
            override suspend fun route(from: GeoPoint, to: GeoPoint, mode: TravelMode, language: String): Route = throw RoutingException("server down")
        }
        val repo = RoutingRepository(listOf(failingOnline, OfflineRoutingProvider { listOf(graph) }))
        assertTrue(repo.route(N1, N7, TravelMode.DRIVING, "en").offline)
        val modes = repo.routableModes(N1)
        assertTrue(TravelMode.WALKING in modes && TravelMode.TRUCK in modes)
        assertFalse(TravelMode.METRO in modes)
        assertFalse(TravelMode.PUBLIC_TRANSPORT in modes)
    }
}

class OnlineParsingTest {
    @Test fun parsesRealValhallaRoute() {
        val route = ValhallaRoutingProvider.parse(resource("valhalla_route.json"), TravelMode.DRIVING, "Valhalla")
        assertEquals(7158.0, route.distanceM, 1.0)
        assertEquals(809.7, route.durationS, 0.1)
        assertEquals(11, route.maneuvers.size)
        assertEquals(ManeuverType.DEPART, route.maneuvers.first().type)
        assertEquals(ManeuverType.ARRIVE, route.maneuvers.last().type)
        assertEquals(ManeuverType.RIGHT, route.maneuvers[1].type)
        assertTrue(route.geometry.size > 50)
        // Decoded geometry starts in Doha, and its length agrees with the reported distance.
        assertEquals(25.285, route.geometry.first().lat, 0.01)
        assertEquals(route.distanceM, Geo.pathLengthM(route.geometry), route.distanceM * 0.02)
        assertTrue(route.maneuvers.all { it.shapeIndex in route.geometry.indices })
        assertFalse(route.offline)
    }

    @Test fun mapsLanguagesAndCostings() {
        assertEquals("ar", ValhallaRoutingProvider.valhallaLanguage("ar-QA"))
        assertEquals("en-US", ValhallaRoutingProvider.valhallaLanguage("ur-PK"))
        assertEquals("truck", ValhallaRoutingProvider.costing(TravelMode.TRUCK))
        assertEquals("pedestrian", ValhallaRoutingProvider.costing(TravelMode.WALKING))
    }

    @Test fun parsesRealTransitItinerary() {
        val route = TransitRoutingProvider.parse(resource("transit_plan.json"), TravelMode.PUBLIC_TRANSPORT, "Transitous", ZoneId.of("Europe/Berlin"))
        assertEquals(1860.0, route.durationS, 0.1)
        val board = route.maneuvers.single { it.type == ManeuverType.TRANSIT_BOARD }
        assertTrue(board.instruction, board.instruction.startsWith("Board S"))
        assertTrue(board.instruction.contains("departs"))
        assertEquals(1, route.maneuvers.count { it.type == ManeuverType.TRANSIT_ALIGHT })
        assertEquals(ManeuverType.ARRIVE, route.maneuvers.last().type)
        assertEquals(52.52, route.geometry.first().lat, 0.01)
        assertTrue(route.maneuvers.all { it.shapeIndex in route.geometry.indices })
    }

    @Test fun saysSoWhenNoTimetableExists() {
        try {
            TransitRoutingProvider.parse("""{"itineraries":[]}""", TravelMode.BUS, "Transitous")
            fail("expected RoutingException")
        } catch (e: RoutingException) {
            assertTrue(e.message!!.contains("No public-transport connection"))
        }
        assertEquals("BUS,COACH", TransitRoutingProvider.transitModes(TravelMode.BUS))
    }

    @Test fun parsesRealNominatimResults() {
        val places = NominatimProvider.parseSearch(resource("nominatim_search.json"))
        assertTrue(places.isNotEmpty())
        assertEquals("Souq Waqif", places[0].name)
        assertEquals("QA", places[0].countryCode)
        assertEquals(25.288, places[0].point.lat, 0.01)
    }
}

class InstructionTextTest {
    @Test fun speaksInSeveralLanguagesAndFallsBackToEnglish() {
        assertEquals("Turn right onto Corniche.", InstructionText.forLanguage("en-GB").maneuver(ManeuverType.RIGHT, "Corniche", null))
        assertEquals("At the roundabout, take exit 3.", InstructionText.forLanguage("en").maneuver(ManeuverType.ROUNDABOUT, null, 3))
        assertEquals("انعطف يمينًا.", InstructionText.forLanguage("ar-QA").maneuver(ManeuverType.RIGHT, null, null))
        assertEquals("Turn left.", InstructionText.forLanguage("ja-JP").maneuver(ManeuverType.LEFT, "", null))
        assertEquals("In 300 meters, Turn left.", InstructionText.forLanguage("en").inDistance("300 m", "Turn left."))
        assertEquals("Dans 2.4 kilomètres, Tournez à gauche.", InstructionText.forLanguage("fr").inDistance("2.4 km", "Tournez à gauche."))
        for (lang in InstructionText.builtInLanguages) for (type in ManeuverType.entries) {
            assertTrue(InstructionText.forLanguage(lang).maneuver(type, "X", 2).isNotBlank())
        }
    }
}

class NavigationSessionTest {
    // 1 km east, then 500 m north, one point every 100 m.
    private val geometry: List<GeoPoint> = buildList {
        var p = GeoPoint(25.0, 51.0)
        add(p)
        repeat(10) { p = Geo.destination(p, 90.0, 100.0); add(p) }
        repeat(5) { p = Geo.destination(p, 0.0, 100.0); add(p) }
    }
    private val route = Route(
        TravelMode.DRIVING, geometry,
        listOf(
            Maneuver(ManeuverType.DEPART, "Head out.", lengthM = 1000.0, timeS = 60.0, shapeIndex = 0),
            Maneuver(ManeuverType.LEFT, "Turn left onto North Rd.", street = "North Rd", lengthM = 500.0, timeS = 40.0, shapeIndex = 10),
            Maneuver(ManeuverType.ARRIVE, "You have arrived at your destination.", lengthM = 0.0, timeS = 0.0, shapeIndex = 15),
        ),
        distanceM = 1500.0, durationS = 100.0, source = "test", offline = true,
    )

    private fun fix(p: GeoPoint, bearing: Float, t: Long, estimated: Boolean = false) = PositionFix(p, 5f, null, null, 14f, bearing, t, estimated)

    @Test fun announcesTurnsInAdvanceThenAtTheTurnThenArrival() {
        val session = NavigationSession(route, RegionProfile.forCountry("QA"), "en")
        val spoken = ArrayList<Pair<Double, String>>()
        var t = 0L
        var travelled = 0.0
        var lastRemaining = Double.MAX_VALUE
        while (travelled <= 1500.0) {
            val p = if (travelled <= 1000) Geo.destination(geometry[0], 90.0, travelled) else Geo.destination(geometry[10], 0.0, travelled - 1000)
            val u = session.update(fix(p, if (travelled <= 1000) 90f else 0f, t))
            u.announcements.forEach { spoken += travelled to it.text }
            assertFalse(u.needsReroute)
            assertTrue(u.progress.remainingM <= lastRemaining + 1.0)
            lastRemaining = u.progress.remainingM
            travelled += 14.0; t += 1000
        }
        val texts = spoken.map { it.second }
        assertEquals("Head out.", texts.first())
        val far = spoken.single { it.second.startsWith("In ") }
        assertTrue(far.second, far.second.endsWith("meters, Turn left onto North Rd."))
        assertTrue("far warning should come 250-300 m before the turn, was at ${far.first}", far.first in 700.0..760.0)
        val near = spoken.single { it.second == "Turn left onto North Rd." }
        assertTrue(near.first in 920.0..1000.0)
        assertEquals("You have arrived at your destination.", texts.last())
        assertTrue(session.arrived)
        assertEquals(4, texts.size)
    }

    @Test fun reportsNextManeuverAndDistance() {
        val session = NavigationSession(route, RegionProfile.forCountry("QA"), "en")
        val u = session.update(fix(Geo.destination(geometry[0], 90.0, 400.0), 90f, 0))
        assertEquals(ManeuverType.LEFT, u.progress.nextManeuver!!.type)
        assertEquals(600.0, u.progress.distanceToManeuverM, 3.0)
        assertEquals(1100.0, u.progress.remainingM, 3.0)
        assertEquals(73.3, u.progress.remainingS, 1.0)
        val after = session.update(fix(Geo.destination(geometry[10], 0.0, 200.0), 0f, 60_000))
        assertEquals(ManeuverType.ARRIVE, after.progress.nextManeuver!!.type)
        assertEquals(300.0, after.progress.distanceToManeuverM, 3.0)
    }

    @Test fun asksForRerouteOnceWhenClearlyOffRoute() {
        val session = NavigationSession(route, RegionProfile.forCountry("QA"), "en")
        session.update(fix(geometry[1], 90f, 0))
        val astray = Geo.destination(geometry[3], 180.0, 200.0)
        val results = (1..6).map { session.update(fix(astray, 180f, it * 1000L)) }
        assertTrue(results.all { it.progress.offRoute })
        assertEquals(1, results.count { it.needsReroute })
        assertTrue(results[3].needsReroute)
        assertTrue(results[3].announcements.any { it.text == "Recalculating the route." })
        // Back on the road: no longer off route.
        assertFalse(session.update(fix(geometry[4], 90f, 9000)).progress.offRoute)
    }

    @Test fun estimatedPositionsNeverTriggerReroute() {
        val session = NavigationSession(route, RegionProfile.forCountry("QA"), "en")
        val astray = Geo.destination(geometry[3], 180.0, 200.0)
        val results = (1..8).map { session.update(fix(astray, 180f, it * 1000L, estimated = true)) }
        assertTrue(results.none { it.needsReroute })
        assertTrue(results.all { it.progress.positionEstimated })
        assertNotNull(results.last().progress.nextManeuver)
    }
}

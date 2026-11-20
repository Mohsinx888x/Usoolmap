package com.usool.map

import com.usool.map.core.Availability
import com.usool.map.core.BBox
import com.usool.map.core.CoordinateParser
import com.usool.map.core.DistanceUnit
import com.usool.map.core.DrivingSide
import com.usool.map.core.Geo
import com.usool.map.core.GeoPoint
import com.usool.map.core.RegionProfile
import com.usool.map.gnss.Constellation
import com.usool.map.gnss.DeadReckoning
import com.usool.map.gnss.FixState
import com.usool.map.gnss.GnssSnapshot
import com.usool.map.gnss.MapMatcher
import com.usool.map.gnss.PositionFix
import com.usool.map.gnss.SatelliteInfo
import com.usool.map.gnss.SignalLossDetector
import com.usool.map.gnss.SignalQuality
import com.usool.map.map.Buildings3D
import com.usool.map.map.HeightQuality
import com.usool.map.traffic.OsmRoadDataProvider
import com.usool.map.traffic.RoadDataProvider
import com.usool.map.traffic.RoadDataRegistry
import com.usool.map.traffic.RoadService
import com.usool.map.voice.AzureSpeechClient
import com.usool.map.voice.GrokJokeClient
import com.usool.map.voice.VoiceCache
import com.usool.map.voice.VoiceLanguages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Locale

class GeoTest {
    private val doha = GeoPoint(25.2854, 51.5310)
    private val london = GeoPoint(51.5074, -0.1278)

    @Test fun distanceAndBearing() {
        assertEquals(5220.0, Geo.distanceM(doha, london) / 1000, 15.0)
        assertEquals(0.0, Geo.bearingDeg(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0)), 1e-6)
        assertEquals(90.0, Geo.bearingDeg(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0)), 1e-6)
        assertEquals(-90.0, Geo.angleDiffDeg(90.0, 0.0), 1e-9)
        assertEquals(20.0, Geo.angleDiffDeg(350.0, 10.0), 1e-9)
    }

    @Test fun destinationRoundTrips() {
        val p = Geo.destination(doha, 37.0, 1500.0)
        assertEquals(1500.0, Geo.distanceM(doha, p), 0.5)
        assertEquals(37.0, Geo.bearingDeg(doha, p), 0.05)
    }

    @Test fun worksAcrossTheAntimeridianAndNearPoles() {
        val fiji = GeoPoint(-17.0, 179.9)
        val east = Geo.destination(fiji, 90.0, 50_000.0)
        assertTrue(east.isValid)
        assertTrue(east.lon < -179.0)
        assertTrue(Geo.destination(GeoPoint(89.9, 0.0), 0.0, 5_000.0).isValid)
    }

    @Test fun decodesPolyline() {
        // Reference string from the polyline algorithm documentation (precision 5).
        val pts = Geo.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-6); assertEquals(-120.2, pts[0].lon, 1e-6)
        assertEquals(43.252, pts[2].lat, 1e-6); assertEquals(-126.453, pts[2].lon, 1e-6)
    }

    @Test fun projectsOntoSegment() {
        val a = GeoPoint(25.0, 51.0)
        val b = GeoPoint(25.0, 51.01)
        val pr = Geo.projectOnSegment(GeoPoint(25.001, 51.005), a, b)
        assertEquals(0.5, pr.t, 0.01)
        assertEquals(111.2, pr.distanceM, 1.0)
        assertEquals(0.0, Geo.projectOnSegment(GeoPoint(25.0, 50.9), a, b).t, 1e-9)
    }
}

class CoordinateParserTest {
    @Test fun parsesCommonFormats() {
        assertEquals(GeoPoint(25.2854, 51.531), CoordinateParser.parse("25.2854, 51.5310"))
        assertEquals(GeoPoint(-33.8688, 151.2093), CoordinateParser.parse("33.8688S 151.2093E"))
        assertEquals(GeoPoint(40.7128, -74.006), CoordinateParser.parse("geo:40.7128,-74.0060?z=12"))
        val dms = CoordinateParser.parse("25°17'07.4\"N 51°31'51.6\"E")!!
        assertEquals(25.28539, dms.lat, 1e-4); assertEquals(51.531, dms.lon, 1e-4)
        val south = CoordinateParser.parse("33°52'08\"S 151°12'33\"E")!!
        assertTrue(south.lat < 0)
    }

    @Test fun rejectsTextAndOutOfRange() {
        assertNull(CoordinateParser.parse("Souq Waqif"))
        assertNull(CoordinateParser.parse("95.0, 10.0"))
        assertNull(CoordinateParser.parse("12 Main Street"))
        assertNull(CoordinateParser.parse(""))
    }
}

class RegionProfileTest {
    @Test fun defaultsToQatarWithoutLimitingOtherCountries() {
        assertEquals("QA", RegionProfile.forCountry(null).countryCode)
        // Every ISO code resolves, including ones with no special rules.
        for (cc in Locale.getISOCountries()) assertEquals(cc, RegionProfile.forCountry(cc).countryCode)
    }

    @Test fun unitsAndDrivingSide() {
        val qa = RegionProfile.forCountry("qa")
        assertEquals(DistanceUnit.METRIC, qa.distanceUnit); assertEquals(DrivingSide.RIGHT, qa.drivingSide)
        val gb = RegionProfile.forCountry("GB")
        assertEquals(DistanceUnit.IMPERIAL, gb.distanceUnit); assertEquals(DrivingSide.LEFT, gb.drivingSide)
        assertEquals(DrivingSide.LEFT, RegionProfile.forCountry("JP").drivingSide)
        assertEquals(DistanceUnit.METRIC, RegionProfile.forCountry("JP").distanceUnit)
        assertEquals("mph", RegionProfile.forCountry("US").speedUnitLabel)
        assertEquals(62.1, RegionProfile.forCountry("US").speedFromKmh(100.0), 0.1)
    }

    @Test fun formatsDistances() {
        val qa = RegionProfile.forCountry("QA")
        assertEquals("350 m", qa.formatDistance(354.0, Locale.US))
        assertEquals("2.4 km", qa.formatDistance(2400.0, Locale.US))
        val us = RegionProfile.forCountry("US")
        assertEquals("500 ft", us.formatDistance(160.0, Locale.US))
        assertEquals("1.5 mi", us.formatDistance(2414.0, Locale.US))
    }

    @Test fun cameraAlertsFollowLocalRules() {
        assertTrue(RegionProfile.forCountry("QA").speedCameraAlertsAllowed)
        assertFalse(RegionProfile.forCountry("CH").speedCameraAlertsAllowed)
    }
}

class GnssTest {
    private fun sat(c: Constellation, used: Boolean, cn0: Float) = SatelliteInfo(1, c, cn0, 45f, 90f, used)

    @Test fun mapsEveryAndroidConstellation() {
        assertEquals(Constellation.GPS, Constellation.fromAndroid(1))
        assertEquals(Constellation.SBAS, Constellation.fromAndroid(2))
        assertEquals(Constellation.GLONASS, Constellation.fromAndroid(3))
        assertEquals(Constellation.QZSS, Constellation.fromAndroid(4))
        assertEquals(Constellation.BEIDOU, Constellation.fromAndroid(5))
        assertEquals(Constellation.GALILEO, Constellation.fromAndroid(6))
        assertEquals(Constellation.NAVIC, Constellation.fromAndroid(7))
        assertEquals(Constellation.UNKNOWN, Constellation.fromAndroid(99))
    }

    @Test fun summarisesSatellites() {
        val snap = GnssSnapshot(
            state = FixState.FIXED,
            satellites = listOf(
                sat(Constellation.GPS, true, 40f), sat(Constellation.GPS, false, 20f), sat(Constellation.GALILEO, true, 38f),
                sat(Constellation.BEIDOU, true, 42f), sat(Constellation.GLONASS, true, 36f), sat(Constellation.NAVIC, false, 0f),
            ),
        )
        assertEquals(6, snap.visibleCount)
        assertEquals(4, snap.usedCount)
        assertEquals(2 to 1, snap.byConstellation[Constellation.GPS])
        assertEquals(1 to 0, snap.byConstellation[Constellation.NAVIC])
        assertEquals(39f, snap.meanCn0DbHz!!, 0.01f)
        assertEquals(SignalQuality.EXCELLENT, snap.signalQuality)
        assertEquals(SignalQuality.NONE, GnssSnapshot().signalQuality)
    }

    @Test fun detectsSignalLossAndRecovery() {
        val d = SignalLossDetector(staleAfterMs = 4_000, maxBridgeMs = 45_000)
        assertEquals(FixState.SEARCHING, d.evaluate(false, null, false, true))
        assertEquals(FixState.FIXED, d.evaluate(true, 1_000, true, true))
        assertEquals(FixState.DEAD_RECKONING, d.evaluate(true, 10_000, true, true))
        assertEquals(FixState.LOST, d.evaluate(true, 10_000, false, true))
        assertEquals(FixState.LOST, d.evaluate(true, 60_000, true, true))
        assertEquals(FixState.FIXED, d.evaluate(true, 500, true, true))
        assertEquals(FixState.DISABLED, d.evaluate(true, 500, true, false))
    }

    @Test fun deadReckoningCarriesPositionForward() {
        val dr = DeadReckoning()
        val start = GeoPoint(25.0, 51.0)
        dr.onFix(PositionFix(start, 5f, null, null, 20f, 90f, elapsedMs = 1_000))
        assertTrue(dr.canEstimate())
        val est = dr.estimate(6_000)!!
        assertTrue(est.estimated)
        assertEquals(100.0, Geo.distanceM(start, est.point), 0.5)
        assertEquals(90.0, Geo.bearingDeg(start, est.point), 0.1)
        assertTrue(est.accuracyM!! > 5f)
    }

    @Test fun deadReckoningFollowsSensorTurnAndRefusesWhenStationary() {
        val dr = DeadReckoning()
        dr.sensorHeadingDeg = 10f
        dr.onFix(PositionFix(GeoPoint(25.0, 51.0), 5f, null, null, 10f, 0f, elapsedMs = 0))
        dr.sensorHeadingDeg = 100f // the device turned 90° to the right
        assertEquals(90f, dr.estimate(2_000)!!.bearingDeg!!, 0.01f)

        val still = DeadReckoning()
        still.onFix(PositionFix(GeoPoint(25.0, 51.0), 5f, null, null, 0.2f, 0f, elapsedMs = 0))
        assertFalse(still.canEstimate())
        assertNull(still.estimate(5_000))
    }

    @Test fun mapMatcherSnapsToRoute() {
        val route = listOf(GeoPoint(25.0, 51.0), GeoPoint(25.0, 51.01), GeoPoint(25.01, 51.01))
        val m = MapMatcher(route)
        val match = m.match(GeoPoint(25.0002, 51.005), 90.0)!!
        assertEquals(0, match.segmentIndex)
        assertEquals(25.0, match.point.lat, 1e-6)
        assertEquals(22.2, match.distanceFromRouteM, 0.5)
        assertEquals(504.0, match.alongM, 3.0)
        val second = m.match(GeoPoint(25.005, 51.0101), 0.0)!!
        assertEquals(1, second.segmentIndex)
        assertTrue(second.alongM > match.alongM)
    }
}

class Buildings3DTest {
    @Test fun classifiesHeightSources() {
        assertEquals(HeightQuality.MEASURED to 12.5, Buildings3D.classify(mapOf("building" to "yes", "height" to "12.5 m")))
        assertEquals(HeightQuality.MEASURED, Buildings3D.classify(mapOf("building" to "yes", "height" to "40 ft")).first)
        val levels = Buildings3D.classify(mapOf("building" to "apartments", "building:levels" to "5"))
        assertEquals(HeightQuality.FROM_LEVELS, levels.first); assertEquals(16.0, levels.second, 1e-9)
        assertEquals(HeightQuality.FROM_TYPE, Buildings3D.classify(mapOf("building" to "garage")).first)
        // A bare outline gets no invented height.
        assertEquals(HeightQuality.FOOTPRINT_ONLY to 0.0, Buildings3D.classify(mapOf("building" to "yes")))
        assertEquals(HeightQuality.FOOTPRINT_ONLY, Buildings3D.classify(mapOf("building" to "commercial", "height" to "tall")).first)
    }

    @Test fun buildsGeoJsonOnlyFromRealOutlines() {
        fun way(id: Int, tags: String, closed: Boolean = true) = """{"type":"way","id":$id,"tags":{$tags},"geometry":[
            {"lat":25.0,"lon":51.0},{"lat":25.0,"lon":51.001},{"lat":25.001,"lon":51.001},${if (closed) """{"lat":25.0,"lon":51.0}""" else """{"lat":25.002,"lon":51.0}"""}]}"""
        val json = """{"elements":[${way(1, "\"building\":\"yes\",\"height\":\"20\"")},${way(2, "\"building\":\"yes\",\"building:levels\":\"3\"")},
            ${way(3, "\"building\":\"yes\"")},${way(4, "\"building\":\"yes\"", closed = false)},{"type":"node","id":9,"lat":25.0,"lon":51.0}]}"""
        val result = Buildings3D.toGeoJson(json)
        assertEquals(1, result.summary.measured)
        assertEquals(1, result.summary.fromLevels)
        assertEquals(1, result.summary.footprintOnly)
        assertEquals(3, result.summary.total)
        assertTrue(result.geoJson.contains("\"quality\":\"FROM_LEVELS\""))
        assertEquals(0, Buildings3D.toGeoJson("""{"elements":[]}""").summary.total)
    }
}

class RoadDataRegistryTest {
    private class Fake(override val name: String, override val services: Set<RoadService>, override val countries: Set<String>, override val configured: Boolean, override val requiresInternet: Boolean = true) : RoadDataProvider

    @Test fun picksCountryProviderBeforeWorldwideOne() {
        val world = Fake("World", setOf(RoadService.INCIDENTS), emptySet(), true)
        val qatar = Fake("Qatar feed", setOf(RoadService.INCIDENTS), setOf("QA"), true)
        val reg = RoadDataRegistry(listOf(world, qatar)) { true }
        assertEquals("Qatar feed", reg.providerFor(RoadService.INCIDENTS, "qa")!!.name)
        assertEquals("World", reg.providerFor(RoadService.INCIDENTS, "BR")!!.name)
    }

    @Test fun reportsGapsInsteadOfPretending() {
        val unconfigured = Fake("Traffic Co", setOf(RoadService.LIVE_TRAFFIC), emptySet(), configured = false)
        val osm = Fake("OSM", setOf(RoadService.SPEED_CAMERAS, RoadService.SPEED_LIMITS), emptySet(), true)
        var online = true
        val reg = RoadDataRegistry(listOf(unconfigured, osm)) { online }
        assertTrue(reg.availability(RoadService.LIVE_TRAFFIC, "QA") is Availability.Unavailable)
        assertNull(reg.providerFor(RoadService.LIVE_TRAFFIC, "QA"))
        assertTrue(reg.availability(RoadService.HAZARDS, "QA") is Availability.Unavailable)
        assertTrue(reg.availability(RoadService.SPEED_CAMERAS, "QA").isAvailable)
        // Restricted by law in Switzerland even though data exists.
        assertFalse(reg.availability(RoadService.SPEED_CAMERAS, "CH").isAvailable)
        online = false
        assertFalse(reg.availability(RoadService.SPEED_LIMITS, "QA").isAvailable)
        assertEquals(RoadService.entries.size, reg.statusReport("QA").size)
    }

    @Test fun parsesCameras() {
        val json = """{"elements":[{"type":"node","id":1,"lat":25.3,"lon":51.5,"tags":{"highway":"speed_camera","maxspeed":"80"}},
            {"type":"node","id":2,"lat":25.4,"lon":51.6,"tags":{"highway":"speed_camera"}}]}"""
        val cams = OsmRoadDataProvider.parseCameras(json, "OSM")
        assertEquals(2, cams.size)
        assertEquals(80, cams[0].limitKmh)
        assertNull(cams[1].limitKmh)
    }
}

class VoiceTest {
    @Test fun ssmlEscapesText() {
        val ssml = AzureSpeechClient.ssml("Turn <left> & \"go\"", VoiceLanguages.forTag("ar-QA"))
        assertTrue(ssml.contains("xml:lang='ar-QA'"))
        assertTrue(ssml.contains("ar-QA-MoazNeural"))
        assertTrue(ssml.contains("Turn &lt;left&gt; &amp; &quot;go&quot;"))
    }

    @Test fun languageFallsBackSensibly() {
        assertEquals("ar-QA", VoiceLanguages.forTag("ar-SA").tag)
        assertEquals("en-US", VoiceLanguages.forTag("xx-YY").tag)
        assertEquals("hi-IN", VoiceLanguages.forTag("hi").tag)
    }

    @Test fun parsesGrokReplyAndRejectsBadOnes() {
        assertEquals("Turn right, like you meant it.", GrokJokeClient.parseReply("""{"choices":[{"message":{"role":"assistant","content":"\"Turn right, like you meant it.\""}}]}"""))
        assertNull(GrokJokeClient.parseReply("""{"error":"bad key"}"""))
        assertNull(GrokJokeClient.parseReply("""{"choices":[{"message":{"content":"${"x".repeat(500)}"}}]}"""))
    }

    @Test fun cacheStoresAndEvicts() {
        val dir = Files.createTempDirectory("voice").toFile()
        val cache = VoiceCache(dir, maxBytes = 250)
        val en = VoiceLanguages.forTag("en-US")
        val k1 = cache.key("Turn left.", en)
        assertNull(cache.get(k1))
        cache.put(k1, ByteArray(100))
        assertNotNull(cache.get(k1))
        assertTrue(cache.key("Turn left.", VoiceLanguages.forTag("fr-FR")) != k1)
        dir.listFiles()!!.forEach { it.setLastModified(1_000) }
        cache.put(cache.key("Turn right.", en), ByteArray(100))
        cache.put(cache.key("Continue.", en), ByteArray(100))
        assertTrue(cache.sizeBytes <= 250)
        assertNull(cache.get(k1))
        assertEquals(2, cache.clipCount)
        dir.deleteRecursively()
    }

    @Suppress("unused") private val unusedBBox = BBox(0.0, 0.0, 1.0, 1.0)
}

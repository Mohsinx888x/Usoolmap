package com.usool.map

import com.usool.map.core.GeoPoint
import com.usool.map.imagery.MapillaryProvider
import com.usool.map.imagery.PanoramaxProvider
import com.usool.map.imagery.SatelliteCatalog
import com.usool.map.imagery.StreetImage
import com.usool.map.imagery.StreetImageryProvider
import com.usool.map.imagery.StreetViewException
import com.usool.map.imagery.StreetViewRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SatelliteTest {
    @Test fun groundResolutionMatchesWebMercator() {
        assertEquals(156_543.0, SatelliteCatalog.groundResolutionM(0.0, 0.0), 1.0)
        // Zoom 19 is the level at which one pixel is about 30 cm at the equator.
        assertEquals(0.2986, SatelliteCatalog.groundResolutionM(0.0, 19.0), 0.001)
        assertEquals(0.27, SatelliteCatalog.groundResolutionM(25.3, 19.0), 0.005)
        assertEquals(8.6, SatelliteCatalog.groundResolutionM(25.3, 14.0), 0.1)
        assertEquals("30 cm", SatelliteCatalog.formatResolution(0.2986))
        assertEquals("2.4 m", SatelliteCatalog.formatResolution(2.39))
        assertEquals("9.6 m", SatelliteCatalog.formatResolution(9.6))
        assertEquals("12 m", SatelliteCatalog.formatResolution(12.3))
    }

    @Test fun freeImageryIsTheDefaultAndNeverClaimsHighResolution() {
        val s = SatelliteCatalog.select("", "", 19, "", "")
        assertEquals(SatelliteCatalog.SENTINEL2, s)
        assertFalse(s.highResolution)
        val text = SatelliteCatalog.describe(s, 25.3, 17.0)
        assertTrue(text, text.contains("about 8.6 m per pixel"))
        assertTrue(text.contains("enlarged, not sharper"))
        assertTrue(text.contains("needs a licensed provider key"))
        assertFalse(SatelliteCatalog.describe(s, 25.3, 12.0).contains("enlarged"))
    }

    @Test fun licensedSourcesTakePriority() {
        val mapTiler = SatelliteCatalog.select("", "", 19, "", "KEY")
        assertTrue(mapTiler.highResolution)
        assertTrue(mapTiler.tileUrl.endsWith("{z}/{x}/{y}.jpg?key=KEY"))
        val custom = SatelliteCatalog.select("https://tiles.example/{z}/{x}/{y}.jpg", "Aerial 30", 19, "© Example", "KEY")
        assertEquals("Aerial 30", custom.name)
        assertTrue(custom.highResolution)
        val text = SatelliteCatalog.describe(custom, 25.3, 18.0)
        assertTrue(text, text.contains("about 27 cm per pixel"))
        assertFalse(text.contains("licensed provider key"))
        assertFalse(SatelliteCatalog.select("https://t/{z}/{x}/{y}", "", 15, "", "").highResolution)
    }
}

class StreetViewTest {
    private fun resource(name: String) = Thread.currentThread().contextClassLoader!!.getResourceAsStream(name)!!.readBytes().toString(Charsets.UTF_8)
    private val eiffel = GeoPoint(48.8582, 2.2946)

    @Test fun parsesRealPanoramaxPhotos() {
        val images = PanoramaxProvider.parse(resource("panoramax_search.json"), "Panoramax")
        assertEquals(3, images.size)
        val first = images[0]
        assertEquals(48.8582, first.point.lat, 0.001); assertEquals(2.2946, first.point.lon, 0.001)
        assertEquals(172.0, first.headingDeg!!, 0.01)
        assertEquals("2025-10-10", first.capturedOn)
        assertFalse(first.isPanorama)
        assertTrue(first.fullUrl.endsWith("/hd.jpg"))
        assertTrue(first.previewUrl.endsWith("/sd.jpg"))
        assertEquals("etalab-2.0", first.licence)
        assertTrue(!first.author.isNullOrBlank())
        assertEquals(0, PanoramaxProvider.parse("""{"features":[]}""", "Panoramax").size)
    }

    @Test fun parsesMapillaryFormat() {
        val json = """{"data":[
          {"id":"111","computed_geometry":{"type":"Point","coordinates":[51.531,25.2854]},"compass_angle":90.5,"captured_at":1700000000000,
           "is_pano":true,"thumb_1024_url":"https://x/1024.jpg","thumb_2048_url":"https://x/2048.jpg","creator":{"username":"mapper","id":"1"}},
          {"id":"222","geometry":{"type":"Point","coordinates":[51.532,25.2855]},"thumb_1024_url":"https://x/b.jpg"},
          {"id":"333","geometry":{"type":"Point","coordinates":[51.5,25.2]}}]}"""
        val images = MapillaryProvider.parse(json, "Mapillary")
        assertEquals(2, images.size) // the one without a picture URL is dropped
        assertTrue(images[0].isPanorama)
        assertEquals("2023-11-14", images[0].capturedOn)
        assertEquals("https://x/2048.jpg", images[0].fullUrl)
        assertEquals("mapper", images[0].author)
        assertEquals("https://x/b.jpg", images[1].fullUrl)
        assertNull(images[1].headingDeg)
    }

    private fun img(id: String, p: GeoPoint, provider: String = "A") = StreetImage(id, p, null, null, false, "p", "f", null, "l", provider)

    private class Fake(override val name: String, override val configured: Boolean, val result: () -> List<StreetImage>) : StreetImageryProvider {
        override val coverage = null
        override suspend fun near(point: GeoPoint, radiusM: Double) = result()
    }

    @Test fun returnsNearestFirstAndDropsFarAndDuplicatePhotos() {
        val near = img("near", GeoPoint(48.85821, 2.2946))
        val mid = img("mid", GeoPoint(48.8585, 2.2946))
        val far = img("far", GeoPoint(48.87, 2.2946))
        val sorted = StreetViewRepository.sortNearest(listOf(far, mid, near, near), eiffel, 80.0)
        assertEquals(listOf("near", "mid"), sorted.map { it.id })
    }

    @Test fun mergesProvidersAndSkipsUnconfiguredOnes() = runTest {
        val repo = StreetViewRepository(listOf(
            Fake("NoToken", false) { fail("must not be called"); emptyList() },
            Fake("Broken", true) { throw StreetViewException("down") },
            Fake("Open", true) { listOf(img("a", eiffel, "Open")) },
        ))
        assertEquals(listOf("Broken", "Open"), repo.activeProviders.map { it.name })
        assertEquals(listOf("a"), repo.near(eiffel).map { it.id })
    }

    @Test fun emptyMeansNoPhotosButTotalFailureIsAnError() = runTest {
        assertTrue(StreetViewRepository(listOf(Fake("Open", true) { emptyList() })).near(eiffel).isEmpty())
        try {
            StreetViewRepository(listOf(Fake("Broken", true) { throw StreetViewException("down") })).near(eiffel)
            fail("expected StreetViewException")
        } catch (e: StreetViewException) {
            assertEquals("down", e.message)
        }
    }
}

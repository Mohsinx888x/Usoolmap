package com.usool.map

import com.usool.map.core.Geo
import com.usool.map.core.GeoPoint
import com.usool.map.imagery.MapillaryProvider
import com.usool.map.imagery.PanoMath
import com.usool.map.imagery.StreetImage
import com.usool.map.imagery.StreetNavigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreetNavigatorTest {
    private val here = GeoPoint(25.2854, 51.5310)
    private fun img(id: String, bearing: Double, metres: Double, pano: Boolean = true, seq: String? = "s1") =
        StreetImage(id, Geo.destination(here, bearing, metres), 0.0, null, pano, "p", "f", null, "l", "A", seq)
    private val current = StreetImage("me", here, 0.0, null, true, "p", "f", null, "l", "A", "s1")

    @Test fun offersOneStepPerDirection() {
        val hops = StreetNavigator.hops(current, listOf(img("n10", 0.0, 10.0), img("n25", 2.0, 25.0), img("s12", 180.0, 12.0), img("e30", 90.0, 30.0), current))
        // The order of the arrows is not significant; which photos are offered is.
        assertEquals(setOf("n10", "e30", "s12"), hops.map { it.image.id }.toSet())
        assertEquals(10.0, hops.first { it.image.id == "n10" }.distanceM, 0.5)
        assertEquals(90.0, hops.first { it.image.id == "e30" }.bearingDeg, 1.0)
    }

    @Test fun ignoresPhotosTooCloseOrTooFar() {
        val hops = StreetNavigator.hops(current, listOf(img("same-spot", 0.0, 1.0), img("far", 0.0, 200.0), img("ok", 0.0, 20.0)))
        assertEquals(listOf("ok"), hops.map { it.image.id })
        assertTrue(StreetNavigator.hops(current, emptyList()).isEmpty())
    }

    @Test fun staysOnTheSameDriveAndPrefersPanoramas() {
        // A photo from another sequence is slightly nearer, but the same drive continues 8 m further on.
        assertEquals("mine", StreetNavigator.hops(current, listOf(img("other", 0.0, 10.0, seq = "s2"), img("mine", 1.0, 18.0)))[0].image.id)
        // A flat photo is nearer, but a panorama just beyond keeps the look-around view.
        assertEquals("pano", StreetNavigator.hops(current, listOf(img("flat", 90.0, 10.0, pano = false), img("pano", 91.0, 22.0)))[0].image.id)
        // With nothing better in that direction the other photo is still offered.
        assertEquals("other", StreetNavigator.hops(current, listOf(img("other", 0.0, 10.0, seq = "s2")))[0].image.id)
    }

    @Test fun limitsTheNumberOfArrowsToTheNearest() {
        val all = (0 until 8).map { img("d$it", it * 45.0, 10.0 + it * 5) }
        val hops = StreetNavigator.hops(current, all, max = 4)
        assertEquals(setOf("d0", "d1", "d2", "d3"), hops.map { it.image.id }.toSet())
    }

    @Test fun picksNearestPhotoButPrefersANearbyPanorama() {
        val flatNear = img("flat", 0.0, 5.0, pano = false)
        assertEquals("pano", StreetNavigator.pick(listOf(flatNear, img("pano", 90.0, 14.0)), here)!!.id)
        assertEquals("flat", StreetNavigator.pick(listOf(flatNear, img("pano-far", 90.0, 60.0)), here)!!.id)
        assertEquals("p1", StreetNavigator.pick(listOf(img("p1", 0.0, 4.0), img("p2", 0.0, 9.0)), here)!!.id)
        assertNull(StreetNavigator.pick(emptyList(), here))
    }

    @Test fun parsesMapillarySequenceAndUsesOriginalForPanoramas() {
        val json = """{"data":[
          {"id":"1","computed_geometry":{"type":"Point","coordinates":[51.531,25.2854]},"computed_compass_angle":87.5,"compass_angle":90.0,"is_pano":true,
           "thumb_1024_url":"https://x/1024.jpg","thumb_2048_url":"https://x/2048.jpg","thumb_original_url":"https://x/orig.jpg","sequence":"SEQ1"},
          {"id":"2","geometry":{"type":"Point","coordinates":[51.532,25.2855]},"compass_angle":10.0,"is_pano":false,
           "thumb_1024_url":"https://x/b1024.jpg","thumb_2048_url":"https://x/b2048.jpg","thumb_original_url":"https://x/borig.jpg","sequence":"SEQ1"}]}"""
        val images = MapillaryProvider.parse(json, "Mapillary")
        assertEquals("https://x/orig.jpg", images[0].fullUrl)
        assertEquals(87.5, images[0].headingDeg!!, 0.001)
        assertEquals("SEQ1", images[0].sequenceId)
        assertEquals("https://x/b2048.jpg", images[1].fullUrl)
    }
}

class PanoMathTest {
    private val fov = Math.toRadians(75.0)

    @Test fun lookingStraightAheadShowsTheMiddleOfThePhoto() {
        val (u, v) = PanoMath.uv(0.0, 0.0, 0.0, 0.0, fov, 0.8)
        assertEquals(0.5, u, 1e-9); assertEquals(0.5, v, 1e-9)
    }

    @Test fun turningRightMovesRightAroundThePhoto() {
        assertEquals(0.75, PanoMath.uv(0.0, 0.0, Math.PI / 2, 0.0, fov, 0.8).first, 1e-9)
        assertEquals(0.25, PanoMath.uv(0.0, 0.0, -Math.PI / 2, 0.0, fov, 0.8).first, 1e-9)
        // Directly behind is the left/right edge of the photo.
        val back = PanoMath.uv(0.0, 0.0, Math.PI, 0.0, fov, 0.8).first
        assertTrue(back < 1e-6 || back > 1 - 1e-6)
    }

    @Test fun lookingUpShowsTheTopOfThePhoto() {
        assertEquals(0.25, PanoMath.uv(0.0, 0.0, 0.0, Math.PI / 4, fov, 0.8).second, 1e-9)
        assertEquals(0.75, PanoMath.uv(0.0, 0.0, 0.0, -Math.PI / 4, fov, 0.8).second, 1e-9)
    }

    @Test fun screenEdgesMatchTheFieldOfView() {
        // The top edge of the screen is half the vertical field of view above the centre.
        val top = PanoMath.uv(0.0, 1.0, 0.0, 0.0, fov, 0.8).second
        assertEquals(0.5 - (fov / 2) / Math.PI, top, 1e-9)
        // The right half of the screen shows what lies to the right, never mirrored.
        assertTrue(PanoMath.uv(0.5, 0.0, 0.0, 0.0, fov, 0.8).first > 0.5)
        assertTrue(PanoMath.uv(-0.5, 0.0, 0.0, 0.0, fov, 0.8).first < 0.5)
    }

    @Test fun reportsCompassDirectionOfTheView() {
        assertEquals(90.0, PanoMath.viewBearing(90.0, 0.0), 1e-9)
        assertEquals(180.0, PanoMath.viewBearing(90.0, Math.PI / 2), 1e-9)
        assertEquals(350.0, PanoMath.viewBearing(10.0, Math.toRadians(-20.0)), 1e-9)
        assertEquals(45.0, PanoMath.viewBearing(null, Math.PI / 4), 1e-9)
    }
}

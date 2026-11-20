package com.usool.map

import com.usool.map.map.MapTheme
import com.usool.map.map.SunClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class MapThemeTest {
    private val json = File("src/main/assets/map_themes.json").readText()
    private val day = MapTheme.parse(json, dark = false)
    private val night = MapTheme.parse(json, dark = true)

    @Test fun dayKeepsTheBaseStyleAndUsesBrandColours() {
        assertFalse(day.dark)
        assertEquals("#FEC502", day.routeLine)
        assertEquals("#111111", day.routeCasing)
        assertNull(day.ruleFor("water"))
        assertTrue(day.paintFor("road_motorway", "line").isEmpty())
        assertEquals(4, day.buildingStops.size)
        assertTrue(day.buildingStops.zipWithNext().all { it.first.first < it.second.first })
    }

    @Test fun nightRecoloursEachKindOfLayer() {
        assertTrue(night.dark)
        assertEquals(mapOf("background-color" to "#14171c"), night.paintFor("background", "background"))
        assertEquals("#0d2436", night.paintFor("water", "fill")["fill-color"])
        assertEquals("#0d2436", night.paintFor("waterway_river", "line")["line-color"])
        // Casings are matched before the road they belong to.
        assertEquals("#0c0e12", night.paintFor("road_motorway_casing", "line")["line-color"])
        assertEquals("#d6a20a", night.paintFor("road_motorway", "line")["line-color"])
        assertEquals("#d6a20a", night.paintFor("bridge_motorway_link", "line")["line-color"])
        assertEquals("#94782a", night.paintFor("tunnel_trunk_primary", "line")["line-color"])
        assertEquals("#5f5638", night.paintFor("road_link", "line")["line-color"])
        assertEquals("#353b45", night.paintFor("bridge_street", "line")["line-color"])
        assertEquals("#4b515b", night.paintFor("road_major_rail_hatching", "line")["line-color"])
        assertEquals("#f4f4f4", night.paintFor("label_city_capital", "symbol")["text-color"])
        assertEquals("#6fa7cf", night.paintFor("waterway_line_label", "symbol")["text-color"])
        assertTrue(night.ruleFor("natural_earth")!!.hide)
        assertNull(night.ruleFor("highway-shield-non-us"))
        // A rule's properties only reach layers of the matching type.
        assertTrue(night.paintFor("water", "line").isEmpty())
    }

    @Test fun labelFieldsFallBackToLocalName() {
        assertEquals(listOf("name:ar", "name_ar", "name:nonlatin", "name"), MapTheme.labelFields("ar"))
        assertEquals(listOf("name:en", "name_en", "name:latin", "name"), MapTheme.labelFields("en"))
        assertEquals(listOf("name"), MapTheme.labelFields("local"))
        assertEquals("name", MapTheme.labelFields("ja").last())
    }

    @Test fun sunClockKnowsDayFromNight() {
        fun at(iso: String) = Instant.parse(iso).toEpochMilli()
        // Doha is UTC+3: local noon and local midnight in November.
        assertFalse(SunClock.isNight(at("2026-11-20T09:00:00Z"), 25.29, 51.53))
        assertTrue(SunClock.isNight(at("2026-11-20T21:00:00Z"), 25.29, 51.53))
        assertTrue(SunClock.elevationDeg(at("2026-11-20T08:30:00Z"), 25.29, 51.53) in 40.0..48.0)
        // Sunset in Doha in late November is about 16:45 local (13:45 UTC).
        assertFalse(SunClock.isNight(at("2026-11-20T13:00:00Z"), 25.29, 51.53))
        assertTrue(SunClock.isNight(at("2026-11-20T14:30:00Z"), 25.29, 51.53))
        // Same instant, other side of the world.
        assertTrue(SunClock.isNight(at("2026-11-20T09:00:00Z"), 34.05, -118.24))
        // Midnight sun: northern Norway at local midnight in June.
        assertFalse(SunClock.isNight(at("2026-06-21T23:00:00Z"), 69.65, 18.96))
    }
}

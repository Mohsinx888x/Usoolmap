package com.usool.map

import com.google.gson.JsonParser
import com.usool.map.assistant.AssistantAction
import com.usool.map.assistant.AssistantActions
import com.usool.map.assistant.LocalCommands
import com.usool.map.core.Geo
import com.usool.map.core.GeoPoint
import com.usool.map.imagery.SatelliteCatalog
import com.usool.map.routing.TravelMode
import com.usool.map.routing.ValhallaRoutingProvider
import com.usool.map.ui.globeConfig
import com.usool.map.ui.globeHeightForZoom
import com.usool.map.voice.VoiceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCommandsTest {
    @Test fun understandsTheStandardCommandsInEnglish() {
        assertEquals(AssistantAction.NavigateHome, LocalCommands.match("Take me home."))
        assertEquals(AssistantAction.FindNearby("fuel"), LocalCommands.match("Find the nearest petrol station"))
        assertEquals(AssistantAction.FindNearby("pharmacy"), LocalCommands.match("nearest pharmacy"))
        assertEquals(AssistantAction.AvoidTolls(true), LocalCommands.match("Avoid toll roads"))
        assertEquals(AssistantAction.AvoidTolls(false), LocalCommands.match("allow toll roads"))
        assertEquals(AssistantAction.Set3D(true), LocalCommands.match("Switch to 3D mode"))
        assertEquals(AssistantAction.Set3D(false), LocalCommands.match("switch to 2D"))
        assertEquals(AssistantAction.ShowTraffic, LocalCommands.match("Show traffic"))
        assertEquals(AssistantAction.SetVoiceMode(VoiceMode.FUNNY), LocalCommands.match("Enable funny navigation"))
        assertEquals(AssistantAction.SetVoiceMode(VoiceMode.PROFESSIONAL), LocalCommands.match("turn off funny navigation"))
        assertEquals(AssistantAction.SetVoiceLanguage("ar-QA"), LocalCommands.match("Change the voice to Qatari Arabic"))
        assertEquals(AssistantAction.SetVoiceLanguage("en-GB"), LocalCommands.match("change the voice to British English"))
        assertEquals(AssistantAction.DownloadOfflineMap, LocalCommands.match("Download the offline map"))
        assertEquals(AssistantAction.GenerateJokes, LocalCommands.match("Generate new jokes"))
        assertEquals(AssistantAction.StopNavigation, LocalCommands.match("stop navigation"))
        assertEquals(AssistantAction.NavigateTo("Souq Waqif"), LocalCommands.match("Take me to Souq Waqif"))
        assertEquals(AssistantAction.OpenGlobe, LocalCommands.match("open the globe"))
    }

    @Test fun understandsArabic() {
        assertEquals(AssistantAction.NavigateHome, LocalCommands.match("خذني البيت"))
        assertEquals(AssistantAction.FindNearby("fuel"), LocalCommands.match("وين أقرب محطة وقود"))
        assertEquals(AssistantAction.FindNearby("halal_restaurant"), LocalCommands.match("ابحث عن مطعم حلال قريب"))
        assertEquals(AssistantAction.Set3D(true), LocalCommands.match("شغل الوضع ثلاثي الأبعاد"))
        assertEquals(AssistantAction.SetVoiceLanguage("ar-QA"), LocalCommands.match("غيّر الصوت إلى اللهجة القطرية"))
        assertEquals(AssistantAction.GenerateJokes, LocalCommands.match("ولّد نكت جديدة"))
        assertEquals(AssistantAction.StopNavigation, LocalCommands.match("أوقف الملاحة"))
    }

    @Test fun leavesOrdinarySearchesAlone() {
        assertNull(LocalCommands.match("Souq Waqif"))
        assertNull(LocalCommands.match("غوانزو"))
        assertNull(LocalCommands.match("Hamad International Airport"))
        assertNull(LocalCommands.match("مطعم حلال"))
    }
}

class AssistantValidationTest {
    @Test fun acceptsOnlyListedActionsWithValidParameters() {
        assertEquals(AssistantAction.Set3D(true), AssistantActions.fromJson("""{"action":"set_3d","enabled":true}"""))
        assertEquals(AssistantAction.FindNearby("hotel"), AssistantActions.fromJson("""{"action":"find_nearby","concept":"hotel"}"""))
        assertEquals(AssistantAction.SetVoiceMode(VoiceMode.SMART_MIXED), AssistantActions.fromJson("""{"action":"set_voice_mode","mode":"smart_mixed"}"""))
        assertEquals(AssistantAction.SetVoiceLanguage("ar-QA"), AssistantActions.fromJson("""{"action":"set_voice_language","language":"ar-qa"}"""))
        assertEquals(AssistantAction.NavigateTo("Katara"), AssistantActions.fromJson("""{"action":"navigate_to","query":" Katara "}"""))
        assertEquals(AssistantAction.NavigateHome, AssistantActions.fromJson("""{"action":"navigate_home"}"""))
    }

    @Test fun rejectsEverythingElse() {
        // Not on the list, however plausible it sounds.
        assertNull(AssistantActions.fromJson("""{"action":"run_code","code":"rm -rf /"}"""))
        assertNull(AssistantActions.fromJson("""{"action":"disable_speed_warnings"}"""))
        assertNull(AssistantActions.fromJson("""{"action":"none"}"""))
        // Listed, but with a missing, mistyped or unknown parameter.
        assertNull(AssistantActions.fromJson("""{"action":"set_3d"}"""))
        assertNull(AssistantActions.fromJson("""{"action":"set_3d","enabled":"yes"}"""))
        assertNull(AssistantActions.fromJson("""{"action":"find_nearby","concept":"casino"}"""))
        assertNull(AssistantActions.fromJson("""{"action":"set_voice_language","language":"tlh-KLI"}"""))
        assertNull(AssistantActions.fromJson("""{"action":"navigate_to","query":"${"x".repeat(200)}"}"""))
        assertNull(AssistantActions.fromJson("""{"action":"navigate_to","query":"a\nb"}"""))
        // Not JSON at all.
        assertNull(AssistantActions.fromJson("Sure! I'll switch to 3D."))
        assertNull(AssistantActions.fromJson("""["set_3d"]"""))
    }

    @Test fun promptOffersExactlyTheValidatedActions() {
        val prompt = AssistantActions.systemPrompt()
        assertEquals(13, AssistantActions.CATALOGUE.size)
        for ((name, _) in AssistantActions.CATALOGUE) assertTrue(name, prompt.contains("- $name:"))
        assertTrue(prompt.contains("Never invent other actions"))
    }
}

class AlternativeRoutesTest {
    private fun resource(name: String) = Thread.currentThread().contextClassLoader!!.getResourceAsStream(name)!!.readBytes().toString(Charsets.UTF_8)

    @Test fun parsesRealValhallaAlternatives() {
        val routes = ValhallaRoutingProvider.parseAll(resource("valhalla_alternates.json"), TravelMode.DRIVING, "Valhalla")
        assertEquals(3, routes.size)
        // The main route and both alternatives are complete, distinct routes between the same two points.
        assertEquals(3, routes.map { Math.round(it.distanceM) }.distinct().size)
        for (r in routes) {
            assertTrue(r.geometry.size > 20)
            assertTrue(r.maneuvers.all { it.shapeIndex in r.geometry.indices })
            assertTrue(Geo.distanceM(r.geometry.first(), routes[0].geometry.first()) < 300)
            assertTrue(Geo.distanceM(r.geometry.last(), routes[0].geometry.last()) < 300)
        }
    }

    @Test fun aResponseWithoutAlternativesGivesOneRoute() {
        assertEquals(1, ValhallaRoutingProvider.parseAll(resource("valhalla_route.json"), TravelMode.DRIVING, "Valhalla").size)
    }

    @Test fun tollFreeOptionsUseValhallaCosting() {
        val o = ValhallaRoutingProvider.tollFreeOptions("auto")
        assertEquals(0.0, o.getAsJsonObject("auto").get("use_tolls").asDouble, 0.0)
    }
}

class GlobeConfigTest {
    @Test fun passesImageryAndOptionalToken() {
        val without = JsonParser.parseString(globeConfig(SatelliteCatalog.SENTINEL2, GeoPoint(25.3, 51.5), 9000.0, "")).asJsonObject
        assertEquals(SatelliteCatalog.SENTINEL2.tileUrl, without.get("imageryUrl").asString)
        assertEquals(14, without.get("imageryMaxZoom").asInt)
        assertEquals(25.3, without.get("lat").asDouble, 1e-9)
        assertFalse(without.has("ionToken"))
        val with = JsonParser.parseString(globeConfig(SatelliteCatalog.SENTINEL2, GeoPoint(25.3, 51.5), 9000.0, " tok ")).asJsonObject
        assertEquals("tok", with.get("ionToken").asString)
    }

    @Test fun cameraHeightFollowsMapZoom() {
        assertTrue(globeHeightForZoom(15.0) < globeHeightForZoom(10.0))
        assertEquals(15_000_000.0, globeHeightForZoom(0.0), 0.0)
        assertEquals(800.0, globeHeightForZoom(22.0), 0.0)
    }
}

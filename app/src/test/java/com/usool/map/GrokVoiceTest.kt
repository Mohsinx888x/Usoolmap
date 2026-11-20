package com.usool.map

import com.google.gson.JsonParser
import com.usool.map.voice.ArabicText
import com.usool.map.voice.GrokJokeClient
import com.usool.map.voice.GrokVoiceClient
import com.usool.map.voice.VoiceCache
import com.usool.map.voice.VoiceLanguages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GrokVoiceTest {
    @Test fun gulfArabicUsesTheGulfSpeechSetting() {
        assertEquals("ar-AE", GrokVoiceClient.ttsLanguage("ar-QA"))
        assertEquals("ar-AE", GrokVoiceClient.ttsLanguage("ar-AE"))
        assertEquals("ar-SA", GrokVoiceClient.ttsLanguage("ar-SA"))
        assertEquals("ar-EG", GrokVoiceClient.ttsLanguage("ar-EG"))
        assertEquals("en", GrokVoiceClient.ttsLanguage("en-GB"))
        assertEquals("pt-BR", GrokVoiceClient.ttsLanguage("pt-BR"))
        assertEquals("es-ES", GrokVoiceClient.ttsLanguage("es-AR"))
        assertEquals("zh", GrokVoiceClient.ttsLanguage("zh-CN"))
        // Not on xAI's list: let the service detect it rather than send a code it rejects.
        assertEquals("auto", GrokVoiceClient.ttsLanguage("ur-PK"))
        assertEquals("auto", GrokVoiceClient.ttsLanguage("fil-PH"))
    }

    @Test fun buildsTheDocumentedRequest() {
        val body = JsonParser.parseString(GrokVoiceClient.requestBody("لِفّ يَمِين", "ar-QA", "leo")).asJsonObject
        assertEquals("لِفّ يَمِين", body.get("text").asString)
        assertEquals("leo", body.get("voice_id").asString)
        assertEquals("ar-AE", body.get("language").asString)
        assertEquals("mp3", body.getAsJsonObject("output_format").get("codec").asString)
        assertEquals(24000, body.getAsJsonObject("output_format").get("sample_rate").asInt)
        assertTrue(GrokVoiceClient.DEFAULT_VOICE in GrokVoiceClient.VOICES)
    }

    @Test fun measuresTashkeel() {
        val vowelled = "بَعْدَ مِيتَيْن مِتِر لِفّ يَمِين عَلَى شَارِع الْكُورْنِيش"
        val bare = "بعد ميتين متر لف يمين على شارع الكورنيش"
        assertTrue(ArabicText.tashkeelRatio(vowelled) > 0.6)
        assertEquals(0.0, ArabicText.tashkeelRatio(bare), 0.0)
        assertTrue(ArabicText.isVowelled(vowelled))
        assertFalse(ArabicText.isVowelled(bare))
        assertFalse(ArabicText.isVowelled("بَعد ميتين متر لف يمين"))
        assertTrue(ArabicText.isArabic(bare))
        assertFalse(ArabicText.isArabic("Turn right"))
    }

    @Test fun asksForGulfDialectWithTashkeelOnlyWhereItApplies() {
        val qatar = GrokJokeClient.systemPrompt(VoiceLanguages.forTag("ar-QA"), strict = false)
        assertTrue(qatar.contains("Khaleeji"))
        assertTrue(qatar.contains("full tashkeel"))
        assertFalse(qatar.contains("previous answer"))
        assertTrue(GrokJokeClient.systemPrompt(VoiceLanguages.forTag("ar-QA"), strict = true).contains("previous answer was missing tashkeel"))
        val egypt = GrokJokeClient.systemPrompt(VoiceLanguages.forTag("ar-EG"), strict = false)
        assertFalse(egypt.contains("Khaleeji"))
        assertTrue(egypt.contains("full tashkeel"))
        val english = GrokJokeClient.systemPrompt(VoiceLanguages.forTag("en-US"), strict = false)
        assertFalse(english.contains("tashkeel"))
    }

    @Test fun acceptsVowelledArabicRepliesDespiteTheirLength() {
        val line = "يَا طَوِيل الْعُمُر، بَعْدَ مِيتَيْن مِتِر لِفّ يَمِين عَلَى شَارِع الْكُورْنِيش، وَلَا تْسَابِق النَّوَارِس!"
        val json = com.google.gson.JsonObject().apply {
            add("choices", com.google.gson.JsonArray().apply { add(com.google.gson.JsonObject().apply { add("message", com.google.gson.JsonObject().apply { addProperty("content", line) }) }) })
        }.toString()
        assertEquals(line, GrokJokeClient.parseReply(json))
    }

    @Test fun changingTheVoiceDoesNotReplayOldClips() {
        val cache = VoiceCache(Files.createTempDirectory("v").toFile())
        val ar = VoiceLanguages.forTag("ar-QA")
        assertNotEquals(cache.key("x", ar, "grok:leo"), cache.key("x", ar, "grok:ara"))
        assertNotEquals(cache.key("x", ar, "grok:leo"), cache.key("x", ar))
        assertEquals(cache.key("x", ar, "grok:leo"), cache.key("x", ar, "grok:leo"))
    }
}

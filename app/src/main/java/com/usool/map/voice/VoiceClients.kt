package com.usool.map.voice

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.usool.map.core.Http

enum class VoiceMode(val label: String, val description: String) {
    PROFESSIONAL("Professional", "Clear turn-by-turn guidance only. Works offline."),
    FUNNY("Funny", "Professional guidance plus humorous reactions to what happens on the road."),
    SMART_MIXED("Smart mixed", "Guidance only near junctions; jokes are kept for quiet stretches."),
    MUTED("Muted", "No voice."),
}

/** A guidance language and regional dialect. [humourStyle] describes the humour asked for when jokes are generated on the phone. */
data class VoiceLanguage(val tag: String, val label: String, val humourStyle: String)

object VoiceLanguages {
    val all = listOf(
        VoiceLanguage("en-US", "English (US)", "dry American road-trip banter"),
        VoiceLanguage("en-GB", "English (UK)", "understated British wit"),
        VoiceLanguage("en-IN", "English (India)", "light Indian-English commuter humour"),
        VoiceLanguage("ar-QA", "العربية (قطر)", "friendly Qatari humour in Gulf (Khaleeji) dialect"),
        VoiceLanguage("ar-SA", "العربية (السعودية)", "warm Saudi humour in Gulf dialect"),
        VoiceLanguage("ar-AE", "العربية (الإمارات)", "light Emirati humour in Gulf dialect"),
        VoiceLanguage("ar-KW", "العربية (الكويت)", "quick Kuwaiti humour in Gulf dialect"),
        VoiceLanguage("ar-EG", "العربية (مصر)", "warm Egyptian Arabic humour"),
        VoiceLanguage("hi-IN", "हिन्दी", "playful Hindi humour"),
        VoiceLanguage("ur-PK", "اردو", "polite, witty Urdu humour"),
        VoiceLanguage("fr-FR", "Français", "light French irony"),
        VoiceLanguage("es-ES", "Español", "cheerful Spanish humour"),
        VoiceLanguage("de-DE", "Deutsch", "deadpan German humour"),
        VoiceLanguage("tr-TR", "Türkçe", "good-natured Turkish humour"),
        VoiceLanguage("id-ID", "Bahasa Indonesia", "relaxed Indonesian humour"),
        VoiceLanguage("fil-PH", "Filipino", "upbeat Filipino humour"),
        VoiceLanguage("ru-RU", "Русский", "wry Russian humour"),
        VoiceLanguage("zh-CN", "中文", "light Mandarin humour"),
        VoiceLanguage("ja-JP", "日本語", "gentle Japanese humour"),
        VoiceLanguage("pt-BR", "Português (Brasil)", "easy-going Brazilian humour"),
        VoiceLanguage("bn-BD", "বাংলা", "friendly Bengali humour"),
    )

    fun forTag(tag: String): VoiceLanguage =
        all.firstOrNull { it.tag.equals(tag, true) }
            ?: all.firstOrNull { it.tag.substringBefore('-').equals(tag.substringBefore('-'), true) }
            ?: all.first()
}

/** Helpers for Arabic text that will be read aloud. */
object ArabicText {
    private val LETTER = Regex("[\\u0621-\\u063A\\u0641-\\u064A]")
    private val MARK = Regex("[\\u064B-\\u0652]")

    fun isArabic(text: String): Boolean = LETTER.containsMatchIn(text)

    /**
     * Share of Arabic letters that carry a vowel mark (tashkeel). Fully vowelled text scores
     * around 0.6 or more, since long vowels and word endings often take none.
     */
    fun tashkeelRatio(text: String): Double {
        val letters = LETTER.findAll(text).count()
        return if (letters == 0) 0.0 else MARK.findAll(text).count().toDouble() / letters
    }

    /** True when the text is vowelled enough for a speech engine to pronounce it unambiguously. */
    fun isVowelled(text: String): Boolean = tashkeelRatio(text) >= 0.4
}

/**
 * Writes a humorous reaction to a driving situation with Grok, on the phone. Used only when no
 * cloud backend is configured; the backend does the same work server-side.
 *
 * The line never contains the instruction itself (directions, distances, street names): those
 * are always spoken by the professional voice. Arabic lines are requested with full tashkeel and
 * asked for once more if they come back unvowelled.
 */
class GrokJokeClient(private val http: Http, private val keySource: () -> String, private val model: String) {
    constructor(http: Http, apiKey: String, model: String) : this(http, { apiKey }, model)

    private val apiKey: String get() = keySource().trim()
    val configured: Boolean get() = apiKey.isNotBlank()

    /** [avoid] lists recent jokes for the same situation so the new one differs from them. */
    suspend fun reaction(situation: String, language: VoiceLanguage, humourLevel: Int, avoid: List<String>): String? {
        if (!configured) return null
        val first = ask(reactionPrompt(language, humourLevel, avoid, strict = false), situation) ?: return null
        if (!language.tag.startsWith("ar") || ArabicText.isVowelled(first)) return first
        return ask(reactionPrompt(language, humourLevel, avoid, strict = true), situation)?.takeIf { ArabicText.isVowelled(it) } ?: first
    }

    /** One question to Grok with [system] as its instructions; null when there is no key or the call fails. */
    suspend fun ask(system: String, user: String): String? {
        if (!configured) return null
        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 1.0)
            addProperty("max_tokens", 300)
            add("messages", JsonArray().apply {
                add(JsonObject().apply { addProperty("role", "system"); addProperty("content", system) })
                add(JsonObject().apply { addProperty("role", "user"); addProperty("content", user) })
            })
        }
        val text = try {
            http.postString("https://api.x.ai/v1/chat/completions", body.toString(), headers = mapOf("Authorization" to "Bearer $apiKey"))
        } catch (e: Exception) {
            return null
        }
        return parseReply(text)
    }

    companion object {
        fun reactionPrompt(language: VoiceLanguage, humourLevel: Int, avoid: List<String>, strict: Boolean): String {
            val tone = when (humourLevel.coerceIn(1, 3)) { 1 -> "gentle and warm"; 2 -> "playful"; else -> "cheeky but kind" }
            val base = "You are the witty companion voice of a car navigation app. The user message describes what just happened on the drive. " +
                "Reply with ONE short original spoken joke about it (at most 22 words) in the language ${language.tag}, using ${language.humourStyle}; tone: $tone. " +
                "Write humour that is natural in that language and culture, not a translation of an English joke. " +
                "Do NOT give directions and do NOT mention distances, street names or numbers: the navigation voice does that. " +
                "Family-friendly: nothing about religion, politics, nationality, gender or stereotypes, and never encourage speeding or unsafe driving. Reply with the sentence only."
            val avoidText = if (avoid.isEmpty()) "" else " It must be clearly different from these earlier jokes and must not start with the same words: " + avoid.take(12).joinToString(" | ")
            if (!language.tag.startsWith("ar")) return base + avoidText
            val dialect = if (language.tag in GULF_TAGS) {
                " Write in Gulf (Khaleeji) Arabic as spoken in Qatar and the Gulf, with Gulf words and phrasing, not Modern Standard Arabic and not Egyptian or Levantine."
            } else ""
            val tashkeel = " Write every Arabic word with full tashkeel (fatha, damma, kasra, sukun, shadda, tanween) that matches how the word is pronounced in this dialect, so that a speech engine reads it correctly."
            val again = if (strict) " Your previous answer was missing tashkeel. Every single word must carry its vowel marks." else ""
            return base + dialect + tashkeel + again + avoidText
        }

        private val GULF_TAGS = setOf("ar-QA", "ar-AE", "ar-SA", "ar-KW", "ar-BH", "ar-OM")

        fun parseReply(json: String): String? {
            val line = runCatching {
                JsonParser.parseString(json).asJsonObject.getAsJsonArray("choices")[0].asJsonObject
                    .getAsJsonObject("message").get("content").asString
            }.getOrNull()?.trim()?.trim('"', '“', '”') ?: return null
            // Vowel marks roughly double the length of Arabic text, so the limit allows for them.
            return line.takeIf { it.isNotBlank() && it.length <= 480 }
        }
    }
}

/**
 * Speaks text with xAI's Grok text-to-speech (`POST /v1/tts`). Any Grok voice can speak any
 * supported language; Gulf Arabic uses the `ar-AE` or `ar-SA` language setting.
 * Returns MP3 bytes, or null on any failure.
 */
class GrokVoiceClient(private val http: Http, private val keySource: () -> String) {
    private val apiKey: String get() = keySource().trim()
    val configured: Boolean get() = apiKey.isNotBlank()

    suspend fun synthesize(text: String, language: VoiceLanguage, voiceId: String): ByteArray? {
        if (!configured) return null
        return try {
            http.postBytes("https://api.x.ai/v1/tts", requestBody(text, language.tag, voiceId), "application/json", mapOf("Authorization" to "Bearer $apiKey"))
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** Built-in voices offered in Settings. The full list is at `GET /v1/tts/voices`. */
        val VOICES = listOf("ara", "eve", "leo", "rex", "sal", "atlas", "orion", "luna")
        const val DEFAULT_VOICE = "leo"

        private val SUPPORTED = setOf("en", "ar-EG", "ar-SA", "ar-AE", "bn", "zh", "fr", "de", "hi", "id", "it", "ja", "ko", "pt-BR", "pt-PT", "ru", "es-MX", "es-ES", "tr", "vi")

        /** Maps an app language tag onto the codes the TTS API accepts; `auto` when there is none. */
        fun ttsLanguage(tag: String): String {
            if (tag in SUPPORTED) return tag
            val base = tag.substringBefore('-').lowercase()
            return when {
                // Gulf countries without their own code use the UAE (Gulf dialect) setting.
                tag in setOf("ar-QA", "ar-KW", "ar-BH", "ar-OM") -> "ar-AE"
                base == "ar" -> "ar-SA"
                base == "es" -> "es-ES"
                base == "pt" -> "pt-BR"
                base in SUPPORTED -> base
                else -> "auto"
            }
        }

        fun requestBody(text: String, languageTag: String, voiceId: String): String = JsonObject().apply {
            addProperty("text", text)
            addProperty("voice_id", voiceId)
            addProperty("language", ttsLanguage(languageTag))
            add("output_format", JsonObject().apply {
                addProperty("codec", "mp3")
                addProperty("sample_rate", 24000)
                addProperty("bit_rate", 64000)
            })
        }.toString()
    }
}

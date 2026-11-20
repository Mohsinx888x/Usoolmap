package com.usool.map.voice

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.usool.map.core.Http
import java.io.File
import java.security.MessageDigest

enum class VoiceMode(val label: String) {
    PROFESSIONAL("Professional"),
    HUMOROUS("Fun (Grok AI voice)"),
    MUTED("Muted"),
}

/** A guidance language with the Azure neural voice used for the humorous mode. */
data class VoiceLanguage(val tag: String, val label: String, val azureVoice: String, val humourStyle: String)

object VoiceLanguages {
    val all = listOf(
        VoiceLanguage("en-US", "English (US)", "en-US-AndrewMultilingualNeural", "dry American road-trip banter"),
        VoiceLanguage("en-GB", "English (UK)", "en-GB-RyanNeural", "understated British wit"),
        VoiceLanguage("en-IN", "English (India)", "en-IN-PrabhatNeural", "light Indian-English commuter humour"),
        VoiceLanguage("ar-QA", "العربية (قطر)", "ar-QA-MoazNeural", "friendly Gulf Arabic humour, in Khaleeji dialect"),
        VoiceLanguage("ar-EG", "العربية (مصر)", "ar-EG-ShakirNeural", "warm Egyptian Arabic humour"),
        VoiceLanguage("hi-IN", "हिन्दी", "hi-IN-MadhurNeural", "playful Hindi humour"),
        VoiceLanguage("ur-PK", "اردو", "ur-PK-AsadNeural", "polite, witty Urdu humour"),
        VoiceLanguage("fr-FR", "Français", "fr-FR-HenriNeural", "light French irony"),
        VoiceLanguage("es-ES", "Español", "es-ES-AlvaroNeural", "cheerful Spanish humour"),
        VoiceLanguage("de-DE", "Deutsch", "de-DE-ConradNeural", "deadpan German humour"),
        VoiceLanguage("tr-TR", "Türkçe", "tr-TR-AhmetNeural", "good-natured Turkish humour"),
        VoiceLanguage("id-ID", "Bahasa Indonesia", "id-ID-ArdiNeural", "relaxed Indonesian humour"),
        VoiceLanguage("fil-PH", "Filipino", "fil-PH-AngeloNeural", "upbeat Filipino humour"),
        VoiceLanguage("ru-RU", "Русский", "ru-RU-DmitryNeural", "wry Russian humour"),
        VoiceLanguage("zh-CN", "中文", "zh-CN-YunxiNeural", "light Mandarin humour"),
        VoiceLanguage("ja-JP", "日本語", "ja-JP-KeitaNeural", "gentle Japanese humour"),
        VoiceLanguage("pt-BR", "Português (Brasil)", "pt-BR-AntonioNeural", "easy-going Brazilian humour"),
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
 * Asks Grok to restate one navigation instruction with a short joke. The instruction's meaning
 * (direction, distance, street) must survive, so the reply is rejected when it is too long or
 * empty and the caller falls back to the professional wording.
 *
 * Arabic lines are requested with full tashkeel: unvowelled Arabic is ambiguous, and a speech
 * engine guessing the vowels is what makes dialect sound wrong. A reply that comes back
 * unvowelled is asked for once more before it is accepted as it is.
 */
class GrokJokeClient(private val http: Http, private val keySource: () -> String, private val model: String) {
    constructor(http: Http, apiKey: String, model: String) : this(http, { apiKey }, model)

    private val apiKey: String get() = keySource().trim()
    val configured: Boolean get() = apiKey.isNotBlank()

    suspend fun humorousLine(instruction: String, language: VoiceLanguage): String? {
        if (!configured) return null
        val first = ask(systemPrompt(language, strict = false), instruction) ?: return null
        if (!language.tag.startsWith("ar") || ArabicText.isVowelled(first)) return first
        return ask(systemPrompt(language, strict = true), instruction)?.takeIf { ArabicText.isVowelled(it) } ?: first
    }

    private suspend fun ask(system: String, instruction: String): String? {
        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0.9)
            addProperty("max_tokens", 300)
            add("messages", JsonArray().apply {
                add(JsonObject().apply { addProperty("role", "system"); addProperty("content", system) })
                add(JsonObject().apply { addProperty("role", "user"); addProperty("content", instruction) })
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
        fun systemPrompt(language: VoiceLanguage, strict: Boolean): String {
            val base = "You are the voice of a car navigation app. Rewrite the driving instruction you are given as ONE short spoken " +
                "sentence (at most 25 words) in the language ${language.tag}, using ${language.humourStyle}. Keep the direction, distance and " +
                "street name exactly as given. Family-friendly: no insults, politics, religion, stereotypes or anything that distracts from " +
                "safe driving. Reply with the sentence only."
            if (!language.tag.startsWith("ar")) return base
            val dialect = if (language.tag in GULF_TAGS) {
                " Write in Gulf (Khaleeji) Arabic as spoken in Qatar and the Gulf, with Gulf words and phrasing (for example يَمِين، يَسَار، سِيدَه، لِفّ، الْحِين، شْوَيّ، دَوَّار), not Modern Standard Arabic and not Egyptian or Levantine."
            } else ""
            val tashkeel = " Write every Arabic word with full tashkeel (fatha, damma, kasra, sukun, shadda, tanween) that matches how the word is pronounced in this dialect, so that a speech engine reads it correctly. Write numbers as vowelled Arabic words, not digits."
            val again = if (strict) " Your previous answer was missing tashkeel. Every single word must carry its vowel marks." else ""
            return base + dialect + tashkeel + again
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

/** Synthesises speech with Azure AI Speech (REST). Returns MP3 bytes, or null on any failure. */
class AzureSpeechClient(private val http: Http, private val key: String, private val region: String) {
    val configured: Boolean get() = key.isNotBlank() && region.isNotBlank()

    suspend fun synthesize(text: String, language: VoiceLanguage): ByteArray? {
        if (!configured) return null
        return try {
            http.postBytes(
                "https://$region.tts.speech.microsoft.com/cognitiveservices/v1",
                ssml(text, language),
                "application/ssml+xml",
                mapOf("Ocp-Apim-Subscription-Key" to key, "X-Microsoft-OutputFormat" to "audio-24khz-48kbitrate-mono-mp3"),
            ).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        fun ssml(text: String, language: VoiceLanguage): String {
            val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
            return "<speak version='1.0' xml:lang='${language.tag}'><voice name='${language.azureVoice}'>$escaped</voice></speak>"
        }
    }
}

/**
 * Disk cache of synthesised humorous lines, keyed by language, voice and the instruction they
 * replace. Cached clips play instantly and keep working with no connection.
 */
class VoiceCache(private val dir: File, private val maxBytes: Long = 64L * 1024 * 1024) {
    init { dir.mkdirs() }

    /** [voice] identifies the engine and voice, so changing the voice does not replay old clips. */
    fun key(instruction: String, language: VoiceLanguage, voice: String = language.azureVoice): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("${language.tag}|$voice|$instruction".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(40)
    }

    fun get(key: String): File? = File(dir, "$key.mp3").takeIf { it.isFile && it.length() > 0 }?.also { it.setLastModified(System.currentTimeMillis()) }

    fun put(key: String, audio: ByteArray): File {
        val tmp = File(dir, "$key.tmp")
        tmp.writeBytes(audio)
        val out = File(dir, "$key.mp3")
        tmp.renameTo(out)
        trim()
        return out
    }

    val sizeBytes: Long get() = dir.listFiles()?.sumOf { it.length() } ?: 0
    val clipCount: Int get() = dir.listFiles { f -> f.name.endsWith(".mp3") }?.size ?: 0

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    /** Drops the least recently played clips once the cache exceeds [maxBytes]. */
    private fun trim() {
        val files = dir.listFiles { f -> f.name.endsWith(".mp3") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }
}

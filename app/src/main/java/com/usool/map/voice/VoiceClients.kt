package com.usool.map.voice

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.usool.map.core.Http
import java.io.File
import java.security.MessageDigest

enum class VoiceMode(val label: String) {
    PROFESSIONAL("Professional"),
    HUMOROUS("Humorous (AI)"),
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

/**
 * Asks the Grok API to restate one navigation instruction with a short joke. The instruction's
 * meaning (direction, distance, street) must survive, so the reply is rejected when it is too
 * long or empty and the caller falls back to the professional wording.
 */
class GrokJokeClient(private val http: Http, private val apiKey: String, private val model: String) {
    val configured: Boolean get() = apiKey.isNotBlank()

    suspend fun humorousLine(instruction: String, language: VoiceLanguage): String? {
        if (!configured) return null
        val system = "You are the voice of a car navigation app. Rewrite the driving instruction you are given as ONE short spoken " +
            "sentence (at most 25 words) in the language ${language.tag}, using ${language.humourStyle}. Keep the direction, distance and " +
            "street name exactly as given. Family-friendly: no insults, politics, religion, stereotypes or anything that distracts from " +
            "safe driving. Reply with the sentence only."
        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0.9)
            addProperty("max_tokens", 120)
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
        fun parseReply(json: String): String? {
            val line = runCatching {
                JsonParser.parseString(json).asJsonObject.getAsJsonArray("choices")[0].asJsonObject
                    .getAsJsonObject("message").get("content").asString
            }.getOrNull()?.trim()?.trim('"', '“', '”') ?: return null
            return line.takeIf { it.isNotBlank() && it.length <= 320 }
        }
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

    fun key(instruction: String, language: VoiceLanguage): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("${language.tag}|${language.azureVoice}|$instruction".toByteArray())
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

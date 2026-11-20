package com.usool.map.voice.humour

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.usool.map.core.Http
import com.usool.map.core.HttpException
import java.io.File
import java.net.URLEncoder
import java.time.Instant

data class CloudClip(
    val id: String, val event: String, val language: String, val personality: String, val humourLevel: Int,
    val text: String, val bytes: Long, val voice: String?, val fallbackVoice: Boolean, val audioUrl: String,
)

data class Personality(val id: String, val label: String, val voice: String?, val gender: String?, val description: String?)

data class VoicesInfo(val requestedLanguage: String, val language: String, val fallback: Boolean, val fallbackNote: String?, val personalities: List<Personality>)

class NextClips(val clips: List<CloudClip>, val generating: Int, val quotaRemaining: Int)

class CloudVoiceException(val code: String, message: String) : Exception(message)

/** Where the app keeps its identity for the backend. */
interface CloudCredentials {
    val baseUrl: String
    val appKey: String
    val installId: String
    var deviceToken: String
}

/**
 * Client for the UsoolMap voice backend on Azure (see `backend/API.md`). The phone never talks to
 * Grok or Azure Speech through this path: it only asks the backend for clips and downloads them.
 */
class CloudVoiceClient(private val http: Http, private val credentials: CloudCredentials) {
    val configured: Boolean get() = credentials.baseUrl.isNotBlank()
    private val base: String get() = credentials.baseUrl.trim().trimEnd('/')

    private suspend fun token(): String {
        credentials.deviceToken.takeIf { it.isNotBlank() }?.let { return it }
        val body = JsonObject().apply { addProperty("installId", credentials.installId) }.toString()
        val reply = call { http.postString("$base/v1/devices/register", body, headers = mapOf("X-App-Key" to credentials.appKey)) }
        return JsonParser.parseString(reply).asJsonObject.get("token").asString.also { credentials.deviceToken = it }
    }

    private suspend fun auth(): Map<String, String> = mapOf("Authorization" to "Bearer ${token()}")

    /** Runs a request; a rejected token is forgotten and the request retried once with a new one. */
    private suspend fun <T> authed(block: suspend (Map<String, String>) -> T): T = try {
        call { block(auth()) }
    } catch (e: CloudVoiceException) {
        if (e.code != "unauthorized") throw e
        credentials.deviceToken = ""
        call { block(auth()) }
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        throw CloudVoiceException(
            when (e.code) { 401 -> "unauthorized"; 429 -> "limit_reached"; 503 -> "unavailable"; else -> "http_${e.code}" },
            when (e.code) {
                401 -> "The voice service rejected this app's key."
                429 -> "The daily limit for new jokes has been reached. Saved jokes keep playing."
                503 -> "New jokes cannot be generated right now. Saved jokes keep playing."
                else -> "The voice service answered with an error (${e.code})."
            },
        )
    } catch (e: CloudVoiceException) {
        throw e
    } catch (e: Exception) {
        throw CloudVoiceException("offline", "The voice service could not be reached.")
    }

    suspend fun voices(language: String): VoicesInfo {
        val o = JsonParser.parseString(authed { http.getString("$base/v1/voices?language=${enc(language)}", it) }).asJsonObject
        return VoicesInfo(
            requestedLanguage = o.get("requestedLanguage").asString,
            language = o.get("language").asString,
            fallback = o.get("fallback")?.asBoolean ?: false,
            fallbackNote = o.get("fallbackNote")?.takeIf { !it.isJsonNull }?.asString,
            personalities = o.getAsJsonArray("personalities").map { p ->
                val x = p.asJsonObject
                Personality(x.get("id").asString, x.get("label").asString, x.get("voice")?.asString, x.get("gender")?.asString, x.get("description")?.takeIf { !it.isJsonNull }?.asString)
            },
        )
    }

    suspend fun starterPack(language: String, personality: String, humourLevel: Int): List<CloudClip> {
        val url = "$base/v1/packs/starter?language=${enc(language)}&personality=${enc(personality)}&humourLevel=$humourLevel"
        return parseClips(JsonParser.parseString(authed { http.getString(url, it) }).asJsonObject.getAsJsonArray("clips"))
    }

    suspend fun next(events: List<NavEvent>, language: String, personality: String, humourLevel: Int, have: List<String>, want: Int): NextClips {
        val body = selection(language, personality, humourLevel).apply {
            add("eventTypes", JsonArray().apply { events.forEach { add(it.wire) } })
            add("have", JsonArray().apply { have.takeLast(500).forEach { add(it) } })
            addProperty("want", want.coerceIn(1, 20))
        }.toString()
        val o = JsonParser.parseString(authed { http.postString("$base/v1/clips/next", body, headers = it) }).asJsonObject
        return NextClips(parseClips(o.getAsJsonArray("clips")), o.get("generating")?.asInt ?: 0, o.get("quotaRemaining")?.asInt ?: 0)
    }

    /** Asks for brand-new jokes. Returns how many were queued. */
    suspend fun regenerate(language: String, personality: String, humourLevel: Int, count: Int): Int {
        val body = selection(language, personality, humourLevel).apply { addProperty("count", count.coerceIn(1, 10)) }.toString()
        return JsonParser.parseString(authed { http.postString("$base/v1/clips/regenerate", body, headers = it) }).asJsonObject.get("queued")?.asInt ?: 0
    }

    /** Downloads a clip's audio, continuing a partial file. */
    suspend fun download(clip: CloudClip, target: File, onBytes: (Long) -> Unit = {}) {
        val url = if (clip.audioUrl.startsWith("http")) clip.audioUrl else base + clip.audioUrl
        authed { headers -> http.downloadTo(url, target, headers, onBytes) }
    }

    suspend fun sendHistory(plays: List<PendingPlay>) {
        val body = JsonObject().apply {
            add("plays", JsonArray().apply {
                plays.take(200).forEach { p -> add(JsonObject().apply { addProperty("clipId", p.clipId); addProperty("playedAt", Instant.ofEpochMilli(p.playedAtMs).toString()) }) }
            })
        }.toString()
        authed { http.postString("$base/v1/history", body, headers = it) }
    }

    suspend fun reportPronunciation(clipId: String?, text: String, language: String, note: String) {
        val body = JsonObject().apply {
            if (clipId != null) addProperty("clipId", clipId)
            addProperty("text", text); addProperty("language", language); addProperty("note", note)
        }.toString()
        authed { http.postString("$base/v1/pronunciation/reports", body, headers = it) }
    }

    /** Asks the backend's assistant to turn a request into one action; returns the action as JSON text. */
    suspend fun assistant(text: String, language: String): String? {
        val body = JsonObject().apply { addProperty("text", text.take(300)); addProperty("language", language) }.toString()
        val o = JsonParser.parseString(authed { http.postString("$base/v1/assistant/command", body, headers = it) }).asJsonObject
        return o.get("action")?.takeIf { it.isJsonObject }?.toString()
    }

    /** Deletes this installation's history on the backend. */
    suspend fun deleteMyData() {
        authed { http.postString("$base/v1/me/delete", "{}", headers = it) }
        credentials.deviceToken = ""
    }

    private fun selection(language: String, personality: String, humourLevel: Int) = JsonObject().apply {
        addProperty("language", language); addProperty("personality", personality); addProperty("humourLevel", humourLevel)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        fun parseClips(array: JsonArray?): List<CloudClip> = array?.mapNotNull { el ->
            val o = el.asJsonObject
            runCatching {
                CloudClip(
                    id = o.get("id").asString, event = o.get("eventType").asString, language = o.get("language").asString,
                    personality = o.get("personality").asString, humourLevel = o.get("humourLevel").asInt, text = o.get("text").asString,
                    bytes = o.get("bytes")?.asLong ?: 0, voice = o.get("voice")?.takeIf { !it.isJsonNull }?.asString,
                    fallbackVoice = o.get("fallbackVoice")?.asBoolean ?: false, audioUrl = o.get("audioUrl").asString,
                )
            }.getOrNull()
        } ?: emptyList()
    }
}

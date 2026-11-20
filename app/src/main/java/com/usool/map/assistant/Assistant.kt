package com.usool.map.assistant

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.usool.map.search.PoiConcepts
import com.usool.map.search.TextNormalizer
import com.usool.map.voice.VoiceLanguages
import com.usool.map.voice.VoiceMode

/**
 * Everything the voice assistant is able to do. A spoken or typed request, and anything an AI
 * model returns, is turned into exactly one of these or rejected: the app never runs text from a
 * model, it only picks from this list and checks every parameter.
 */
sealed interface AssistantAction {
    data object NavigateHome : AssistantAction
    data class NavigateTo(val query: String) : AssistantAction
    /** Find a kind of place near the map's position; [conceptId] is one of `PoiConcepts`. */
    data class FindNearby(val conceptId: String) : AssistantAction
    data class Set3D(val enabled: Boolean) : AssistantAction
    data class SetSatellite(val enabled: Boolean) : AssistantAction
    data object OpenGlobe : AssistantAction
    data object ShowTraffic : AssistantAction
    data class AvoidTolls(val enabled: Boolean) : AssistantAction
    data class SetVoiceMode(val mode: VoiceMode) : AssistantAction
    data class SetVoiceLanguage(val tag: String) : AssistantAction
    data object DownloadOfflineMap : AssistantAction
    data object GenerateJokes : AssistantAction
    data object StopNavigation : AssistantAction
}

object AssistantActions {
    /** Names a model may use, with the parameter each one takes. Sent to the model as its only options. */
    val CATALOGUE = listOf(
        "navigate_home" to "no parameters",
        "navigate_to" to "query: the place or address to go to",
        "find_nearby" to "concept: one of ${PoiConcepts.all.joinToString(", ") { it.id }}",
        "set_3d" to "enabled: true or false",
        "set_satellite" to "enabled: true or false",
        "open_globe" to "no parameters",
        "show_traffic" to "no parameters",
        "avoid_tolls" to "enabled: true or false",
        "set_voice_mode" to "mode: one of professional, funny, smart_mixed, muted",
        "set_voice_language" to "language: one of ${VoiceLanguages.all.joinToString(", ") { it.tag }}",
        "download_offline_map" to "no parameters",
        "generate_jokes" to "no parameters",
        "stop_navigation" to "no parameters",
    )

    /**
     * Validates an action described as JSON, e.g. `{"action":"set_3d","enabled":true}`.
     * Returns null for anything that is not on the list or has a missing or invalid parameter.
     */
    fun fromJson(json: String): AssistantAction? {
        val o = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
        return fromJson(o)
    }

    fun fromJson(o: JsonObject): AssistantAction? {
        fun bool(name: String): Boolean? = o.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        fun text(name: String): String? = o.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()
        return when (text("action")) {
            "navigate_home" -> AssistantAction.NavigateHome
            "navigate_to" -> text("query")?.takeIf { it.length in 2..120 && '\n' !in it }?.let { AssistantAction.NavigateTo(it) }
            "find_nearby" -> text("concept")?.takeIf { id -> PoiConcepts.all.any { it.id == id } }?.let { AssistantAction.FindNearby(it) }
            "set_3d" -> bool("enabled")?.let { AssistantAction.Set3D(it) }
            "set_satellite" -> bool("enabled")?.let { AssistantAction.SetSatellite(it) }
            "open_globe" -> AssistantAction.OpenGlobe
            "show_traffic" -> AssistantAction.ShowTraffic
            "avoid_tolls" -> bool("enabled")?.let { AssistantAction.AvoidTolls(it) }
            "set_voice_mode" -> text("mode")?.let { m -> VoiceMode.entries.firstOrNull { it.name.equals(m, true) } }?.let { AssistantAction.SetVoiceMode(it) }
            "set_voice_language" -> text("language")?.let { t -> VoiceLanguages.all.firstOrNull { it.tag.equals(t, true) } }?.let { AssistantAction.SetVoiceLanguage(it.tag) }
            "download_offline_map" -> AssistantAction.DownloadOfflineMap
            "generate_jokes" -> AssistantAction.GenerateJokes
            "stop_navigation" -> AssistantAction.StopNavigation
            else -> null
        }
    }

    /** Instruction given to the model: choose one action from the list and answer with JSON only. */
    fun systemPrompt(): String = "You control a car navigation app. Turn the user's request into exactly one action from this list and reply with a single JSON object and nothing else, " +
        "for example {\"action\":\"set_3d\",\"enabled\":true}. If nothing on the list fits, reply {\"action\":\"none\"}. Never invent other actions. Actions:\n" +
        CATALOGUE.joinToString("\n") { "- ${it.first}: ${it.second}" }
}

/**
 * Understands the common commands directly on the phone, in English and Arabic, with no network
 * and no AI. Anything it does not recognise can be passed to the AI assistant.
 */
object LocalCommands {
    /** True when [q] (already normalised) contains any phrase; phrases are normalised the same way, keeping edge spaces. */
    private fun has(q: String, vararg words: String) = words.any { w ->
        val core = TextNormalizer.normalize(w)
        q.contains((if (w.startsWith(" ")) " " else "") + core + (if (w.endsWith(" ")) " " else ""))
    }

    fun match(text: String): AssistantAction? {
        val q = " " + TextNormalizer.normalize(text) + " "
        val off = has(q, " off ", "disable", "turn off", "stop ", "اطفي", "اطفئ", "اوقف", "وقف", "الغ") && !has(q, "stop navigation", "وقف الملاحه", "اوقف الملاحه")
        return when {
            has(q, "take me home", "go home", "navigate home", "drive home", "خذني البيت", "ودني البيت", "خذني للبيت", "الى البيت", "للبيت", "روح البيت") -> AssistantAction.NavigateHome
            has(q, "stop navigation", "end navigation", "cancel navigation", "cancel route", "وقف الملاحه", "اوقف الملاحه", "الغ المسار", "انه الملاحه") -> AssistantAction.StopNavigation
            has(q, "toll", "رسوم", "تعرفه") -> AssistantAction.AvoidTolls(!has(q, "allow toll", "use toll", "with toll", "اسمح"))
            has(q, "globe", "الكره الارضيه", "كره ارضيه") -> AssistantAction.OpenGlobe
            has(q, " 3d", "three d", "ثلاثي الابعاد", "ثلاثي") -> AssistantAction.Set3D(!off)
            has(q, " 2d", "two d", "ثنائي الابعاد", "ثنائي") -> AssistantAction.Set3D(false)
            has(q, "satellite", "قمر صناعي", "الاقمار الصناعيه", "صور الاقمار") -> AssistantAction.SetSatellite(!off)
            has(q, "traffic", "ازدحام", "زحمه", "حركه المرور", "المرور") -> AssistantAction.ShowTraffic
            has(q, "new jokes", "generate jokes", "more jokes", "نكت جديده", "نكات جديده", "ولد نكت", "جدد النكت") -> AssistantAction.GenerateJokes
            has(q, "offline map", "download the map", "download map", "خريطه بدون انترنت", "نزل الخريطه", "حمل الخريطه", "تنزيل الخريطه") -> AssistantAction.DownloadOfflineMap
            has(q, "voice", "صوت", "لهجه") && language(q) != null -> AssistantAction.SetVoiceLanguage(language(q)!!)
            has(q, "smart mixed", "smart mode", "الوضع الذكي", "ذكي") -> AssistantAction.SetVoiceMode(VoiceMode.SMART_MIXED)
            has(q, "funny", "humor", "humour", "jokes", "مضحك", "فكاهي", "نكت") -> AssistantAction.SetVoiceMode(if (off) VoiceMode.PROFESSIONAL else VoiceMode.FUNNY)
            has(q, "professional", "serious", "احترافي", "رسمي", "جدي") -> AssistantAction.SetVoiceMode(VoiceMode.PROFESSIONAL)
            has(q, "mute", "silent", "be quiet", "صامت", "اسكت", "كتم") -> AssistantAction.SetVoiceMode(VoiceMode.MUTED)
            has(q, "nearest", "nearby", "closest", "near me", "find a ", "find the ", "اقرب", "قريب", "ابحث عن", "دور على", "وين") ->
                PoiConcepts.all.firstOrNull { c -> c.triggers.any { t -> q.contains(" $t ") || (t.any { ch -> ch.code > 0x2E80 } && q.contains(t)) } }?.let { AssistantAction.FindNearby(it.id) }
            has(q, "take me to ", "navigate to ", "drive to ", "go to ", "directions to ", "خذني الى ", "ودني ", "خذني ل", "اتجه الى ", "روح الى ") -> destination(text)?.let { AssistantAction.NavigateTo(it) }
            else -> null
        }
    }

    private val LANGUAGE_WORDS = listOf(
        "ar-QA" to listOf("qatari", "قطري"), "ar-SA" to listOf("saudi", "سعودي"), "ar-AE" to listOf("emirati", "اماراتي"), "ar-KW" to listOf("kuwaiti", "كويتي"),
        "ar-EG" to listOf("egyptian", "مصري"), "ar-QA" to listOf("gulf", "khaleeji", "خليجي", "arabic", "عربي"),
        "en-GB" to listOf("british", "بريطاني"), "en-IN" to listOf("indian english"), "en-US" to listOf("american", "english", "انجليزي", "امريكي"),
        "hi-IN" to listOf("hindi", "هندي"), "ur-PK" to listOf("urdu", "اردو"), "fr-FR" to listOf("french", "فرنسي"), "es-ES" to listOf("spanish", "اسباني"),
        "de-DE" to listOf("german", "الماني"), "tr-TR" to listOf("turkish", "تركي"), "ru-RU" to listOf("russian", "روسي"), "zh-CN" to listOf("chinese", "صيني"),
        "ja-JP" to listOf("japanese", "ياباني"), "pt-BR" to listOf("portuguese", "برتغالي"), "id-ID" to listOf("indonesian", "اندونيسي"),
    )

    private fun language(q: String): String? = LANGUAGE_WORDS.firstOrNull { (_, words) -> words.any { q.contains(it) } }?.first

    /** The place named after "take me to", "navigate to" and similar. */
    private fun destination(text: String): String? {
        val m = Regex("(?i)(?:take me to|navigate to|drive to|go to|directions to|خذني إلى|خذني الى|ودني|اتجه إلى|اتجه الى|روح إلى|روح الى)\\s+(.+)").find(text.trim()) ?: return null
        return m.groupValues[1].trim().trimEnd('.', '!', '?', '؟').takeIf { it.length >= 2 }
    }
}

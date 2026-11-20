package com.usool.map.voice.humour

import com.usool.map.voice.GrokJokeClient
import com.usool.map.voice.GrokVoiceClient
import com.usool.map.voice.VoiceGuide
import com.usool.map.voice.VoiceLanguages
import com.usool.map.voice.VoiceMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PackStatus { IDLE, DOWNLOADING, PAUSED, FAILED, DONE }

data class HumourState(
    /** "cloud", "device" (jokes generated on the phone with an xAI key) or "none". */
    val source: String = "none",
    val packStatus: PackStatus = PackStatus.IDLE,
    val packFilesDone: Int = 0,
    val packFilesTotal: Int = 0,
    val packBytesDone: Long = 0,
    val packBytesTotal: Long = 0,
    val clipCount: Int = 0,
    val cacheBytes: Long = 0,
    val personalities: List<Personality> = emptyList(),
    /** Set when the requested dialect has no voice of its own and another one is used. */
    val fallbackNote: String? = null,
    val lastJoke: LocalClip? = null,
    val message: String? = null,
)

/** The user's choices, read each time they are needed so changes in Settings apply at once. */
interface HumourPreferences {
    val languageTag: String
    var personality: String
    val intensity: Int
    val frequency: Int
    /** Wall-clock time until which jokes are silenced; 0 when not paused. */
    var pausedUntilWallMs: Long
    /** When true the engine plays only what is already on the phone and makes no requests. */
    val offlineOnly: Boolean
    val deviceGrokVoice: String
    var deviceGenerationsToday: Int
    var deviceGenerationDay: String
}

/**
 * Plays humorous reactions during navigation and keeps the phone's clip library stocked.
 *
 * Playing never waits for the network: a clip is taken from the local library or nothing is
 * said. When the library has nothing new for an event, more clips are requested in the
 * background, from the Azure backend when one is configured or, failing that, generated on the
 * phone with the user's own xAI key, and they are ready for the next time the event happens.
 */
class HumourEngine(
    private val scope: CoroutineScope,
    private val voice: VoiceGuide,
    val library: ClipLibrary,
    private val cloud: CloudVoiceClient,
    private val grokText: GrokJokeClient,
    private val grokVoice: GrokVoiceClient,
    private val prefs: HumourPreferences,
    private val online: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(HumourState())
    val state: StateFlow<HumourState> = _state.asStateFlow()

    private var lastJokeAtMs: Long? = null
    private var packJob: Job? = null
    private val fetching = HashSet<String>()

    init { refreshCounts() }

    val source: String get() = when {
        cloud.configured -> "cloud"
        grokText.configured -> "device"
        else -> "none"
    }

    private val language: String get() = prefs.languageTag
    private val personality: String get() = if (source == "device") prefs.deviceGrokVoice else prefs.personality
    private val level: Int get() = prefs.intensity.coerceIn(1, 3)

    fun settings(): HumourSettings = HumourSettings(
        mode = when (voice.mode) { VoiceMode.FUNNY -> HumourMode.FUNNY; VoiceMode.SMART_MIXED -> HumourMode.SMART_MIXED; else -> HumourMode.OFF },
        intensity = level, frequency = prefs.frequency, pausedUntilMs = prefs.pausedUntilWallMs,
    )

    private fun refreshCounts() = _state.update { it.copy(source = source, clipCount = library.count, cacheBytes = library.sizeBytes) }

    /** Reacts to a navigation event if it is safe to; otherwise stays silent. */
    fun onEvent(event: NavEvent, facts: DriveFacts) {
        val settings = settings()
        if (settings.mode == HumourMode.OFF) return
        val decision = HumourPolicy.decide(event, facts.copy(nowMs = clock()), settings, lastJokeAtMs)
        if (!decision.allow) return
        val clip = library.pick(event, language, personality, level, clock())
        if (clip == null) {
            // Nothing new to say: say nothing now, and fetch something for next time.
            stock(listOf(event), want = 2)
            return
        }
        val now = clock()
        val played = voice.playHumour(library.file(clip).absolutePath) {
            lastJokeAtMs = now
            library.markPlayed(clip, now)
            _state.update { it.copy(lastJoke = clip) }
        }
        if (played && library.pick(event, language, personality, level, now + 1) == null) stock(listOf(event), want = 2)
    }

    /** Stops a joke at once; called when a manoeuvre comes close. */
    fun hush() = voice.scheduler.cancelHumour()

    /** At the start of a route: make sure there is something for the events most likely to happen. */
    fun prepareForRoute() {
        if (settings().mode == HumourMode.OFF) return
        val now = clock()
        val missing = NavEvent.entries.filter { library.pick(it, language, personality, level, now) == null }
        if (missing.isNotEmpty()) stock(missing, want = (missing.size).coerceAtMost(12))
        syncHistory()
    }

    /** Fetches clips for [events] in the background. Failures are silent: navigation is unaffected. */
    private fun stock(events: List<NavEvent>, want: Int) {
        if (prefs.offlineOnly || !online()) return
        // The backend needs a personality it knows; until the voice list has been loaded there is none.
        if (source == "cloud" && prefs.personality.isBlank()) { refreshVoices(); return }
        val key = events.joinToString(",") { it.wire } + "|$language|$personality|$level"
        synchronized(fetching) { if (!fetching.add(key)) return }
        scope.launch {
            try {
                when (source) {
                    "cloud" -> {
                        val reply = cloud.next(events, language, personality, level, library.ids(language, personality), want)
                        reply.clips.forEach { store(it) }
                    }
                    "device" -> events.take(3).forEach { generateOnDevice(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Leave it; the next event tries again.
            } finally {
                synchronized(fetching) { fetching.remove(key) }
                refreshCounts()
            }
        }
    }

    private suspend fun store(clip: CloudClip, onBytes: (Long) -> Unit = {}) {
        if (library.has(clip.id)) return
        val part = library.partFile(clip.id)
        cloud.download(clip, part, onBytes)
        val file = library.newFile(clip.id)
        if (!part.renameTo(file)) return
        library.add(LocalClip(clip.id, clip.event, clip.language, clip.personality, clip.humourLevel, clip.text, file.name, file.length(), "cloud", clip.voice, clip.fallbackVoice, clock()))
    }

    /** One joke written and spoken by Grok on the phone. Returns false when nothing was produced. */
    private suspend fun generateOnDevice(event: NavEvent): Boolean {
        val today = java.time.LocalDate.now().toString()
        if (prefs.deviceGenerationDay != today) { prefs.deviceGenerationDay = today; prefs.deviceGenerationsToday = 0 }
        if (prefs.deviceGenerationsToday >= DEVICE_DAILY_LIMIT) return false
        val lang = VoiceLanguages.forTag(language)
        val earlier = library.all().filter { it.event == event.wire && it.language == language }.sortedByDescending { it.addedAtMs }.map { it.text }
        repeat(2) {
            prefs.deviceGenerationsToday += 1
            val text = grokText.reaction(situation(event), lang, level, earlier) ?: return false
            if (library.isTooSimilar(text, event, language) || earlier.take(6).any { JokeText.opening(it) == JokeText.opening(text) }) return@repeat
            val audio = grokVoice.synthesize(text, lang, prefs.deviceGrokVoice) ?: return false
            val id = "dev-" + java.util.UUID.randomUUID()
            val file = library.newFile(id)
            file.writeBytes(audio)
            library.add(LocalClip(id, event.wire, language, prefs.deviceGrokVoice, level, text, file.name, file.length(), "device", "grok:${prefs.deviceGrokVoice}", false, clock()))
            return true
        }
        return false
    }

    // --- Voice pack ---

    /** Downloads the starter library for the current selection, or continues an interrupted download. */
    fun downloadPack() {
        if (packJob?.isActive == true) return
        if (source == "none") { say("Funny voice is not set up. Add the voice service address or an xAI key below."); return }
        if (!online()) { _state.update { it.copy(packStatus = PackStatus.FAILED, message = "Connect to the internet to download the voice pack.") }; return }
        _state.update { it.copy(packStatus = PackStatus.DOWNLOADING, message = null) }
        packJob = scope.launch {
            try {
                if (source == "cloud") downloadCloudPack() else generateDevicePack()
                refreshCounts()
                val s = _state.value
                _state.update { it.copy(packStatus = PackStatus.DONE, message = if (s.packFilesTotal == 0) "The cloud library has no clips for this voice yet. New ones are being generated; try again in a few minutes." else null) }
                if (source == "cloud" && s.packFilesTotal == 0) runCatching { cloud.regenerate(language, personality, level, 10) }
            } catch (e: CancellationException) {
                _state.update { it.copy(packStatus = PackStatus.PAUSED) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(packStatus = PackStatus.FAILED, message = e.message ?: "The download stopped. Tap Retry.") }
            }
        }
    }

    private suspend fun downloadCloudPack() {
        val clips = cloud.starterPack(language, personality, level)
        val total = clips.sumOf { it.bytes }
        var doneBytes = clips.filter { library.has(it.id) }.sumOf { it.bytes }
        var doneFiles = clips.count { library.has(it.id) }
        _state.update { it.copy(packFilesTotal = clips.size, packBytesTotal = total, packFilesDone = doneFiles, packBytesDone = doneBytes) }
        for (clip in clips) {
            if (library.has(clip.id)) continue
            store(clip) { onDisk -> _state.update { it.copy(packBytesDone = (doneBytes + onDisk).coerceAtMost(total)) } }
            doneBytes += clip.bytes
            doneFiles += 1
            _state.update { it.copy(packFilesDone = doneFiles, packBytesDone = doneBytes.coerceAtMost(total)) }
        }
    }

    /** Without a backend the starter pack is generated here, one joke per kind of event. */
    private suspend fun generateDevicePack() {
        val now = clock()
        val missing = NavEvent.entries.filter { library.pick(it, language, personality, level, now) == null }
        _state.update { it.copy(packFilesTotal = NavEvent.entries.size, packFilesDone = NavEvent.entries.size - missing.size, packBytesTotal = 0, packBytesDone = library.sizeBytes) }
        for (event in missing) {
            if (!generateOnDevice(event)) throw IllegalStateException("A joke could not be generated. Check the xAI key and its credit, or today's limit of $DEVICE_DAILY_LIMIT was reached.")
            _state.update { it.copy(packFilesDone = it.packFilesDone + 1, packBytesDone = library.sizeBytes) }
        }
    }

    fun pausePack() { packJob?.cancel() }

    /** Asks for brand-new jokes, beyond what is already in the library. */
    fun generateNew() {
        if (source == "none") { say("Funny voice is not set up."); return }
        if (!online()) { say("Generating new jokes needs an internet connection."); return }
        scope.launch {
            try {
                if (source == "cloud") {
                    val queued = cloud.regenerate(language, personality, level, 8)
                    say("$queued new jokes are being written and recorded. They will download as they become ready.")
                    kotlinx.coroutines.delay(45_000)
                    stock(NavEvent.entries, want = 12)
                } else {
                    val made = NavEvent.entries.shuffled().take(4).count { generateOnDevice(it) }
                    refreshCounts()
                    say(if (made > 0) "$made new jokes were added." else "No new joke could be generated. Check the xAI key, or today's limit was reached.")
                }
            } catch (e: Exception) {
                say(e.message ?: "New jokes could not be requested.")
            }
        }
    }

    fun clearCache() {
        packJob?.cancel()
        library.clear()
        _state.update { HumourState(source = source, personalities = it.personalities, fallbackNote = it.fallbackNote, message = "Saved jokes were deleted from this phone.") }
    }

    /** Loads the voices the backend offers for the chosen language and notes any fallback. */
    fun refreshVoices() {
        if (source != "cloud") {
            _state.update { it.copy(source = source, personalities = GrokVoiceClient.VOICES.map { v -> Personality(v, v.replaceFirstChar(Char::uppercase), "grok:$v", null, null) }, fallbackNote = null) }
            return
        }
        if (!online()) return
        scope.launch {
            runCatching { cloud.voices(language) }.onSuccess { info ->
                if (info.personalities.none { it.id == prefs.personality }) info.personalities.firstOrNull()?.let { prefs.personality = it.id }
                _state.update { it.copy(source = source, personalities = info.personalities, fallbackNote = info.fallbackNote.takeIf { _ -> info.fallback }) }
            }.onFailure { e -> say(e.message ?: "The voice list could not be loaded.") }
        }
    }

    /** Tells the backend which clips were played, so they are not served again within the window. */
    fun syncHistory() {
        if (source != "cloud" || !online() || prefs.offlineOnly) return
        val plays = library.pendingPlays()
        if (plays.isEmpty()) return
        scope.launch { runCatching { cloud.sendHistory(plays) }.onSuccess { library.clearPending(plays) } }
    }

    fun reportPronunciation(note: String) {
        val clip = _state.value.lastJoke
        if (clip == null) { say("Play a joke first, then report it."); return }
        if (source != "cloud") { say("Pronunciation reports go to the cloud voice service, which is not set up."); return }
        scope.launch {
            runCatching { cloud.reportPronunciation(clip.id.takeIf { clip.source == "cloud" }, clip.text, clip.language, note) }
                .onSuccess { say("Thank you. The report was sent for review.") }
                .onFailure { say(it.message ?: "The report could not be sent.") }
        }
    }

    fun pauseJokes(minutes: Int) { prefs.pausedUntilWallMs = if (minutes <= 0) 0 else clock() + minutes * 60_000L }

    fun clearMessage() = _state.update { it.copy(message = null) }
    private fun say(text: String) = _state.update { it.copy(message = text) }

    companion object {
        /** Cap on jokes generated on the phone per day, to bound what the user's own xAI key spends. */
        const val DEVICE_DAILY_LIMIT = 40

        /** The driving situation described to the joke writer. */
        fun situation(event: NavEvent): String = when (event) {
            NavEvent.ROUTE_START -> "The driver has just started a trip."
            NavEvent.ARRIVAL -> "The driver has just arrived at the destination."
            NavEvent.REROUTE -> "The driver missed a turn and the route was recalculated."
            NavEvent.LONG_STRAIGHT -> "A long, uneventful stretch of straight road."
            NavEvent.ROUNDABOUT -> "The driver has just come out of a roundabout."
            NavEvent.U_TURN -> "The driver has just made a U-turn."
            NavEvent.TURN_DONE -> "The driver has just completed a turn correctly."
            NavEvent.TRAFFIC_DELAY -> "The car has been crawling in heavy traffic for several minutes."
            NavEvent.SPEEDING -> "The driver is going faster than the speed limit and should slow down."
            NavEvent.SPEED_CAMERA -> "A speed camera has just been announced."
            NavEvent.SIGNAL_LOST -> "The satellite positioning signal was just lost."
            NavEvent.SIGNAL_BACK -> "The satellite positioning signal has just come back."
            NavEvent.STOPPED_LONG -> "The car has been standing still at a long red light."
            NavEvent.NIGHT_DRIVE -> "The driver is on the road late at night."
        }
    }
}

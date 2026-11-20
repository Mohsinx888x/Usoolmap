package com.usool.map.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Speaks navigation guidance.
 *
 * The professional voice uses the device's own text-to-speech engine, so it needs neither the
 * internet nor any AI service. The humorous voice is an optional layer on top: lines are written
 * by Grok and spoken by Grok's own text-to-speech ahead of time, stored in [cache], and played only when
 * the clip is already on disk. If it is not, the professional voice speaks instead, so a slow or
 * missing AI service can never delay or silence an instruction.
 */
class VoiceGuide(
    context: Context,
    private val scope: CoroutineScope,
    private val grok: GrokJokeClient,
    private val grokVoice: GrokVoiceClient,
    private val azure: AzureSpeechClient,
    /** Grok voice chosen in Settings. */
    private val grokVoiceId: () -> String,
    val cache: VoiceCache,
    private val online: () -> Boolean,
) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null
    private val pending = ConcurrentLinkedQueue<String>()
    private var prepareJob: Job? = null

    @Volatile var mode: VoiceMode = VoiceMode.PROFESSIONAL
    @Volatile var language: VoiceLanguage = VoiceLanguages.all.first()
        set(value) { field = value; applyTtsLanguage() }

    /** The humorous voice needs only an xAI key: Grok writes the line and Grok speaks it. */
    val humourConfigured: Boolean get() = grok.configured

    private fun clipKey(instruction: String, language: VoiceLanguage) = cache.key(instruction, language, "grok:${grokVoiceId()}")

    /** False when the device has no speech data installed for [language]. */
    @Volatile var professionalVoiceAvailable: Boolean = true
        private set

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun start() {
        if (tts != null) return
        tts = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setAudioAttributes(audioAttributes)
                applyTtsLanguage()
            } else {
                professionalVoiceAvailable = false
            }
        }
    }

    fun shutdown() {
        prepareJob?.cancel()
        player?.release(); player = null
        tts?.shutdown(); tts = null; ttsReady = false
    }

    private fun applyTtsLanguage() {
        val engine = tts ?: return
        if (!ttsReady) return
        val result = engine.setLanguage(Locale.forLanguageTag(language.tag))
        professionalVoiceAvailable = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        if (!professionalVoiceAvailable) engine.setLanguage(Locale.US)
    }

    /**
     * Speaks [instruction] now. [jokeKey] is the plain instruction the humorous clip was prepared
     * for (the manoeuvre without its distance prefix).
     */
    fun speak(instruction: String, jokeKey: String? = null) {
        when (mode) {
            VoiceMode.MUTED -> return
            VoiceMode.HUMOROUS -> {
                val clip = jokeKey?.let { cache.get(clipKey(it, language)) }
                if (clip != null && play(clip)) return
                speakProfessional(instruction)
            }
            VoiceMode.PROFESSIONAL -> speakProfessional(instruction)
        }
    }

    private fun speakProfessional(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "usool-${System.nanoTime()}")
    }

    private fun play(file: File): Boolean = try {
        tts?.stop()
        player?.release()
        player = MediaPlayer().apply {
            setAudioAttributes(audioAttributes)
            setDataSource(file.absolutePath)
            setOnCompletionListener { it.release(); if (player === it) player = null }
            prepare()
            start()
        }
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Queues humorous clips for the given instructions (normally every manoeuvre of a new route).
     * Work happens in the background; anything that cannot be fetched now stays queued and is
     * retried by [syncPending] when the connection returns.
     */
    fun prepare(instructions: List<String>) {
        if (mode != VoiceMode.HUMOROUS || !humourConfigured) return
        instructions.distinct().filter { cache.get(clipKey(it, language)) == null && it !in pending }.forEach { pending += it }
        syncPending()
    }

    fun syncPending() {
        if (prepareJob?.isActive == true || pending.isEmpty() || !online() || !humourConfigured) return
        val lang = language
        prepareJob = scope.launch {
            while (true) {
                val instruction = pending.peek() ?: break
                if (!online()) break
                val key = clipKey(instruction, lang)
                if (cache.get(key) == null) {
                    val line = grok.humorousLine(instruction, lang)
                    // Grok speaks the line; Azure is used only if it is configured and Grok speech fails.
                    val audio = line?.let { grokVoice.synthesize(it, lang, grokVoiceId()) ?: azure.synthesize(it, lang) }
                    if (audio == null) {
                        // Leave it queued and stop; the next sync (or the next route) tries again.
                        break
                    }
                    cache.put(key, audio)
                }
                pending.poll()
                delay(150)
            }
        }
    }

    val pendingCount: Int get() = pending.size
}

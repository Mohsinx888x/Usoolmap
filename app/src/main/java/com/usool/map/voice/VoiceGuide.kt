package com.usool.map.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.usool.map.voice.humour.AudioItem
import com.usool.map.voice.humour.AudioPriority
import com.usool.map.voice.humour.AudioPriorityScheduler
import com.usool.map.voice.humour.AudioSink
import java.util.Locale

/**
 * Everything the app says goes through here.
 *
 * Guidance is spoken by the phone's own text-to-speech engine, so it needs neither the internet
 * nor any AI service and is never held up by them. Humorous clips are finished audio files handed
 * in by the humour engine; the [scheduler] plays them only when nothing more important is being
 * said and cuts them off the moment guidance needs to speak.
 */
class VoiceGuide(context: Context) : AudioSink {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null

    val scheduler = AudioPriorityScheduler(this)

    @Volatile var mode: VoiceMode = VoiceMode.PROFESSIONAL
    @Volatile var language: VoiceLanguage = VoiceLanguages.all.first()
        set(value) { field = value; applyTtsLanguage() }

    /** False when the device has no speech data installed for [language]. */
    @Volatile var professionalVoiceAvailable: Boolean = true
        private set

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // Other audio (music, podcasts) is lowered while the app speaks and restored afterwards.
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).build()

    fun start() {
        if (tts != null) return
        tts = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setAudioAttributes(attributes)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = finished()
                    @Deprecated("Required by the interface") override fun onError(utteranceId: String?) = finished()
                    override fun onError(utteranceId: String?, errorCode: Int) = finished()
                })
                applyTtsLanguage()
            } else {
                professionalVoiceAvailable = false
            }
        }
    }

    fun shutdown() {
        scheduler.stopAll()
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

    /** Speaks guidance text with the professional voice at the given priority. */
    fun speak(text: String, priority: AudioPriority = AudioPriority.INSTRUCTION) {
        if (mode == VoiceMode.MUTED) return
        scheduler.submit(AudioItem(priority, text = text, submittedAtMs = System.currentTimeMillis()))
    }

    /**
     * Offers a humorous clip. Returns false when it was not played because something else was
     * being said; the caller then keeps the clip for another time.
     */
    fun playHumour(filePath: String, onStarted: () -> Unit): Boolean {
        if (mode != VoiceMode.FUNNY && mode != VoiceMode.SMART_MIXED) return false
        return scheduler.submit(AudioItem(AudioPriority.HUMOUR, filePath = filePath, submittedAtMs = System.currentTimeMillis(), onStarted = onStarted))
    }

    private fun finished() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        scheduler.onFinished()
    }

    // --- AudioSink: called by the scheduler, one item at a time ---

    override fun play(item: AudioItem) {
        audioManager.requestAudioFocus(focusRequest)
        val text = item.text
        val file = item.filePath
        when {
            text != null -> {
                val engine = tts
                if (engine == null || !ttsReady) { finished(); return }
                if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "usool-${System.nanoTime()}") != TextToSpeech.SUCCESS) finished()
            }
            file != null -> try {
                player?.release()
                player = MediaPlayer().apply {
                    setAudioAttributes(attributes)
                    setDataSource(file)
                    setOnCompletionListener { p -> p.release(); if (player === p) player = null; finished() }
                    setOnErrorListener { p, _, _ -> p.release(); if (player === p) player = null; finished(); true }
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                player = null
                finished()
            }
            else -> finished()
        }
    }

    override fun stop() {
        tts?.stop()
        player?.let { p -> runCatching { p.stop() }; p.release() }
        player = null
        audioManager.abandonAudioFocusRequest(focusRequest)
    }
}

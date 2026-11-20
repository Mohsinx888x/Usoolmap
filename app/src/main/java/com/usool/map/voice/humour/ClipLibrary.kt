package com.usool.map.voice.humour

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** A humorous clip stored on the phone. */
data class LocalClip(
    val id: String,
    val event: String,
    val language: String,
    val personality: String,
    val humourLevel: Int,
    val text: String,
    val fileName: String,
    val bytes: Long,
    /** "cloud" for the Azure library, "device" for clips generated on the phone. */
    val source: String,
    val voice: String? = null,
    val fallbackVoice: Boolean = false,
    val addedAtMs: Long = 0,
    val lastPlayedAtMs: Long = 0,
    val playCount: Int = 0,
)

data class PendingPlay(val clipId: String, val playedAtMs: Long)

/**
 * The phone's own library of humorous clips: audio files plus an index of what each one is and
 * when it was last played. Playback always comes from here, so it is instant and works offline.
 *
 * Repetition is avoided locally as well as in the cloud: a clip is not reused within
 * [nonRepeatDays], and a clip that opens with the same words as one of the last few played is
 * passed over when there is an alternative.
 */
class ClipLibrary(private val dir: File, var nonRepeatDays: Int = 90, private val maxBytes: Long = 200L * 1024 * 1024) {
    private val gson = Gson()
    private val indexFile = File(dir, "index.json")
    private val pendingFile = File(dir, "pending-plays.json")
    private val clips = LinkedHashMap<String, LocalClip>()
    private val pending = ArrayList<PendingPlay>()
    private val recentOpenings = ArrayDeque<String>()

    init {
        dir.mkdirs()
        runCatching {
            if (indexFile.isFile) gson.fromJson<List<LocalClip>>(indexFile.readText(), object : TypeToken<List<LocalClip>>() {}.type)
                ?.filter { File(dir, it.fileName).isFile }?.forEach { clips[it.id] = it }
            if (pendingFile.isFile) gson.fromJson<List<PendingPlay>>(pendingFile.readText(), object : TypeToken<List<PendingPlay>>() {}.type)?.let { pending += it }
        }
        clips.values.filter { it.lastPlayedAtMs > 0 }.sortedBy { it.lastPlayedAtMs }.takeLast(RECENT_OPENINGS).forEach { recentOpenings += JokeText.opening(it.text) }
    }

    @Synchronized fun file(clip: LocalClip): File = File(dir, clip.fileName)
    @Synchronized fun has(id: String): Boolean = clips.containsKey(id)
    @Synchronized fun ids(language: String, personality: String): List<String> = clips.values.filter { it.language == language && it.personality == personality }.map { it.id }
    @Synchronized fun all(): List<LocalClip> = clips.values.toList()
    val count: Int @Synchronized get() = clips.size
    val sizeBytes: Long @Synchronized get() = clips.values.sumOf { it.bytes }

    /** Clips that could be played for this selection, ignoring the repetition window. */
    @Synchronized
    fun countFor(language: String, personality: String, humourLevel: Int): Int =
        clips.values.count { it.language == language && it.personality == personality && it.humourLevel == humourLevel }

    /**
     * A clip for [event] that has not been played within the non-repetition window, or null when
     * there is none: the caller then simply says nothing.
     */
    @Synchronized
    fun pick(event: NavEvent, language: String, personality: String, humourLevel: Int, nowMs: Long): LocalClip? {
        val window = nonRepeatDays * 86_400_000L
        val fresh = clips.values.filter {
            it.event == event.wire && it.language == language && it.personality == personality && it.humourLevel == humourLevel &&
                (it.lastPlayedAtMs == 0L || nowMs - it.lastPlayedAtMs >= window)
        }
        if (fresh.isEmpty()) return null
        val varied = fresh.filter { JokeText.opening(it.text) !in recentOpenings }.ifEmpty { fresh }
        // Never-played first, then least recently played.
        return varied.minWith(compareBy({ it.lastPlayedAtMs }, { it.addedAtMs }))
    }

    @Synchronized
    fun markPlayed(clip: LocalClip, nowMs: Long) {
        clips[clip.id] = clip.copy(lastPlayedAtMs = nowMs, playCount = clip.playCount + 1)
        recentOpenings += JokeText.opening(clip.text)
        while (recentOpenings.size > RECENT_OPENINGS) recentOpenings.removeFirst()
        if (clip.source == "cloud") pending += PendingPlay(clip.id, nowMs)
        save()
    }

    /** Adds a downloaded clip whose audio is already at `dir/fileName`. */
    @Synchronized
    fun add(clip: LocalClip) {
        clips[clip.id] = clip
        trim()
        save()
    }

    /** True when [text] is too close to a clip already stored for the same event and language. */
    @Synchronized
    fun isTooSimilar(text: String, event: NavEvent, language: String, threshold: Double = 0.55): Boolean =
        clips.values.any { it.event == event.wire && it.language == language && JokeText.similarity(it.text, text) >= threshold }

    @Synchronized fun pendingPlays(): List<PendingPlay> = pending.toList()

    @Synchronized
    fun clearPending(sent: List<PendingPlay>) {
        pending.removeAll(sent.toSet())
        save()
    }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        clips.clear(); pending.clear(); recentOpenings.clear()
    }

    fun newFile(id: String): File = File(dir, "$id.mp3")
    fun partFile(id: String): File = File(dir, "$id.part")

    /** Makes room by dropping the clips played longest ago; unplayed clips go last. */
    private fun trim() {
        var total = clips.values.sumOf { it.bytes }
        if (total <= maxBytes) return
        for (c in clips.values.sortedWith(compareBy({ it.lastPlayedAtMs == 0L }, { it.lastPlayedAtMs }))) {
            if (total <= maxBytes) break
            File(dir, c.fileName).delete()
            clips.remove(c.id)
            total -= c.bytes
        }
    }

    private fun save() {
        runCatching {
            indexFile.writeText(gson.toJson(clips.values.toList()))
            pendingFile.writeText(gson.toJson(pending))
        }
    }

    companion object {
        private const val RECENT_OPENINGS = 6
    }
}

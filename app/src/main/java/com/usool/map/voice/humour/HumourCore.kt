package com.usool.map.voice.humour

import com.usool.map.routing.ManeuverType

/** Driving situations a humorous clip can react to. [wire] is the name used by the backend API. */
enum class NavEvent(val wire: String) {
    ROUTE_START("route_start"), ARRIVAL("arrival"), REROUTE("reroute"), LONG_STRAIGHT("long_straight"),
    ROUNDABOUT("roundabout"), U_TURN("u_turn"), TURN_DONE("turn_done"), TRAFFIC_DELAY("traffic_delay"),
    SPEEDING("speeding"), SPEED_CAMERA("speed_camera"), SIGNAL_LOST("signal_lost"), SIGNAL_BACK("signal_back"),
    STOPPED_LONG("stopped_long"), NIGHT_DRIVE("night_drive");

    companion object {
        fun fromWire(name: String): NavEvent? = entries.firstOrNull { it.wire == name }
    }
}

/** What the navigation engine knows at one moment; the input for detecting events and judging safety. */
data class DriveFacts(
    val nowMs: Long,
    val speedMps: Double,
    val distanceToManeuverM: Double,
    val nextManeuver: ManeuverType?,
    /** Index of the next manoeuvre in the route; it rises each time one is completed. */
    val maneuverIndex: Int,
    /** Distance from the next manoeuvre to the one after it; small values mean a complex junction sequence. */
    val gapAfterNextM: Double? = null,
    val offRoute: Boolean = false,
    val speedLimitKmh: Int? = null,
    val localHour: Int = 12,
)

/**
 * Detects the events that follow from how the drive is going (as opposed to those the navigator
 * announces itself, such as rerouting or arrival). Reactions to a turn are raised only after the
 * turn is finished, never on the approach.
 */
class NavEventDetector {
    private var startedAt = -1L
    private var lastIndex = -1
    private var lastManeuver: ManeuverType? = null
    private var slowSince = -1L
    private var stoppedSince = -1L
    private var speedingSince = -1L
    private var cruisingSince = -1L
    private var nightDone = false
    private var stoppedRaised = false
    private var trafficRaised = false
    private var speedingRaised = false
    private var straightRaisedForIndex = -1

    fun update(f: DriveFacts): List<NavEvent> {
        val out = ArrayList<NavEvent>()
        if (startedAt < 0) startedAt = f.nowMs

        // A manoeuvre has just been completed: react to what it was.
        if (lastIndex >= 0 && f.maneuverIndex > lastIndex) {
            when (lastManeuver) {
                ManeuverType.ROUNDABOUT -> out += NavEvent.ROUNDABOUT
                ManeuverType.UTURN -> out += NavEvent.U_TURN
                ManeuverType.LEFT, ManeuverType.RIGHT, ManeuverType.SHARP_LEFT, ManeuverType.SHARP_RIGHT -> out += NavEvent.TURN_DONE
                else -> Unit
            }
        }
        lastIndex = f.maneuverIndex
        lastManeuver = f.nextManeuver

        // Standing still (a long light) and crawling (a jam) are different situations.
        if (f.speedMps < 0.8) { if (stoppedSince < 0) stoppedSince = f.nowMs } else { stoppedSince = -1; stoppedRaised = false }
        if (stoppedSince >= 0 && !stoppedRaised && f.nowMs - stoppedSince >= STOPPED_MS) { stoppedRaised = true; out += NavEvent.STOPPED_LONG }
        if (f.speedMps < 4.0) { if (slowSince < 0) slowSince = f.nowMs } else { slowSince = -1; trafficRaised = false }
        if (slowSince >= 0 && !trafficRaised && f.nowMs - slowSince >= TRAFFIC_MS) { trafficRaised = true; out += NavEvent.TRAFFIC_DELAY }

        val limit = f.speedLimitKmh
        val over = limit != null && f.speedMps * 3.6 > limit * 1.1 + 3
        if (over) { if (speedingSince < 0) speedingSince = f.nowMs } else { speedingSince = -1; speedingRaised = false }
        if (speedingSince >= 0 && !speedingRaised && f.nowMs - speedingSince >= SPEEDING_MS) { speedingRaised = true; out += NavEvent.SPEEDING }

        // A long uneventful stretch, raised once per stretch.
        val cruising = f.distanceToManeuverM > LONG_STRAIGHT_M && f.speedMps > 12 && !f.offRoute
        if (cruising) { if (cruisingSince < 0) cruisingSince = f.nowMs } else cruisingSince = -1
        if (cruisingSince >= 0 && straightRaisedForIndex != f.maneuverIndex && f.nowMs - cruisingSince >= CRUISE_MS) {
            straightRaisedForIndex = f.maneuverIndex; out += NavEvent.LONG_STRAIGHT
        }

        if (!nightDone && (f.localHour >= 22 || f.localHour < 4) && f.nowMs - startedAt >= NIGHT_AFTER_MS) { nightDone = true; out += NavEvent.NIGHT_DRIVE }
        return out
    }

    companion object {
        const val STOPPED_MS = 45_000L
        const val TRAFFIC_MS = 180_000L
        const val SPEEDING_MS = 10_000L
        const val CRUISE_MS = 60_000L
        const val NIGHT_AFTER_MS = 300_000L
        const val LONG_STRAIGHT_M = 3_000.0
    }
}

enum class HumourMode { OFF, FUNNY, SMART_MIXED }

data class HumourSettings(
    val mode: HumourMode = HumourMode.OFF,
    /** 1 = gentle, 2 = playful, 3 = cheeky. */
    val intensity: Int = 2,
    /** 1 = rare, 2 = normal, 3 = frequent. */
    val frequency: Int = 2,
    /** Jokes are silenced until this time (elapsed ms); 0 when not paused. */
    val pausedUntilMs: Long = 0,
)

/**
 * Decides whether a humorous clip may be played right now. Safety comes first: nothing is played
 * close to a manoeuvre, while the driver is off route, or more often than the chosen frequency.
 * In Smart Mixed mode jokes are kept to quiet stretches and thinned out where junctions come in
 * quick succession.
 */
object HumourPolicy {
    data class Decision(val allow: Boolean, val reason: String)

    /** Seconds until the next manoeuvre below which nothing but guidance is spoken. */
    const val CRITICAL_SECONDS = 25.0
    const val CRITICAL_DISTANCE_M = 350.0

    fun minGapMs(settings: HumourSettings): Long {
        val base = when (settings.frequency.coerceIn(1, 3)) { 1 -> 300_000L; 2 -> 150_000L; else -> 75_000L }
        return if (settings.mode == HumourMode.SMART_MIXED) base * 3 / 2 else base
    }

    fun decide(event: NavEvent, f: DriveFacts, settings: HumourSettings, lastJokeAtMs: Long?): Decision {
        if (settings.mode == HumourMode.OFF) return Decision(false, "jokes are off")
        if (f.nowMs < settings.pausedUntilMs) return Decision(false, "jokes are paused")
        if (f.offRoute) return Decision(false, "off route")
        // Arrival ends the drive, so nothing is coming up that a joke could distract from.
        if (event != NavEvent.ARRIVAL) {
            val seconds = if (f.speedMps > 1) f.distanceToManeuverM / f.speedMps else Double.MAX_VALUE
            if (f.distanceToManeuverM < CRITICAL_DISTANCE_M || seconds < CRITICAL_SECONDS) return Decision(false, "manoeuvre coming up")
        }
        if (lastJokeAtMs != null && f.nowMs - lastJokeAtMs < minGapMs(settings)) return Decision(false, "too soon after the last joke")
        if (settings.mode == HumourMode.SMART_MIXED) {
            if (event != NavEvent.ARRIVAL && f.distanceToManeuverM < 900) return Decision(false, "not a quiet stretch")
            val gap = f.gapAfterNextM
            if (gap != null && gap < 400 && f.distanceToManeuverM < 2_000) return Decision(false, "complex junctions ahead")
            // These need the driver's attention more than a laugh.
            if (event == NavEvent.SPEEDING || event == NavEvent.SIGNAL_LOST) return Decision(false, "attention needed")
        }
        return Decision(true, "ok")
    }
}

/** Text comparison used to keep jokes from repeating. Lexical, not a measure of meaning. */
object JokeText {
    private val MARKS = Regex("[\\u064B-\\u0652\\u0670\\u0640]")
    private val PUNCT = Regex("[\\p{P}\\p{S}]")

    fun normalize(text: String): String = PUNCT.replace(MARKS.replace(text.lowercase(), ""), " ")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه')
        .trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** First two words: jokes that all start the same way feel repetitive even when they differ. */
    fun opening(text: String): String = normalize(text).split(" ").take(2).joinToString(" ")

    private fun shingles(text: String): Set<String> {
        val n = normalize(text)
        val words = n.split(" ")
        val out = HashSet<String>()
        for (i in 0 until words.size - 1) out += words[i] + " " + words[i + 1]
        val compact = n.replace(" ", "_")
        for (i in 0..compact.length - 3) out += compact.substring(i, i + 3)
        return out
    }

    fun similarity(a: String, b: String): Double {
        val x = shingles(a); val y = shingles(b)
        if (x.isEmpty() || y.isEmpty()) return if (normalize(a) == normalize(b)) 1.0 else 0.0
        val common = x.count { it in y }
        return common.toDouble() / (x.size + y.size - common)
    }
}

/** What is being said, most urgent first. */
enum class AudioPriority { SAFETY, INSTRUCTION, INFO, HUMOUR }

class AudioItem(val priority: AudioPriority, val text: String? = null, val filePath: String? = null, val submittedAtMs: Long = 0, val onStarted: (() -> Unit)? = null)

/** Plays one thing at a time. Implemented on Android by text-to-speech and a media player. */
interface AudioSink {
    fun play(item: AudioItem)
    fun stop()
}

/**
 * Orders everything the app says:
 *  1. safety alerts, 2. turn instructions, 3. route information, 4. jokes.
 *
 * Anything more urgent interrupts what is playing. A joke is the only thing that never waits:
 * if something else is speaking or queued it is dropped, and it is cut off the moment guidance
 * needs to speak. Route information that has waited too long is dropped as stale.
 */
class AudioPriorityScheduler(private val sink: AudioSink, private val clock: () -> Long = System::currentTimeMillis) {
    private val queue = ArrayList<AudioItem>()
    private var playing: AudioItem? = null

    val current: AudioPriority? get() = synchronized(this) { playing?.priority }

    /** Returns false when the item was dropped instead of played or queued. */
    @Synchronized
    fun submit(item: AudioItem): Boolean {
        val now = playing
        if (item.priority == AudioPriority.HUMOUR) {
            if (now != null || queue.isNotEmpty()) return false
            start(item)
            return true
        }
        if (now == null) { start(item); return true }
        if (item.priority.ordinal < now.priority.ordinal || now.priority == AudioPriority.HUMOUR) {
            // More urgent than what is playing: cut in. An interrupted instruction is not resumed,
            // because by then it is out of date.
            sink.stop()
            // Of two instructions, the newer one is the one that still applies.
            queue.removeAll { it.priority.ordinal >= item.priority.ordinal && it.priority != AudioPriority.SAFETY }
            start(item)
            return true
        }
        if (item.priority == now.priority && item.priority == AudioPriority.INSTRUCTION) {
            sink.stop(); start(item); return true
        }
        queue += item
        queue.sortBy { it.priority.ordinal }
        return true
    }

    /** Called by the sink when the current item has finished or failed. */
    @Synchronized
    fun onFinished() {
        playing = null
        val t = clock()
        queue.removeAll { it.priority == AudioPriority.INFO && t - it.submittedAtMs > STALE_INFO_MS }
        if (queue.isNotEmpty()) start(queue.removeAt(0))
    }

    @Synchronized
    fun stopAll() {
        queue.clear()
        if (playing != null) { sink.stop(); playing = null }
    }

    /** Stops a joke that is playing, for example because a manoeuvre has come close. */
    @Synchronized
    fun cancelHumour() {
        if (playing?.priority == AudioPriority.HUMOUR) { sink.stop(); playing = null }
    }

    private fun start(item: AudioItem) {
        playing = item
        item.onStarted?.invoke()
        sink.play(item)
    }

    companion object {
        const val STALE_INFO_MS = 8_000L
    }
}

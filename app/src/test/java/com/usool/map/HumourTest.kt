package com.usool.map

import com.google.gson.JsonParser
import com.usool.map.routing.ManeuverType
import com.usool.map.voice.humour.AudioItem
import com.usool.map.voice.humour.AudioPriority
import com.usool.map.voice.humour.AudioPriorityScheduler
import com.usool.map.voice.humour.AudioSink
import com.usool.map.voice.humour.ClipLibrary
import com.usool.map.voice.humour.CloudVoiceClient
import com.usool.map.voice.humour.DriveFacts
import com.usool.map.voice.humour.HumourMode
import com.usool.map.voice.humour.HumourPolicy
import com.usool.map.voice.humour.HumourSettings
import com.usool.map.voice.humour.JokeText
import com.usool.map.voice.humour.LocalClip
import com.usool.map.voice.humour.NavEvent
import com.usool.map.voice.humour.NavEventDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

private fun facts(t: Long, speed: Double = 20.0, dist: Double = 5000.0, next: ManeuverType? = ManeuverType.RIGHT, index: Int = 1, gap: Double? = null, off: Boolean = false, limit: Int? = null, hour: Int = 12) =
    DriveFacts(t, speed, dist, next, index, gap, off, limit, hour)

class NavEventDetectorTest {
    @Test fun reactsToATurnOnlyAfterItIsFinished() {
        val d = NavEventDetector()
        assertTrue(d.update(facts(0, dist = 400.0, next = ManeuverType.ROUNDABOUT, index = 1)).isEmpty())
        assertTrue(d.update(facts(1000, dist = 30.0, next = ManeuverType.ROUNDABOUT, index = 1)).isEmpty())
        assertEquals(listOf(NavEvent.ROUNDABOUT), d.update(facts(2000, dist = 900.0, next = ManeuverType.UTURN, index = 2)))
        assertEquals(listOf(NavEvent.U_TURN), d.update(facts(3000, dist = 900.0, next = ManeuverType.LEFT, index = 3)))
        assertEquals(listOf(NavEvent.TURN_DONE), d.update(facts(4000, dist = 900.0, next = ManeuverType.CONTINUE, index = 4)))
        // Passing a "continue" is not worth a remark.
        assertTrue(d.update(facts(5000, dist = 900.0, next = ManeuverType.ARRIVE, index = 5)).isEmpty())
    }

    @Test fun tellsALongRedLightFromAJam() {
        val d = NavEventDetector()
        var events = emptyList<NavEvent>()
        for (t in 0..50) events = events + d.update(facts(t * 1000L, speed = 0.0, dist = 800.0))
        assertEquals(listOf(NavEvent.STOPPED_LONG), events)
        // Still standing: it is not raised again.
        assertTrue((51..120).flatMap { d.update(facts(it * 1000L, speed = 0.0, dist = 800.0)) }.isEmpty())
        // Crawling for three minutes is traffic.
        val crawl = (121..181).flatMap { d.update(facts(it * 1000L, speed = 2.5, dist = 800.0)) }
        assertEquals(listOf(NavEvent.TRAFFIC_DELAY), crawl)
    }

    @Test fun noticesSpeedingOnlyWhenItLastsAndTheLimitIsKnown() {
        val d = NavEventDetector()
        assertTrue((0..30).flatMap { d.update(facts(it * 1000L, speed = 40.0, dist = 800.0, limit = null)) }.isEmpty())
        val over = (31..45).flatMap { d.update(facts(it * 1000L, speed = 30.0, dist = 800.0, limit = 80)) } // 108 km/h in an 80 zone
        assertEquals(listOf(NavEvent.SPEEDING), over)
        val brief = NavEventDetector()
        assertTrue((0..5).flatMap { brief.update(facts(it * 1000L, speed = 30.0, dist = 800.0, limit = 80)) }.isEmpty())
        // A few km/h over is tolerance, not speeding.
        val slight = NavEventDetector()
        assertTrue((0..30).flatMap { slight.update(facts(it * 1000L, speed = 23.5, dist = 800.0, limit = 80)) }.isEmpty())
    }

    @Test fun remarksOnceOnALongStraightAndOnceOnANightDrive() {
        val d = NavEventDetector()
        val events = (0..400).flatMap { d.update(facts(it * 1000L, speed = 28.0, dist = 9000.0, hour = 23)) }
        assertEquals(listOf(NavEvent.LONG_STRAIGHT, NavEvent.NIGHT_DRIVE), events)
        val day = NavEventDetector()
        assertFalse((0..400).flatMap { day.update(facts(it * 1000L, speed = 28.0, dist = 500.0, hour = 14)) }.contains(NavEvent.NIGHT_DRIVE))
    }
}

class HumourPolicyTest {
    private val funny = HumourSettings(HumourMode.FUNNY, frequency = 2)
    private val smart = HumourSettings(HumourMode.SMART_MIXED, frequency = 2)

    @Test fun neverJokesNearAManoeuvre() {
        assertFalse(HumourPolicy.decide(NavEvent.TURN_DONE, facts(0, dist = 300.0), funny, null).allow)
        // 600 m at 30 m/s is 20 seconds away: too close in time even though it is far in metres.
        assertFalse(HumourPolicy.decide(NavEvent.TURN_DONE, facts(0, speed = 30.0, dist = 600.0), funny, null).allow)
        assertTrue(HumourPolicy.decide(NavEvent.TURN_DONE, facts(0, speed = 15.0, dist = 600.0), funny, null).allow)
        // On arrival there is no manoeuvre left to distract from.
        assertTrue(HumourPolicy.decide(NavEvent.ARRIVAL, facts(0, dist = 0.0, speed = 0.0), funny, null).allow)
    }

    @Test fun staysSilentWhenOffPausedOrOffRoute() {
        assertFalse(HumourPolicy.decide(NavEvent.LONG_STRAIGHT, facts(0), HumourSettings(HumourMode.OFF), null).allow)
        assertFalse(HumourPolicy.decide(NavEvent.LONG_STRAIGHT, facts(1000), funny.copy(pausedUntilMs = 5000), null).allow)
        assertTrue(HumourPolicy.decide(NavEvent.LONG_STRAIGHT, facts(6000), funny.copy(pausedUntilMs = 5000), null).allow)
        assertFalse(HumourPolicy.decide(NavEvent.LONG_STRAIGHT, facts(0, off = true), funny, null).allow)
    }

    @Test fun spacesJokesByTheChosenFrequency() {
        assertEquals(300_000L, HumourPolicy.minGapMs(funny.copy(frequency = 1)))
        assertEquals(150_000L, HumourPolicy.minGapMs(funny))
        assertEquals(75_000L, HumourPolicy.minGapMs(funny.copy(frequency = 3)))
        assertEquals(225_000L, HumourPolicy.minGapMs(smart))
        assertFalse(HumourPolicy.decide(NavEvent.LONG_STRAIGHT, facts(100_000), funny, 0).allow)
        assertTrue(HumourPolicy.decide(NavEvent.LONG_STRAIGHT, facts(151_000), funny, 0).allow)
    }

    @Test fun smartMixedKeepsJokesForQuietStretches() {
        // 700 m from a turn at city speed: Funny allows it, Smart Mixed waits for a quieter stretch.
        val busy = facts(0, speed = 12.0, dist = 700.0)
        assertTrue(HumourPolicy.decide(NavEvent.TURN_DONE, busy, funny, null).allow)
        assertFalse(HumourPolicy.decide(NavEvent.TURN_DONE, busy, smart, null).allow)
        assertTrue(HumourPolicy.decide(NavEvent.LONG_STRAIGHT, facts(0, dist = 4000.0), smart, null).allow)
        // Two junctions in quick succession ahead: hold back.
        assertFalse(HumourPolicy.decide(NavEvent.LONG_STRAIGHT, facts(0, dist = 1500.0, gap = 200.0), smart, null).allow)
        assertFalse(HumourPolicy.decide(NavEvent.SPEEDING, facts(0, dist = 4000.0), smart, null).allow)
        assertTrue(HumourPolicy.decide(NavEvent.SPEEDING, facts(0, dist = 4000.0), funny, null).allow)
    }
}

class AudioPrioritySchedulerTest {
    private class FakeSink : AudioSink {
        val log = ArrayList<String>()
        override fun play(item: AudioItem) { log += "play:" + (item.text ?: item.filePath) }
        override fun stop() { log += "stop" }
    }
    private fun say(p: AudioPriority, text: String, at: Long = 0) = AudioItem(p, text = text, submittedAtMs = at)
    private fun joke(name: String) = AudioItem(AudioPriority.HUMOUR, filePath = name)

    @Test fun guidanceCutsOffAJokeImmediately() {
        val sink = FakeSink(); val s = AudioPriorityScheduler(sink)
        assertTrue(s.submit(joke("j1")))
        assertTrue(s.submit(say(AudioPriority.INSTRUCTION, "Turn left")))
        assertEquals(listOf("play:j1", "stop", "play:Turn left"), sink.log)
        assertEquals(AudioPriority.INSTRUCTION, s.current)
    }

    @Test fun aJokeNeverWaitsAndNeverInterrupts() {
        val sink = FakeSink(); val s = AudioPriorityScheduler(sink)
        s.submit(say(AudioPriority.INSTRUCTION, "Turn left"))
        assertFalse(s.submit(joke("j1")))
        s.onFinished()
        // It was dropped, not queued: nothing plays after the instruction.
        assertEquals(listOf("play:Turn left"), sink.log)
        assertNull(s.current)
        s.submit(say(AudioPriority.INFO, "Recalculating"))
        assertFalse(s.submit(joke("j2")))
    }

    @Test fun safetyAlertsBeatEverythingAndOrderIsByUrgency() {
        val sink = FakeSink(); val s = AudioPriorityScheduler(sink) { 0L }
        s.submit(say(AudioPriority.INSTRUCTION, "In 300 meters turn right"))
        s.submit(say(AudioPriority.SAFETY, "Speed camera"))
        assertEquals(listOf("play:In 300 meters turn right", "stop", "play:Speed camera"), sink.log)
        // While the alert plays, an instruction and some information queue up; the instruction goes first.
        s.submit(say(AudioPriority.INFO, "Route info"))
        s.submit(say(AudioPriority.INSTRUCTION, "Turn right"))
        s.onFinished()
        assertEquals("play:Turn right", sink.log.last())
        s.onFinished()
        assertEquals("play:Route info", sink.log.last())
    }

    @Test fun aNewerInstructionReplacesAnOlderOne() {
        val sink = FakeSink(); val s = AudioPriorityScheduler(sink)
        s.submit(say(AudioPriority.INSTRUCTION, "In 300 meters turn right"))
        s.submit(say(AudioPriority.INSTRUCTION, "Turn right"))
        assertEquals(listOf("play:In 300 meters turn right", "stop", "play:Turn right"), sink.log)
    }

    @Test fun staleInformationIsDroppedAndHumourCanBeCancelled() {
        var now = 0L
        val sink = FakeSink(); val s = AudioPriorityScheduler(sink) { now }
        s.submit(say(AudioPriority.INSTRUCTION, "Turn right"))
        s.submit(say(AudioPriority.INFO, "Old info", at = 0))
        now = 20_000
        s.onFinished()
        assertEquals(listOf("play:Turn right"), sink.log)
        s.submit(joke("j1"))
        s.cancelHumour()
        assertEquals("stop", sink.log.last())
        assertNull(s.current)
    }
}

class ClipLibraryTest {
    private val day = 86_400_000L
    private fun tempDir(): File = Files.createTempDirectory("clips").toFile()
    private fun add(lib: ClipLibrary, id: String, text: String, event: NavEvent = NavEvent.REROUTE, source: String = "cloud", bytes: Int = 10) {
        lib.newFile(id).writeBytes(ByteArray(bytes))
        lib.add(LocalClip(id, event.wire, "ar-QA", "p1", 2, text, "$id.mp3", bytes.toLong(), source, addedAtMs = id.hashCode().toLong() and 0xffff))
    }

    @Test fun doesNotRepeatAJokeWithinTheWindow() {
        val lib = ClipLibrary(tempDir(), nonRepeatDays = 90)
        add(lib, "a", "First joke about missing a turn")
        add(lib, "b", "Another line entirely different")
        val t0 = 1_000_000_000_000L
        val first = lib.pick(NavEvent.REROUTE, "ar-QA", "p1", 2, t0)!!
        lib.markPlayed(first, t0)
        val second = lib.pick(NavEvent.REROUTE, "ar-QA", "p1", 2, t0 + day)!!
        assertTrue(second.id != first.id)
        lib.markPlayed(second, t0 + day)
        // Both heard: silence rather than a repeat.
        assertNull(lib.pick(NavEvent.REROUTE, "ar-QA", "p1", 2, t0 + 30 * day))
        // After the window the oldest one may come back.
        assertEquals(first.id, lib.pick(NavEvent.REROUTE, "ar-QA", "p1", 2, t0 + 91 * day)!!.id)
    }

    @Test fun onlyPicksClipsForTheSameSelection() {
        val lib = ClipLibrary(tempDir())
        add(lib, "a", "Joke")
        assertNull(lib.pick(NavEvent.ARRIVAL, "ar-QA", "p1", 2, 0))
        assertNull(lib.pick(NavEvent.REROUTE, "en-US", "p1", 2, 0))
        assertNull(lib.pick(NavEvent.REROUTE, "ar-QA", "other", 2, 0))
        assertNull(lib.pick(NavEvent.REROUTE, "ar-QA", "p1", 3, 0))
        assertNotNull(lib.pick(NavEvent.REROUTE, "ar-QA", "p1", 2, 0))
    }

    @Test fun avoidsTheSameOpeningWordsTwiceInARow() {
        val lib = ClipLibrary(tempDir())
        add(lib, "a", "Well well, you missed it again", NavEvent.REROUTE)
        add(lib, "b", "Well well, what a lovely stretch", NavEvent.LONG_STRAIGHT)
        add(lib, "c", "Nothing but road and sky", NavEvent.LONG_STRAIGHT)
        lib.markPlayed(lib.pick(NavEvent.REROUTE, "ar-QA", "p1", 2, 0)!!, 1)
        assertEquals("c", lib.pick(NavEvent.LONG_STRAIGHT, "ar-QA", "p1", 2, 2)!!.id)
    }

    @Test fun survivesRestartAndQueuesCloudPlaysForSync() {
        val dir = tempDir()
        val lib = ClipLibrary(dir)
        add(lib, "cloud1", "From the cloud")
        add(lib, "dev1", "Made on the phone", source = "device")
        lib.markPlayed(lib.all().first { it.id == "cloud1" }, 5000)
        lib.markPlayed(lib.all().first { it.id == "dev1" }, 6000)
        val reopened = ClipLibrary(dir)
        assertEquals(2, reopened.count)
        assertEquals(5000, reopened.all().first { it.id == "cloud1" }.lastPlayedAtMs)
        // Only cloud clips are reported to the cloud.
        assertEquals(listOf("cloud1"), reopened.pendingPlays().map { it.clipId })
        reopened.clearPending(reopened.pendingPlays())
        assertTrue(ClipLibrary(dir).pendingPlays().isEmpty())
        // A clip whose audio file is gone is forgotten.
        File(dir, "dev1.mp3").delete()
        assertEquals(listOf("cloud1"), ClipLibrary(dir).all().map { it.id })
    }

    @Test fun detectsNearDuplicatesAndClears() {
        val lib = ClipLibrary(tempDir())
        add(lib, "a", "يَا طَوِيل الْعُمُر، ضَيَّعْت اللَّفَّة مَرَّة ثَانْيَة")
        // Same words without vowel marks and with different punctuation.
        assertTrue(lib.isTooSimilar("يا طويل العمر ضيعت اللفة مرة ثانية!", NavEvent.REROUTE, "ar-QA"))
        assertFalse(lib.isTooSimilar("الطريق فاضي والجو حلو", NavEvent.REROUTE, "ar-QA"))
        assertFalse(lib.isTooSimilar("يا طويل العمر ضيعت اللفة مرة ثانية", NavEvent.ARRIVAL, "ar-QA"))
        lib.clear()
        assertEquals(0, lib.count); assertEquals(0L, lib.sizeBytes)
    }

    @Test fun makesRoomByDroppingTheLongestAgoPlayed() {
        val lib = ClipLibrary(tempDir(), maxBytes = 250)
        add(lib, "old", "Played long ago", bytes = 100)
        lib.markPlayed(lib.all().first(), 1000)
        add(lib, "new1", "Never played one", bytes = 100)
        add(lib, "new2", "Never played two", bytes = 100)
        assertEquals(setOf("new1", "new2"), lib.all().map { it.id }.toSet())
    }
}

class JokeTextAndCloudTest {
    @Test fun comparesJokesIgnoringCasePunctuationAndTashkeel() {
        assertEquals("well well", JokeText.opening("Well, well... you again!"))
        assertEquals(1.0, JokeText.similarity("Turn missed, again!", "turn missed again"), 1e-9)
        assertTrue(JokeText.similarity("You missed the turn again my friend", "You missed that turn again my friend") > 0.55)
        assertTrue(JokeText.similarity("You missed the turn again", "The road is empty and the sky is blue") < 0.2)
        assertEquals(JokeText.normalize("الدَّوَّار"), JokeText.normalize("الدوار"))
    }

    @Test fun eventNamesMatchTheBackendContract() {
        val contract = listOf("route_start", "arrival", "reroute", "long_straight", "roundabout", "u_turn", "turn_done", "traffic_delay", "speeding", "speed_camera", "signal_lost", "signal_back", "stopped_long", "night_drive")
        assertEquals(contract.sorted(), NavEvent.entries.map { it.wire }.sorted())
        assertEquals(NavEvent.U_TURN, NavEvent.fromWire("u_turn"))
        assertNull(NavEvent.fromWire("nonsense"))
    }

    @Test fun parsesClipsFromTheBackend() {
        val json = """{"clips":[
          {"id":"c1","eventType":"reroute","language":"ar-QA","personality":"abu-nasser","humourLevel":2,"text":"نكتة","bytes":48213,"voice":"ar-QA-MoazNeural","fallbackVoice":false,"audioUrl":"/v1/clips/c1/audio","createdAt":"2026-11-20T10:00:00Z"},
          {"id":"c2","eventType":"arrival","language":"ar-JO","personality":"x","humourLevel":1,"text":"t","bytes":10,"voice":null,"fallbackVoice":true,"audioUrl":"/v1/clips/c2/audio"},
          {"id":"broken"}],"generating":2,"quotaRemaining":18}"""
        val clips = CloudVoiceClient.parseClips(JsonParser.parseString(json).asJsonObject.getAsJsonArray("clips"))
        assertEquals(listOf("c1", "c2"), clips.map { it.id })
        assertEquals(48213L, clips[0].bytes)
        assertEquals("ar-QA-MoazNeural", clips[0].voice)
        assertTrue(clips[1].fallbackVoice)
        assertNull(clips[1].voice)
    }
}

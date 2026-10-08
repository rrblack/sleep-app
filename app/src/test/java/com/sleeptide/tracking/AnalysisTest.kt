package com.sleeptide.tracking

import com.sleeptide.data.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class AudioAnalyzerTest {
    private val sr = 16000

    /** Feeds [seconds] of audio produced by [gen] (sample index -> value in -1..1) through the analyzer. */
    private fun run(seconds: Int, gen: (Int) -> Double): Pair<AudioAnalyzer, List<AudioAnalyzer.Event>> {
        val events = mutableListOf<AudioAnalyzer.Event>()
        val a = AudioAnalyzer(sr, 1)
        a.listener = object : AudioAnalyzer.Listener {
            override fun onEvent(event: AudioAnalyzer.Event) { events += event }
        }
        val frame = ShortArray(AudioAnalyzer.FRAME)
        val total = seconds * sr
        var i = 0
        while (i + frame.size <= total) {
            for (k in frame.indices) frame[k] = (gen(i + k).coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
            a.process(frame, i * 1000L / sr)
            i += frame.size
        }
        a.flush()
        return a to events
    }

    private val rnd = Random(1)
    private fun background() = (rnd.nextDouble() * 2 - 1) * 0.002

    @Test
    fun silenceProducesNoEvents() {
        val (a, events) = run(30) { background() }
        assertTrue(events.isEmpty())
        assertEquals(0f, a.drainMinute().activeSec, 0.001f)
    }

    @Test
    fun rhythmicLowFrequencyBurstsAreSnoring() {
        // 1.2 s low-pitched "snore" every 4 s.
        val (a, events) = run(60) { i ->
            val t = i.toDouble() / sr
            val phase = t % 4.0
            val env = if (phase < 1.2) sin(PI * phase / 1.2) else 0.0
            val tone = 0.25 * (sin(2 * PI * 110 * t) + 0.6 * sin(2 * PI * 220 * t) + 0.3 * sin(2 * PI * 330 * t))
            env * tone + background()
        }
        val stats = a.drainMinute()
        assertTrue("expected a snore episode, got $events", events.any { it.type == AudioAnalyzer.EventType.SNORE })
        assertTrue("snore seconds ${stats.snoreSec}", stats.snoreSec > 30f)
        assertFalse(events.any { it.type == AudioAnalyzer.EventType.TALK })
    }

    @Test
    fun broadbandBurstsAreMovementNotSnoring() {
        // 0.6 s noise burst (rustling) every 7 s.
        val (a, events) = run(60) { i ->
            val t = i.toDouble() / sr
            val burst = (t % 7.0) < 0.6
            (if (burst) (rnd.nextDouble() * 2 - 1) * 0.3 else 0.0) + background()
        }
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it.type == AudioAnalyzer.EventType.NOISE })
        assertTrue(a.drainMinute().activeSec > 3f)
    }

    @Test
    fun midBandVoiceIsTalk() {
        val (_, events) = run(10) { i ->
            val t = i.toDouble() / sr
            val on = t in 2.0..3.6
            val voice = 0.2 * (sin(2 * PI * 700 * t) + sin(2 * PI * 1100 * t) + 0.7 * sin(2 * PI * 1500 * t)) * (0.6 + 0.4 * sin(2 * PI * 4 * t))
            (if (on) voice else 0.0) + background()
        }
        assertTrue("expected talk, got $events", events.any { it.type == AudioAnalyzer.EventType.TALK })
    }

    @Test
    fun steadyFanIsIgnoredAfterAdapting() {
        val (_, events) = run(60) { i ->
            val t = i.toDouble() / sr
            0.05 * sin(2 * PI * 120 * t) + (rnd.nextDouble() * 2 - 1) * 0.02
        }
        assertTrue("steady noise should not create events, got $events", events.isEmpty())
    }
}

class SleepStagerTest {
    @Test
    fun stillNightIsMostlyDeepSleep() {
        val r = SleepStager.analyze(List(480) { 0f })
        assertEquals(480, r.asleepMin)
        assertEquals(0, r.latencyMin)
        assertEquals(480, r.deepMin)
        assertEquals(100, SleepStager.quality(r, 480, 480))
    }

    @Test
    fun restlessStartCountsAsFallingAsleep() {
        val activity = List(20) { 20f } + List(400) { if (it % 45 in 0..3) 2f else 0f }
        val r = SleepStager.analyze(activity)
        assertTrue("latency ${r.latencyMin}", r.latencyMin in 18..24)
        assertTrue(r.stages.take(18).all { it == Stage.AWAKE })
        assertTrue(r.lightMin > 0 && r.deepMin > 0)
        assertEquals(r.asleepMin + r.awakeMin, activity.size)
    }

    @Test
    fun awakeningInTheNightIsCounted() {
        val activity = List(200) { 0f } + List(10) { 30f } + List(200) { 0f }
        val r = SleepStager.analyze(activity)
        assertEquals(1, r.awakenings)
        assertTrue(SleepStager.quality(r, activity.size, 480) < 100)
    }

    @Test
    fun smartAlarmDetectsLightPhase() {
        val quiet = List(60) { 0f }
        assertFalse(SleepStager.isLightPhase(quiet, 0f))
        assertTrue(SleepStager.isLightPhase(quiet, 3f))
        assertTrue(SleepStager.isLightPhase(quiet.dropLast(2) + listOf(1.5f, 1f), 0f))
    }

    @Test
    fun depthStaysInRange() {
        val r = SleepStager.analyze(List(300) { (it % 37).toFloat() / 3f })
        assertTrue(r.depth.all { it in 0f..1f })
        assertEquals(300, r.depth.size)
    }
}

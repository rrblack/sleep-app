package com.sleeptide.tracking

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns a stream of 16-bit mono PCM frames into sleep-relevant sound events.
 *
 * - Keeps an adaptive noise floor so steady background (fans, AC) is ignored.
 * - A sound event is a stretch of frames clearly above that floor.
 * - Each event is classified by its spectrum and length:
 *   - snore-like: 0.25-3.5 s with most energy below ~450 Hz;
 *   - talk: longer, energy concentrated in the 450-2000 Hz speech band;
 *   - everything else is movement/noise (rustling sheets, bumps, coughs).
 * - Snoring is only confirmed when snore-like events repeat with a breathing rhythm
 *   (gaps of 1.5-12 s), which filters out single low-frequency thumps.
 *
 * Not thread-safe; the caller serializes access.
 */
class AudioAnalyzer(private val sampleRate: Int = 16000, sensitivity: Int = 1) {

    enum class EventType { SNORE, TALK, NOISE }

    data class Event(val type: EventType, val startMs: Long, val durationMs: Long, val peakDb: Float)

    data class MinuteStats(val activeSec: Float, val snoreSec: Float, val avgDb: Float, val events: Int)

    interface Listener {
        fun onEvent(event: Event) {}
        fun onSnoreEpisodeStart(timeMs: Long) {}
    }

    companion object {
        const val FRAME = 512
        private const val MIN_EVENT_MS = 120L
        private const val END_SILENCE_FRAMES = 10
        private const val MAX_EVENT_MS = 8000L
        private const val SNORE_GAP_MIN_MS = 1500L
        private const val SNORE_GAP_MAX_MS = 12000L
        private const val SNORE_EPISODE_END_MS = 20000L
    }

    var listener: Listener? = null

    /** While true (e.g. sleep sounds playing), levels are tracked but no events are produced. */
    var suppressEvents = false

    var currentDb = -90f
        private set
    var noiseFloor = -90f
        private set
    var snoring = false
        private set

    private val thresholdDb = when (sensitivity) { 0 -> 12f; 2 -> 6f; else -> 9f }
    private val frameMs = FRAME * 1000.0 / sampleRate
    private val re = DoubleArray(FRAME)
    private val im = DoubleArray(FRAME)
    private val window = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1)) }
    private val lowBins = bins(60.0, 450.0)
    private val midBins = bins(450.0, 2000.0)
    private val highBins = bins(2000.0, min(8000.0, sampleRate / 2.0))
    private var floorInit = false

    // Current event
    private var inEvent = false
    private var evStart = 0L
    private var evLastActive = 0L
    private var evPeak = -90f
    private var evLow = 0.0
    private var evMid = 0.0
    private var evHigh = 0.0
    private var silentFrames = 0

    // Snoring
    private val snoreCandidates = ArrayDeque<Long>()
    private var lastSnoreMs = 0L
    private var episodeStart = 0L
    private var episodePeak = -90f
    private var lastFrameMs = 0L

    // Per-minute accumulators
    private var mActiveMs = 0.0
    private var mSnoreMs = 0.0
    private var mDbSum = 0.0
    private var mFrames = 0
    private var mEvents = 0

    private fun bins(fromHz: Double, toHz: Double): IntRange {
        val hz = sampleRate.toDouble() / FRAME
        val a = max(1, (fromHz / hz).toInt())
        val b = min(FRAME / 2 - 1, (toHz / hz).toInt())
        return a..b
    }

    /** Processes one frame of [FRAME] samples that started at [timeMs]. */
    fun process(frame: ShortArray, timeMs: Long) {
        lastFrameMs = timeMs
        var sum = 0.0
        for (i in 0 until FRAME) {
            val v = frame[i] / 32768.0
            sum += v * v
            re[i] = v * window[i]
            im[i] = 0.0
        }
        val db = (20 * log10(sqrt(sum / FRAME) + 1e-9)).toFloat().coerceAtLeast(-100f)
        currentDb = db
        mDbSum += db
        mFrames++

        when {
            !floorInit -> { noiseFloor = db; floorInit = true }
            db < noiseFloor -> noiseFloor += (db - noiseFloor) * 0.05f
            !inEvent -> noiseFloor += (db - noiseFloor) * 0.002f
            else -> noiseFloor += (db - noiseFloor) * 0.0003f
        }

        if (snoring && timeMs - lastSnoreMs > SNORE_EPISODE_END_MS) endEpisode()

        if (suppressEvents) {
            inEvent = false
            silentFrames = 0
            return
        }

        val active = db > noiseFloor + thresholdDb
        if (active) {
            if (!inEvent) {
                inEvent = true
                evStart = timeMs
                evPeak = db
                evLow = 0.0; evMid = 0.0; evHigh = 0.0
            }
            evLastActive = timeMs
            silentFrames = 0
            evPeak = max(evPeak, db)
            accumulateSpectrum()
            if (timeMs - evStart >= MAX_EVENT_MS) finishEvent()
        } else if (inEvent) {
            silentFrames++
            if (silentFrames >= END_SILENCE_FRAMES) finishEvent()
        }
    }

    private fun accumulateSpectrum() {
        Fft.transform(re, im)
        evLow += bandEnergy(lowBins)
        evMid += bandEnergy(midBins)
        evHigh += bandEnergy(highBins)
    }

    private fun bandEnergy(r: IntRange): Double {
        var e = 0.0
        for (k in r) e += re[k] * re[k] + im[k] * im[k]
        return e
    }

    private fun finishEvent() {
        inEvent = false
        silentFrames = 0
        val dur = (evLastActive - evStart + frameMs).toLong()
        if (dur < MIN_EVENT_MS) return
        val total = evLow + evMid + evHigh + 1e-12
        val lowR = evLow / total
        val midR = evMid / total
        val highR = evHigh / total

        val snoreLike = dur in 250L..3500L && lowR >= 0.5 && highR < 0.2
        if (snoreLike && handleSnoreCandidate(evStart, dur)) return

        mActiveMs += dur
        mEvents++
        val type = if (dur >= 700 && midR >= 0.4 && highR < 0.35) EventType.TALK else EventType.NOISE
        listener?.onEvent(Event(type, evStart, dur, evPeak))
    }

    /** Returns true when the candidate was counted as snoring (and therefore not as movement). */
    private fun handleSnoreCandidate(t: Long, dur: Long): Boolean {
        while (snoreCandidates.isNotEmpty() && t - snoreCandidates.first() > 30_000) snoreCandidates.removeFirst()
        snoreCandidates.addLast(t)

        if (snoring) {
            val gap = t - lastSnoreMs
            mSnoreMs += if (gap <= SNORE_GAP_MAX_MS) gap.toDouble() else dur.toDouble()
            lastSnoreMs = t
            episodePeak = max(episodePeak, evPeak)
            return true
        }
        val n = snoreCandidates.size
        if (n >= 3) {
            val g1 = snoreCandidates[n - 1] - snoreCandidates[n - 2]
            val g2 = snoreCandidates[n - 2] - snoreCandidates[n - 3]
            val rhythmic = g1 in SNORE_GAP_MIN_MS..SNORE_GAP_MAX_MS && g2 in SNORE_GAP_MIN_MS..SNORE_GAP_MAX_MS
            if (rhythmic) {
                snoring = true
                episodeStart = snoreCandidates[n - 3]
                episodePeak = evPeak
                lastSnoreMs = t
                mSnoreMs += (g1 + g2).toDouble()
                listener?.onSnoreEpisodeStart(t)
                return true
            }
        }
        return false
    }

    private fun endEpisode() {
        if (!snoring) return
        snoring = false
        val duration = lastSnoreMs - episodeStart + 1500
        listener?.onEvent(Event(EventType.SNORE, episodeStart, duration, episodePeak))
    }

    /** Closes any open event or snoring episode (call when tracking stops). */
    fun flush() {
        if (inEvent) finishEvent()
        endEpisode()
    }

    /** Seconds of movement/noise in the current, not yet drained minute. */
    fun peekActiveSec(): Float = (mActiveMs / 1000.0).toFloat()

    fun drainMinute(): MinuteStats {
        val stats = MinuteStats(
            activeSec = min(60.0, mActiveMs / 1000.0).toFloat(),
            snoreSec = min(60.0, mSnoreMs / 1000.0).toFloat(),
            avgDb = if (mFrames > 0) (mDbSum / mFrames).toFloat() else -90f,
            events = mEvents,
        )
        mActiveMs = 0.0; mSnoreMs = 0.0; mDbSum = 0.0; mFrames = 0; mEvents = 0
        return stats
    }
}

object Fft {
    /** In-place iterative radix-2 FFT. Array length must be a power of two. */
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            val half = len / 2
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val vr = re[b] * cr - im[b] * ci
                    val vi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - vr
                    im[b] = im[a] - vi
                    re[a] += vr
                    im[a] += vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

package com.sleeptide.tracking

import com.sleeptide.data.Stage
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Estimates sleep stages from per-minute activity (seconds of movement or noise in each minute).
 *
 * This is actigraphy-style scoring, the same idea Sleep Cycle uses with its microphone:
 * lots of movement means awake, occasional movement means light sleep and long
 * still stretches mean deep sleep. It is an estimate, not a medical measurement.
 */
object SleepStager {
    private val WEIGHTS = floatArrayOf(1.0f, 0.7f, 0.5f, 0.3f, 0.15f)
    const val LIGHT_THRESHOLD = 0.25f
    const val AWAKE_SMOOTHED = 5f
    const val AWAKE_RAW = 10f

    data class Result(
        val stages: List<Int>,
        val depth: List<Float>,
        val latencyMin: Int,
        val asleepMin: Int,
        val deepMin: Int,
        val lightMin: Int,
        val awakeMin: Int,
        val awakenings: Int,
    )

    fun smooth(a: List<Float>): FloatArray {
        val n = a.size
        val out = FloatArray(n)
        for (i in 0 until n) {
            var sum = 0f
            var wsum = 0f
            for (k in -4..4) {
                val j = i + k
                if (j < 0 || j >= n) continue
                val w = WEIGHTS[kotlin.math.abs(k)]
                sum += a[j] * w
                wsum += w
            }
            out[i] = if (wsum > 0) sum / wsum else 0f
        }
        return out
    }

    fun analyze(activity: List<Float>): Result {
        val n = activity.size
        if (n == 0) return Result(emptyList(), emptyList(), 0, 0, 0, 0, 0, 0)
        val s = smooth(activity)
        val stages = IntArray(n) { i ->
            when {
                activity[i] >= AWAKE_RAW || s[i] >= AWAKE_SMOOTHED -> Stage.AWAKE
                s[i] >= LIGHT_THRESHOLD -> Stage.LIGHT
                else -> Stage.DEEP
            }
        }

        // Sleep onset: first minute followed by 10 minutes without being awake.
        var onset = n
        for (i in 0 until n) {
            val end = min(n, i + 10)
            if ((i until end).none { stages[it] == Stage.AWAKE }) { onset = i; break }
        }
        var final = -1
        for (i in n - 1 downTo 0) if (stages[i] != Stage.AWAKE) { final = i; break }
        for (i in 0 until n) if (i < onset || i > final) stages[i] = Stage.AWAKE

        // Deep sleep comes in sustained blocks; very short "deep" blips are light sleep.
        var i = 0
        while (i < n) {
            if (stages[i] == Stage.DEEP) {
                var j = i
                while (j < n && stages[j] == Stage.DEEP) j++
                if (j - i < 5) for (k in i until j) stages[k] = Stage.LIGHT
                i = j
            } else i++
        }

        var awakenings = 0
        if (onset <= final) {
            var k = onset
            while (k <= final) {
                if (stages[k] == Stage.AWAKE) {
                    var e = k
                    while (e <= final && stages[e] == Stage.AWAKE) e++
                    if (e - k >= 2) awakenings++
                    k = e
                } else k++
            }
        }

        val deep = stages.count { it == Stage.DEEP }
        val light = stages.count { it == Stage.LIGHT }
        val asleep = deep + light

        val scale = ln(1f + 24f)
        val rawDepth = FloatArray(n) { idx ->
            val d = 1f - min(1f, ln(1f + s[idx] * 4f) / scale)
            if (stages[idx] == Stage.AWAKE) min(d, 0.1f) else d
        }
        val depth = List(n) { idx ->
            var sum = 0f
            var c = 0
            for (k in max(0, idx - 3)..min(n - 1, idx + 3)) { sum += rawDepth[k]; c++ }
            sum / c
        }

        return Result(
            stages = stages.toList(),
            depth = depth,
            latencyMin = min(onset, n),
            asleepMin = asleep,
            deepMin = deep,
            lightMin = light,
            awakeMin = n - asleep,
            awakenings = awakenings,
        )
    }

    /** Sleep quality 0-100 from duration, efficiency, deep sleep, awakenings and time to fall asleep. */
    fun quality(r: Result, inBedMin: Int, goalMin: Int): Int {
        if (inBedMin <= 0 || r.asleepMin == 0) return 0
        val duration = min(1f, r.asleepMin.toFloat() / max(60, goalMin)) * 45f
        val efficiency = ((r.asleepMin.toFloat() / inBedMin - 0.6f) / 0.35f).coerceIn(0f, 1f) * 20f
        val deep = min(1f, (r.deepMin.toFloat() / r.asleepMin) / 0.3f) * 15f
        val awakenings = max(0f, 10f - r.awakenings * 2.5f)
        val latency = when {
            r.latencyMin <= 20 -> 10f
            r.latencyMin <= 45 -> 5f
            else -> 0f
        }
        return (duration + efficiency + deep + awakenings + latency).roundToInt().coerceIn(0, 100)
    }

    /**
     * Smart-alarm decision: true when the sleeper looks to be in light sleep or awake right now.
     * [recent] is the per-minute activity so far, [currentMinuteActive] the activity in the running minute.
     */
    fun isLightPhase(recent: List<Float>, currentMinuteActive: Float): Boolean {
        if (currentMinuteActive >= 2f) return true
        val n = recent.size
        if (n == 0) return false
        val w = floatArrayOf(1f, 0.7f, 0.5f)
        var sum = currentMinuteActive * 0.5f
        var wsum = 0.5f
        for (k in 0 until min(3, n)) {
            sum += recent[n - 1 - k] * w[k]
            wsum += w[k]
        }
        return sum / wsum >= LIGHT_THRESHOLD
    }
}

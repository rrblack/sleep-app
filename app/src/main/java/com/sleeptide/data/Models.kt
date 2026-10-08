package com.sleeptide.data

object Stage {
    const val AWAKE = 0
    const val LIGHT = 1
    const val DEEP = 2
}

object Mood {
    const val NONE = -1
    const val BAD = 0
    const val OK = 1
    const val GOOD = 2

    fun emoji(m: Int) = when (m) { BAD -> "😫"; OK -> "😐"; GOOD -> "😊"; else -> "" }
    fun label(m: Int) = when (m) { BAD -> "Tired"; OK -> "Okay"; GOOD -> "Rested"; else -> "Not set" }
}

data class SleepSession(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val alarmMode: AlarmMode,
    val alarmTargetMs: Long,
    val windowMin: Int,
    /** When the alarm actually went off (0 if no alarm rang). */
    val wokeMs: Long,
    val quality: Int,
    val asleepMin: Int,
    val deepMin: Int,
    val lightMin: Int,
    val awakeMin: Int,
    val latencyMin: Int,
    val awakenings: Int,
    val snoreSec: Int,
    val avgDb: Float,
    val mood: Int,
    val notes: List<String>,
    /** Per-minute movement/sound activity in "active seconds". */
    val activity: List<Float>,
    /** Per-minute seconds of snoring. */
    val snorePerMin: List<Int>,
    /** Per-minute average sound level (dBFS). */
    val noisePerMin: List<Float>,
    val stages: List<Int>,
    /** Per-minute sleep depth 0 (awake) .. 1 (deep), smoothed for display. */
    val depth: List<Float>,
    val inProgress: Boolean,
) {
    val inBedMin: Int get() = ((endMs - startMs) / 60_000L).toInt()
    val snoreMin: Int get() = (snoreSec + 30) / 60
}

data class SoundEventRecord(
    val id: Long,
    val sessionId: Long,
    val type: String, // SNORE or TALK
    val startMs: Long,
    val durationMs: Long,
    val peakDb: Float,
    val file: String?,
)

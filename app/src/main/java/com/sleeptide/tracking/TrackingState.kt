package com.sleeptide.tracking

import com.sleeptide.data.AlarmMode
import kotlinx.coroutines.flow.MutableStateFlow

data class LiveTracking(
    val sessionId: Long,
    val startMs: Long,
    val alarmMode: AlarmMode,
    val alarmTargetMs: Long,
    val windowStartMs: Long,
    val currentDb: Float,
    val snoring: Boolean,
    val snoreSec: Int,
    val minutes: Int,
    val activity: List<Float>,
    val micOk: Boolean,
    val sleepSoundPlaying: Boolean,
    val smartTriggered: Boolean,
    val snoozedUntil: Long,
)

/** In-process state shared between the services and the UI. */
object TrackingState {
    val live = MutableStateFlow<LiveTracking?>(null)
    val finishedSessionId = MutableStateFlow<Long?>(null)
    val message = MutableStateFlow<String?>(null)
}

object AlarmState {
    val ringing = MutableStateFlow(false)
    val snoozedUntil = MutableStateFlow(0L)
}

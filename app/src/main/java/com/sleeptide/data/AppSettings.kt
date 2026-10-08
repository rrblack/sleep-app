package com.sleeptide.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AlarmMode(val label: String) { SMART("Smart"), REGULAR("Regular"), OFF("No alarm") }

enum class SleepSound(val label: String) {
    OFF("Off"), RAIN("Rain"), OCEAN("Ocean waves"), WHITE("White noise"),
    PINK("Pink noise"), BROWN("Brown noise"), FAN("Fan")
}

val DEFAULT_TAGS = listOf(
    "Caffeine", "Alcohol", "Worked out", "Stressful day", "Ate late",
    "Screen time", "Meditated", "Sick", "Napped", "Shared bed"
)

data class AppSettings(
    // Alarm
    val alarmHour: Int = 7,
    val alarmMinute: Int = 0,
    val alarmMode: AlarmMode = AlarmMode.SMART,
    val wakeWindowMin: Int = 30,
    /** null = built-in gentle chime, otherwise a system ringtone URI. */
    val alarmSoundUri: String? = null,
    val alarmSoundName: String = "Gentle chime",
    val alarmVolume: Float = 1f,
    val vibrate: Boolean = true,
    val rampSeconds: Int = 60,
    val snoozeMinutes: Int = 9,
    // Sleep
    val sleepGoalMin: Int = 480,
    val bedtimeReminder: Boolean = false,
    val reminderHour: Int = 22,
    val reminderMinute: Int = 30,
    // Tracking
    val sensitivity: Int = 1, // 0 low, 1 medium, 2 high
    val useMotion: Boolean = true,
    val recordSounds: Boolean = true,
    val keepRecordingsDays: Int = 30, // 0 = forever
    // Sleep sounds
    val sleepSound: SleepSound = SleepSound.OFF,
    val sleepSoundMinutes: Int = 30,
    val sleepSoundVolume: Float = 0.5f,
    // Notes
    val noteTags: List<String> = DEFAULT_TAGS,
    val tonightNotes: Set<String> = emptySet(),
)

class SettingsRepository(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state
    val current: AppSettings get() = _state.value

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        save(next)
        _state.value = next
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            alarmHour = prefs.getInt("alarmHour", d.alarmHour),
            alarmMinute = prefs.getInt("alarmMinute", d.alarmMinute),
            alarmMode = enumOr(prefs.getString("alarmMode", null), d.alarmMode),
            wakeWindowMin = prefs.getInt("wakeWindowMin", d.wakeWindowMin),
            alarmSoundUri = prefs.getString("alarmSoundUri", null),
            alarmSoundName = prefs.getString("alarmSoundName", d.alarmSoundName) ?: d.alarmSoundName,
            alarmVolume = prefs.getFloat("alarmVolume", d.alarmVolume),
            vibrate = prefs.getBoolean("vibrate", d.vibrate),
            rampSeconds = prefs.getInt("rampSeconds", d.rampSeconds),
            snoozeMinutes = prefs.getInt("snoozeMinutes", d.snoozeMinutes),
            sleepGoalMin = prefs.getInt("sleepGoalMin", d.sleepGoalMin),
            bedtimeReminder = prefs.getBoolean("bedtimeReminder", d.bedtimeReminder),
            reminderHour = prefs.getInt("reminderHour", d.reminderHour),
            reminderMinute = prefs.getInt("reminderMinute", d.reminderMinute),
            sensitivity = prefs.getInt("sensitivity", d.sensitivity),
            useMotion = prefs.getBoolean("useMotion", d.useMotion),
            recordSounds = prefs.getBoolean("recordSounds", d.recordSounds),
            keepRecordingsDays = prefs.getInt("keepRecordingsDays", d.keepRecordingsDays),
            sleepSound = enumOr(prefs.getString("sleepSound", null), d.sleepSound),
            sleepSoundMinutes = prefs.getInt("sleepSoundMinutes", d.sleepSoundMinutes),
            sleepSoundVolume = prefs.getFloat("sleepSoundVolume", d.sleepSoundVolume),
            noteTags = prefs.getString("noteTags", null)?.let(::splitList) ?: d.noteTags,
            tonightNotes = prefs.getString("tonightNotes", null)?.let(::splitList)?.toSet() ?: d.tonightNotes,
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putInt("alarmHour", s.alarmHour)
            .putInt("alarmMinute", s.alarmMinute)
            .putString("alarmMode", s.alarmMode.name)
            .putInt("wakeWindowMin", s.wakeWindowMin)
            .putString("alarmSoundUri", s.alarmSoundUri)
            .putString("alarmSoundName", s.alarmSoundName)
            .putFloat("alarmVolume", s.alarmVolume)
            .putBoolean("vibrate", s.vibrate)
            .putInt("rampSeconds", s.rampSeconds)
            .putInt("snoozeMinutes", s.snoozeMinutes)
            .putInt("sleepGoalMin", s.sleepGoalMin)
            .putBoolean("bedtimeReminder", s.bedtimeReminder)
            .putInt("reminderHour", s.reminderHour)
            .putInt("reminderMinute", s.reminderMinute)
            .putInt("sensitivity", s.sensitivity)
            .putBoolean("useMotion", s.useMotion)
            .putBoolean("recordSounds", s.recordSounds)
            .putInt("keepRecordingsDays", s.keepRecordingsDays)
            .putString("sleepSound", s.sleepSound.name)
            .putInt("sleepSoundMinutes", s.sleepSoundMinutes)
            .putFloat("sleepSoundVolume", s.sleepSoundVolume)
            .putString("noteTags", joinList(s.noteTags))
            .putString("tonightNotes", joinList(s.tonightNotes.toList()))
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    companion object {
        private const val SEP = "\u001F"
        fun joinList(list: List<String>) = list.joinToString(SEP)
        fun splitList(s: String): List<String> = if (s.isEmpty()) emptyList() else s.split(SEP)
    }
}

/** Small bits of state that must survive process death (armed alarm, running session). */
class RuntimeState(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("runtime", Context.MODE_PRIVATE)

    var armedAlarmAt: Long
        get() = prefs.getLong("armedAlarmAt", 0L)
        set(v) = prefs.edit().putLong("armedAlarmAt", v).apply()

    var activeSessionId: Long
        get() = prefs.getLong("activeSessionId", 0L)
        set(v) = prefs.edit().putLong("activeSessionId", v).commit().let { }

    var lastWokenSessionId: Long
        get() = prefs.getLong("lastWokenSessionId", 0L)
        set(v) = prefs.edit().putLong("lastWokenSessionId", v).apply()
}

package com.sleeptide.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Locale

private class DbHelper(ctx: Context) : SQLiteOpenHelper(ctx, "sleeptide.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL DEFAULT 0,
                alarm_mode TEXT NOT NULL,
                alarm_target_ms INTEGER NOT NULL DEFAULT 0,
                window_min INTEGER NOT NULL DEFAULT 0,
                woke_ms INTEGER NOT NULL DEFAULT 0,
                quality INTEGER NOT NULL DEFAULT 0,
                asleep_min INTEGER NOT NULL DEFAULT 0,
                deep_min INTEGER NOT NULL DEFAULT 0,
                light_min INTEGER NOT NULL DEFAULT 0,
                awake_min INTEGER NOT NULL DEFAULT 0,
                latency_min INTEGER NOT NULL DEFAULT 0,
                awakenings INTEGER NOT NULL DEFAULT 0,
                snore_sec INTEGER NOT NULL DEFAULT 0,
                avg_db REAL NOT NULL DEFAULT -90,
                mood INTEGER NOT NULL DEFAULT -1,
                notes TEXT NOT NULL DEFAULT '',
                activity TEXT NOT NULL DEFAULT '',
                snore TEXT NOT NULL DEFAULT '',
                noise TEXT NOT NULL DEFAULT '',
                stages TEXT NOT NULL DEFAULT '',
                depth TEXT NOT NULL DEFAULT '',
                in_progress INTEGER NOT NULL DEFAULT 1
            )"""
        )
        db.execSQL(
            """CREATE TABLE events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                type TEXT NOT NULL,
                start_ms INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                peak_db REAL NOT NULL,
                file TEXT
            )"""
        )
        db.execSQL("CREATE INDEX idx_events_session ON events(session_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

/** All database access. Methods block, so call them off the main thread where possible. */
class SleepRepository(private val ctx: Context) {
    private val helper = DbHelper(ctx)
    private val _version = MutableStateFlow(0)

    /** Bumped on every write so screens can reload. */
    val version: StateFlow<Int> = _version

    private fun changed() = _version.update { it + 1 }

    fun createSession(startMs: Long, mode: AlarmMode, alarmTargetMs: Long, windowMin: Int, notes: List<String>): Long {
        val cv = ContentValues().apply {
            put("start_ms", startMs)
            put("end_ms", startMs)
            put("alarm_mode", mode.name)
            put("alarm_target_ms", alarmTargetMs)
            put("window_min", windowMin)
            put("notes", SettingsRepository.joinList(notes))
            put("in_progress", 1)
        }
        return helper.writableDatabase.insert("sessions", null, cv).also { changed() }
    }

    fun saveProgress(id: Long, activity: List<Float>, snore: List<Int>, noise: List<Float>) {
        val cv = ContentValues().apply {
            put("activity", encodeF(activity))
            put("snore", snore.joinToString(","))
            put("noise", encodeF(noise))
        }
        helper.writableDatabase.update("sessions", cv, "id=?", arrayOf(id.toString()))
    }

    /** Writes analysis results. Deliberately leaves mood and notes alone so a concurrent edit is kept. */
    fun saveResults(s: SleepSession) {
        val cv = ContentValues().apply {
            put("end_ms", s.endMs)
            put("woke_ms", s.wokeMs)
            put("quality", s.quality)
            put("asleep_min", s.asleepMin)
            put("deep_min", s.deepMin)
            put("light_min", s.lightMin)
            put("awake_min", s.awakeMin)
            put("latency_min", s.latencyMin)
            put("awakenings", s.awakenings)
            put("snore_sec", s.snoreSec)
            put("avg_db", s.avgDb)
            put("activity", encodeF(s.activity))
            put("snore", s.snorePerMin.joinToString(","))
            put("noise", encodeF(s.noisePerMin))
            put("stages", s.stages.joinToString(","))
            put("depth", encodeF(s.depth))
            put("in_progress", if (s.inProgress) 1 else 0)
        }
        helper.writableDatabase.update("sessions", cv, "id=?", arrayOf(s.id.toString()))
        changed()
    }

    fun get(id: Long): SleepSession? =
        helper.readableDatabase.query("sessions", null, "id=?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) readSession(c) else null
        }

    fun list(): List<SleepSession> =
        helper.readableDatabase.query("sessions", null, "in_progress=0", null, null, null, "start_ms DESC").use { c ->
            buildList { while (c.moveToNext()) add(readSession(c)) }
        }

    fun setMood(id: Long, mood: Int) {
        helper.writableDatabase.update("sessions", ContentValues().apply { put("mood", mood) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun setNotes(id: Long, notes: List<String>) {
        helper.writableDatabase.update(
            "sessions", ContentValues().apply { put("notes", SettingsRepository.joinList(notes)) },
            "id=?", arrayOf(id.toString())
        )
        changed()
    }

    fun delete(id: Long) {
        helper.writableDatabase.delete("events", "session_id=?", arrayOf(id.toString()))
        helper.writableDatabase.delete("sessions", "id=?", arrayOf(id.toString()))
        recordingsDir(id).deleteRecursively()
        changed()
    }

    fun deleteAll() {
        helper.writableDatabase.delete("events", null, null)
        helper.writableDatabase.delete("sessions", null, null)
        File(ctx.filesDir, "recordings").deleteRecursively()
        changed()
    }

    fun addEvent(e: SoundEventRecord): Long {
        val cv = ContentValues().apply {
            put("session_id", e.sessionId)
            put("type", e.type)
            put("start_ms", e.startMs)
            put("duration_ms", e.durationMs)
            put("peak_db", e.peakDb)
            put("file", e.file)
        }
        return helper.writableDatabase.insert("events", null, cv).also { changed() }
    }

    fun events(sessionId: Long): List<SoundEventRecord> =
        helper.readableDatabase.query("events", null, "session_id=?", arrayOf(sessionId.toString()), null, null, "start_ms ASC")
            .use { c -> buildList { while (c.moveToNext()) add(readEvent(c)) } }

    fun recordingsOlderThan(ms: Long): List<SoundEventRecord> =
        helper.readableDatabase.query("events", null, "file IS NOT NULL AND start_ms < ?", arrayOf(ms.toString()), null, null, null)
            .use { c -> buildList { while (c.moveToNext()) add(readEvent(c)) } }

    fun deleteRecording(e: SoundEventRecord) {
        e.file?.let { File(it).delete() }
        helper.writableDatabase.update("events", ContentValues().apply { putNull("file") }, "id=?", arrayOf(e.id.toString()))
        changed()
    }

    fun recordingsDir(sessionId: Long): File = File(ctx.filesDir, "recordings/$sessionId")

    private fun readSession(c: Cursor) = SleepSession(
        id = c.long("id"),
        startMs = c.long("start_ms"),
        endMs = c.long("end_ms"),
        alarmMode = AlarmMode.entries.firstOrNull { it.name == c.str("alarm_mode") } ?: AlarmMode.OFF,
        alarmTargetMs = c.long("alarm_target_ms"),
        windowMin = c.int("window_min"),
        wokeMs = c.long("woke_ms"),
        quality = c.int("quality"),
        asleepMin = c.int("asleep_min"),
        deepMin = c.int("deep_min"),
        lightMin = c.int("light_min"),
        awakeMin = c.int("awake_min"),
        latencyMin = c.int("latency_min"),
        awakenings = c.int("awakenings"),
        snoreSec = c.int("snore_sec"),
        avgDb = c.getFloat(c.getColumnIndexOrThrow("avg_db")),
        mood = c.int("mood"),
        notes = SettingsRepository.splitList(c.str("notes")),
        activity = decodeF(c.str("activity")),
        snorePerMin = decodeI(c.str("snore")),
        noisePerMin = decodeF(c.str("noise")),
        stages = decodeI(c.str("stages")),
        depth = decodeF(c.str("depth")),
        inProgress = c.int("in_progress") != 0,
    )

    private fun readEvent(c: Cursor) = SoundEventRecord(
        id = c.long("id"),
        sessionId = c.long("session_id"),
        type = c.str("type"),
        startMs = c.long("start_ms"),
        durationMs = c.long("duration_ms"),
        peakDb = c.getFloat(c.getColumnIndexOrThrow("peak_db")),
        file = c.getString(c.getColumnIndexOrThrow("file")),
    )

    private fun Cursor.long(col: String) = getLong(getColumnIndexOrThrow(col))
    private fun Cursor.int(col: String) = getInt(getColumnIndexOrThrow(col))
    private fun Cursor.str(col: String) = getString(getColumnIndexOrThrow(col)) ?: ""

    companion object {
        fun encodeF(list: List<Float>) = list.joinToString(",") { String.format(Locale.US, "%.2f", it) }
        fun decodeF(s: String): List<Float> = if (s.isBlank()) emptyList() else s.split(",").mapNotNull { it.toFloatOrNull() }
        fun decodeI(s: String): List<Int> = if (s.isBlank()) emptyList() else s.split(",").mapNotNull { it.toIntOrNull() }
    }
}

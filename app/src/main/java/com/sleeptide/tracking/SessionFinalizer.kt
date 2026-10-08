package com.sleeptide.tracking

import com.sleeptide.SleepTideApp

object SessionFinalizer {

    /** Runs sleep staging and scoring on a session's stored minute data and saves the results. */
    fun finalize(app: SleepTideApp, id: Long, endMs: Long, wokeMs: Long) {
        val s = app.repo.get(id) ?: return
        val result = SleepStager.analyze(s.activity)
        val inBed = s.activity.size
        val quality = SleepStager.quality(result, inBed, app.settings.current.sleepGoalMin)
        val loud = s.noisePerMin.filter { it > -100f }
        app.repo.saveResults(
            s.copy(
                endMs = endMs,
                wokeMs = wokeMs,
                quality = quality,
                asleepMin = result.asleepMin,
                deepMin = result.deepMin,
                lightMin = result.lightMin,
                awakeMin = result.awakeMin,
                latencyMin = result.latencyMin,
                awakenings = result.awakenings,
                snoreSec = s.snorePerMin.sum(),
                avgDb = if (loud.isEmpty()) -90f else loud.average().toFloat(),
                stages = result.stages,
                depth = result.depth,
                inProgress = false,
            )
        )
    }

    /** A session left "in progress" by a crash or reboot is closed using the minutes saved so far. */
    fun repairStale(app: SleepTideApp) {
        val id = app.runtime.activeSessionId
        if (id == 0L || SleepTrackingService.isRunning) return
        val s = app.repo.get(id)
        if (s == null) {
            app.runtime.activeSessionId = 0L
            return
        }
        if (s.activity.size < 10) app.repo.delete(id)
        else finalize(app, id, s.startMs + s.activity.size * 60_000L, 0L)
        app.runtime.activeSessionId = 0L
    }

    fun cleanupRecordings(app: SleepTideApp) {
        val days = app.settings.current.keepRecordingsDays
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        app.repo.recordingsOlderThan(cutoff).forEach { app.repo.deleteRecording(it) }
    }
}

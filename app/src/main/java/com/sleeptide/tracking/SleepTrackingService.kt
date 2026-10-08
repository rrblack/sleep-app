package com.sleeptide.tracking

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sleeptide.Notifications
import com.sleeptide.R
import com.sleeptide.alarm.AlarmScheduler
import com.sleeptide.app
import com.sleeptide.data.AlarmMode
import com.sleeptide.data.SleepSound
import com.sleeptide.data.SoundEventRecord
import com.sleeptide.util.Fmt
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Foreground service that listens through the night.
 *
 * Every minute it stores how much movement/noise and snoring it heard. During the smart-alarm
 * window it checks whether you are in light sleep and, if so, fires the alarm early.
 */
class SleepTrackingService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "com.sleeptide.action.START_TRACKING"
        const val ACTION_STOP = "com.sleeptide.action.STOP_TRACKING"
        const val ACTION_DISCARD = "com.sleeptide.action.DISCARD_TRACKING"
        const val EXTRA_FROM_ALARM = "from_alarm"
        private const val SAMPLE_RATE = 16000
        private const val MIN_SESSION_MIN = 10
        private const val MAX_SNORE_CLIPS = 15
        private const val MAX_TALK_CLIPS = 20

        @Volatile
        var isRunning = false
            private set

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, SleepTrackingService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context, fromAlarm: Boolean = false) {
            if (!isRunning) return
            ctx.startService(
                Intent(ctx, SleepTrackingService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_FROM_ALARM, fromAlarm)
            )
        }

        fun discard(ctx: Context) {
            if (!isRunning) return
            ctx.startService(Intent(ctx, SleepTrackingService::class.java).setAction(ACTION_DISCARD))
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var workerThread: HandlerThread? = null
    private lateinit var worker: Handler
    private var wakeLock: PowerManager.WakeLock? = null

    private val lock = Any()
    private lateinit var analyzer: AudioAnalyzer
    private var clipRecorder: ClipRecorder? = null
    private var audioThread: Thread? = null
    @Volatile private var audioRunning = false
    @Volatile private var micOk = false
    private var noisePlayer: NoisePlayer? = null
    private var sensorManager: SensorManager? = null

    private var sessionId = 0L
    private var startMs = 0L
    private var alarmMode = AlarmMode.OFF
    private var alarmTarget = 0L
    private var windowStart = 0L
    @Volatile private var smartTriggered = false
    private var smartWokeAt = 0L
    private var stopping = false

    // Written only on the worker thread.
    private val activity = ArrayList<Float>()
    private val snore = ArrayList<Int>()
    private val noise = ArrayList<Float>()
    private var minuteStartMs = 0L
    private var snoreSecTotal = 0f
    private var tick = 0

    // Motion (worker thread)
    private var gravity = 0f
    private var gravityInit = false
    private var motionThreshold = 0.08f
    private val motionSeconds = HashSet<Long>()

    // Clips (audio thread)
    private var snoreClips = 0
    private var talkClips = 0
    private var lastSnoreClipMs = 0L
    private var lastTalkClipMs = 0L
    @Volatile private var pendingSnoreClip: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!isRunning) begin()
            ACTION_STOP -> if (isRunning) end(save = true, fromAlarm = intent.getBooleanExtra(EXTRA_FROM_ALARM, false))
            ACTION_DISCARD -> if (isRunning) end(save = false, fromAlarm = false)
            else -> if (!isRunning) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        val settings = app.settings.current
        startMs = System.currentTimeMillis()
        alarmMode = settings.alarmMode
        alarmTarget = if (alarmMode == AlarmMode.OFF) 0L
        else AlarmScheduler.nextOccurrence(settings.alarmHour, settings.alarmMinute, startMs)
        windowStart = if (alarmMode == AlarmMode.SMART) alarmTarget - settings.wakeWindowMin * 60_000L else alarmTarget

        try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, Notifications.ID_TRACKING, buildNotification(), type)
        } catch (e: Exception) {
            TrackingState.message.value = "Could not start tracking: ${e.message}"
            stopSelf()
            return
        }
        isRunning = true

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepTide:tracking")
            .apply { acquire(16 * 60 * 60 * 1000L) }

        workerThread = HandlerThread("sleep-tracking").also { it.start() }
        worker = Handler(workerThread!!.looper)

        sessionId = app.repo.createSession(
            startMs, alarmMode, alarmTarget,
            if (alarmMode == AlarmMode.SMART) settings.wakeWindowMin else 0,
            settings.tonightNotes.toList()
        )
        app.runtime.activeSessionId = sessionId
        app.settings.update { it.copy(tonightNotes = emptySet()) }
        AlarmState.snoozedUntil.value = 0L

        if (alarmMode != AlarmMode.OFF) AlarmScheduler.schedule(this, alarmTarget)

        analyzer = AudioAnalyzer(SAMPLE_RATE, settings.sensitivity).also { it.listener = analyzerListener }
        if (settings.recordSounds) clipRecorder = ClipRecorder(SAMPLE_RATE, 6)
        startAudio()

        motionThreshold = when (settings.sensitivity) { 0 -> 0.12f; 2 -> 0.05f; else -> 0.08f }
        if (settings.useMotion) {
            sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
            sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager?.registerListener(this, it, 50_000, worker)
            }
        }

        if (settings.sleepSound != SleepSound.OFF) {
            synchronized(lock) { analyzer.suppressEvents = true }
            noisePlayer = NoisePlayer(settings.sleepSound, settings.sleepSoundVolume).also {
                it.start(settings.sleepSoundMinutes * 60_000L) {
                    synchronized(lock) { analyzer.suppressEvents = false }
                }
            }
        }

        minuteStartMs = startMs
        worker.postDelayed(minuteTick, 60_000)
        worker.post(liveTick)
    }

    private fun startAudio() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, SAMPLE_RATE * 2)
            )
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            micOk = false
            return
        }
        micOk = true
        audioRunning = true
        audioThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val frame = ShortArray(AudioAnalyzer.FRAME)
            var samples = 0L
            val audioStart = System.currentTimeMillis()
            try {
                record.startRecording()
                loop@ while (audioRunning) {
                    var off = 0
                    while (off < frame.size) {
                        if (!audioRunning) break@loop
                        val n = record.read(frame, off, frame.size - off)
                        if (n < 0) { micOk = false; break@loop }
                        off += n
                    }
                    val t = audioStart + samples * 1000 / SAMPLE_RATE
                    samples += frame.size
                    synchronized(lock) { analyzer.process(frame, t) }
                    clipRecorder?.feed(frame, frame.size)
                }
            } catch (e: Exception) {
                micOk = false
            } finally {
                try { record.stop() } catch (_: Exception) {}
                record.release()
            }
        }, "sleep-audio").apply { start() }
    }

    private val analyzerListener = object : AudioAnalyzer.Listener {
        override fun onSnoreEpisodeStart(timeMs: Long) {
            val rec = clipRecorder ?: return
            if (snoreClips >= MAX_SNORE_CLIPS || timeMs - lastSnoreClipMs < 10 * 60_000L) return
            val file = clipFile("snore", timeMs)
            if (rec.start(file, 10)) {
                snoreClips++
                lastSnoreClipMs = timeMs
                pendingSnoreClip = file.absolutePath
            }
        }

        override fun onEvent(event: AudioAnalyzer.Event) {
            when (event.type) {
                AudioAnalyzer.EventType.SNORE -> {
                    val clip = pendingSnoreClip
                    pendingSnoreClip = null
                    app.repo.addEvent(
                        SoundEventRecord(0, sessionId, "SNORE", event.startMs, event.durationMs, event.peakDb, clip)
                    )
                }
                AudioAnalyzer.EventType.TALK -> {
                    val rec = clipRecorder ?: return
                    if (talkClips >= MAX_TALK_CLIPS || event.startMs - lastTalkClipMs < 60_000L) return
                    val file = clipFile("talk", event.startMs)
                    if (rec.start(file, 3)) {
                        talkClips++
                        lastTalkClipMs = event.startMs
                        app.repo.addEvent(
                            SoundEventRecord(0, sessionId, "TALK", event.startMs, event.durationMs, event.peakDb, file.absolutePath)
                        )
                    }
                }
                AudioAnalyzer.EventType.NOISE -> Unit
            }
        }
    }

    private fun clipFile(kind: String, t: Long) = File(app.repo.recordingsDir(sessionId), "${kind}_$t.wav")

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val mag = sqrt(x * x + y * y + z * z)
        if (!gravityInit) {
            gravity = mag
            gravityInit = true
            return
        }
        gravity += (mag - gravity) * 0.02f
        if (abs(mag - gravity) > motionThreshold) motionSeconds.add(event.timestamp / 1_000_000_000L)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private val minuteTick = object : Runnable {
        override fun run() {
            closeMinute()
            minuteStartMs += 60_000L
            val delay = max(1000L, minuteStartMs + 60_000L - System.currentTimeMillis())
            worker.postDelayed(this, delay)
        }
    }

    private fun closeMinute() {
        val stats = synchronized(lock) { analyzer.drainMinute() }
        val motion = motionSeconds.size
        motionSeconds.clear()
        activity.add(min(60f, stats.activeSec + motion))
        snore.add(stats.snoreSec.roundToInt().coerceIn(0, 60))
        noise.add(stats.avgDb)
        snoreSecTotal += stats.snoreSec
        app.repo.saveProgress(sessionId, activity, snore, noise)
    }

    private val liveTick = object : Runnable {
        override fun run() {
            tick++
            val now = System.currentTimeMillis()
            if (tick % 15 == 0) checkSmartWake(now)
            publishLive()
            if (tick % 60 == 0) updateNotification()
            worker.postDelayed(this, 1000)
        }
    }

    private fun checkSmartWake(now: Long) {
        if (alarmMode != AlarmMode.SMART || smartTriggered) return
        if (now < windowStart || now >= alarmTarget - 60_000L) return
        val partial = synchronized(lock) { analyzer.peekActiveSec() } + motionSeconds.size
        if (SleepStager.isLightPhase(activity, partial)) {
            smartTriggered = true
            smartWokeAt = now
            AlarmScheduler.schedule(this, now + 1000)
        }
    }

    private fun publishLive() {
        val (db, snoring, suppress) = synchronized(lock) { Triple(analyzer.currentDb, analyzer.snoring, analyzer.suppressEvents) }
        TrackingState.live.value = LiveTracking(
            sessionId = sessionId,
            startMs = startMs,
            alarmMode = alarmMode,
            alarmTargetMs = alarmTarget,
            windowStartMs = windowStart,
            currentDb = db,
            snoring = snoring,
            snoreSec = snoreSecTotal.roundToInt(),
            minutes = activity.size,
            activity = activity.toList(),
            micOk = micOk,
            sleepSoundPlaying = suppress && noisePlayer?.isPlaying == true,
            smartTriggered = smartTriggered,
            snoozedUntil = AlarmState.snoozedUntil.value,
        )
    }

    private fun buildNotification(): Notification {
        val text = when (alarmMode) {
            AlarmMode.SMART -> "Smart alarm ${Fmt.time(this, windowStart)} – ${Fmt.time(this, alarmTarget)}"
            AlarmMode.REGULAR -> "Alarm at ${Fmt.time(this, alarmTarget)}"
            AlarmMode.OFF -> "No alarm set"
        }
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, SleepTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Notifications.CH_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle("Tracking your sleep")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(Notifications.openAppIntent(this))
            .addAction(0, "End session", stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        try {
            androidx.core.app.NotificationManagerCompat.from(this).notify(Notifications.ID_TRACKING, buildNotification())
        } catch (_: SecurityException) {
        }
    }

    private fun end(save: Boolean, fromAlarm: Boolean) {
        if (stopping) return
        stopping = true
        worker.removeCallbacksAndMessages(null)
        worker.post {
            noisePlayer?.stop()
            audioRunning = false
            audioThread?.join(3000)
            sensorManager?.unregisterListener(this)
            synchronized(lock) { analyzer.flush() }
            clipRecorder?.close()

            val now = System.currentTimeMillis()
            if (now - minuteStartMs >= 20_000L) closeMinute()
            if (!fromAlarm) AlarmScheduler.cancel(this)

            var finished: Long? = null
            if (!save) {
                app.repo.delete(sessionId)
                TrackingState.message.value = "Sleep session discarded"
            } else if (activity.size < MIN_SESSION_MIN) {
                app.repo.delete(sessionId)
                TrackingState.message.value = "Session was shorter than $MIN_SESSION_MIN minutes, so it wasn't saved"
            } else {
                app.repo.saveProgress(sessionId, activity, snore, noise)
                SessionFinalizer.finalize(app, sessionId, now, if (fromAlarm) now else 0L)
                finished = sessionId
            }
            app.runtime.activeSessionId = 0L
            SessionFinalizer.cleanupRecordings(app)

            mainHandler.post {
                TrackingState.live.value = null
                if (finished != null) TrackingState.finishedSessionId.value = finished
                shutdown()
            }
        }
    }

    private fun shutdown() {
        isRunning = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        wakeLock?.let { if (it.isHeld) it.release() }
        workerThread?.quitSafely()
        stopSelf()
    }

    override fun onDestroy() {
        if (isRunning && !stopping) {
            // Killed unexpectedly: stop sensors and keep what we have. The app repairs the session on next launch.
            audioRunning = false
            noisePlayer?.stop()
            sensorManager?.unregisterListener(this)
            clipRecorder?.close()
            wakeLock?.let { if (it.isHeld) it.release() }
            workerThread?.quitSafely()
            TrackingState.live.value = null
            isRunning = false
        }
        super.onDestroy()
    }
}

package com.sleeptide.alarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.sleeptide.Notifications
import com.sleeptide.R
import com.sleeptide.app
import com.sleeptide.tracking.AlarmState
import com.sleeptide.tracking.SleepTrackingService
import com.sleeptide.util.Fmt
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Rings the alarm: plays sound with a volume ramp, vibrates, and handles snooze/stop. */
class AlarmService : Service() {

    companion object {
        const val ACTION_RING = "com.sleeptide.action.RING"
        const val ACTION_SNOOZE = "com.sleeptide.action.SNOOZE"
        const val ACTION_DISMISS = "com.sleeptide.action.DISMISS"
        private const val MAX_RING_MS = 15 * 60_000L

        fun snooze(ctx: Context) = ctx.startService(Intent(ctx, AlarmService::class.java).setAction(ACTION_SNOOZE))
        fun dismiss(ctx: Context) = ctx.startService(Intent(ctx, AlarmService::class.java).setAction(ACTION_DISMISS))
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var chime: ChimePlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringStart = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> startRinging()
            ACTION_SNOOZE -> snooze()
            ACTION_DISMISS -> dismiss()
            else -> if (!AlarmState.ringing.value) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRinging() {
        if (AlarmState.ringing.value) return
        val notification = buildNotification()
        val started = try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifications.ID_ALARM, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else {
                startForeground(Notifications.ID_ALARM, notification)
            }
            true
        } catch (e: Exception) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(Notifications.ID_ALARM, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    true
                } else false
            } catch (e2: Exception) {
                false
            }
        }
        if (!started) {
            stopSelf()
            return
        }
        AlarmState.ringing.value = true
        AlarmState.snoozedUntil.value = 0L
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepTide:alarm")
            .apply { acquire(MAX_RING_MS + 60_000L) }
        ringStart = System.currentTimeMillis()
        startSound()
        startVibration()
        handler.post(ramp)
        handler.postDelayed({ dismiss() }, MAX_RING_MS)
        try {
            startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // Background activity starts may be blocked; the full-screen notification covers that case.
        }
    }

    private fun buildNotification(): Notification {
        val s = app.settings.current
        val fullScreen = PendingIntent.getActivity(
            this, 10, Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snoozePi = PendingIntent.getService(
            this, 11, Intent(this, AlarmService::class.java).setAction(ACTION_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 12, Intent(this, AlarmService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Notifications.CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle("Good morning")
            .setContentText("Alarm · ${Fmt.time(this, System.currentTimeMillis())}")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(0, "Snooze ${s.snoozeMinutes} min", snoozePi)
            .addAction(0, "Stop", stopPi)
            .build()
    }

    private fun startSound() {
        val s = app.settings.current
        val uri = s.alarmSoundUri?.let { Uri.parse(it) }
        if (uri != null) {
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(this@AlarmService, uri)
                    isLooping = true
                    setVolume(0.05f, 0.05f)
                    prepare()
                    start()
                }
                return
            } catch (e: Exception) {
                player?.release()
                player = null
            }
        }
        chime = ChimePlayer().also { it.start() }
    }

    private val ramp = object : Runnable {
        override fun run() {
            val s = app.settings.current
            val elapsed = (System.currentTimeMillis() - ringStart) / 1000f
            val progress = if (s.rampSeconds <= 0) 1f else min(1f, elapsed / s.rampSeconds)
            val v = (0.05f + 0.95f * progress * progress) * s.alarmVolume
            player?.setVolume(v, v)
            chime?.volume = v
            if (progress < 1f) handler.postDelayed(this, 250)
        }
    }

    private fun startVibration() {
        if (!app.settings.current.vibrate) return
        val v = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator = v
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 900), 0)
        @Suppress("DEPRECATION")
        v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
    }

    private fun stopRinging() {
        handler.removeCallbacksAndMessages(null)
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
        chime?.stop()
        chime = null
        vibrator?.cancel()
        vibrator = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun snooze() {
        if (!AlarmState.ringing.value) { stopSelf(); return }
        stopRinging()
        val at = System.currentTimeMillis() + app.settings.current.snoozeMinutes * 60_000L
        AlarmScheduler.schedule(this, at)
        AlarmState.snoozedUntil.value = at
        AlarmState.ringing.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun dismiss() {
        stopRinging()
        AlarmScheduler.cancel(this)
        AlarmState.snoozedUntil.value = 0L
        if (SleepTrackingService.isRunning) {
            app.runtime.lastWokenSessionId = app.runtime.activeSessionId
            SleepTrackingService.stop(this, fromAlarm = true)
        }
        AlarmState.ringing.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopRinging()
        AlarmState.ringing.value = false
        super.onDestroy()
    }
}

/** Built-in alarm tone: a soft rising arpeggio of bell-like tones, synthesized on the fly. */
class ChimePlayer {
    @Volatile var volume = 0.05f
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        val sr = 44100
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(max(minBuf, sr / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        running = true
        thread = Thread({
            // C5 E5 G5 C6 E6 G6, then a pause; the whole phrase repeats.
            val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.5, 1318.5, 1568.0)
            val noteLen = (0.32 * sr).toInt()
            val phrase = noteLen * notes.size + sr * 2
            val buf = ShortArray(2048)
            var n = 0L
            try {
                track.play()
                while (running) {
                    for (i in buf.indices) {
                        val pos = (n % phrase).toInt()
                        var sample = 0.0
                        // Let each bell ring on after the next note starts.
                        for (k in notes.indices) {
                            val start = k * noteLen
                            if (pos < start) continue
                            val t = (pos - start).toDouble() / sr
                            if (t > 2.5) continue
                            val env = exp(-t * 2.2) * min(1.0, t * 200)
                            sample += env * (sin(2 * PI * notes[k] * t) + 0.3 * sin(4 * PI * notes[k] * t)) * 0.22
                        }
                        buf[i] = (sample.coerceIn(-1.0, 1.0) * volume * 32000).toInt().toShort()
                        n++
                    }
                    track.write(buf, 0, buf.size)
                }
                track.stop()
            } catch (_: Exception) {
            } finally {
                track.release()
            }
        }, "alarm-chime").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.join(300)
        thread = null
    }
}

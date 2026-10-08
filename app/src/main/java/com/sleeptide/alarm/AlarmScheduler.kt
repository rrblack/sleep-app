package com.sleeptide.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.sleeptide.Notifications
import com.sleeptide.app
import com.sleeptide.tracking.SleepTrackingService
import java.util.Calendar

object AlarmScheduler {
    private const val ACTION_FIRE = "com.sleeptide.action.ALARM_FIRE"

    /** Next time the clock shows [hour]:[minute], strictly after [now]. */
    fun nextOccurrence(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun firePendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 100, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Uses setAlarmClock: exact, allowed in Doze, and shows the alarm icon in the status bar. */
    fun schedule(ctx: Context, triggerAt: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = firePendingIntent(ctx)
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, Notifications.openAppIntent(ctx)), pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
        ctx.app.runtime.armedAlarmAt = triggerAt
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(firePendingIntent(ctx))
        ctx.app.runtime.armedAlarmAt = 0L
    }

    fun canScheduleExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.app.runtime.armedAlarmAt = 0L
        ContextCompat.startForegroundService(
            context, Intent(context, AlarmService::class.java).setAction(AlarmService.ACTION_RING)
        )
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.app
        val armed = app.runtime.armedAlarmAt
        if (armed > System.currentTimeMillis()) AlarmScheduler.schedule(context, armed)
        else if (armed != 0L && !SleepTrackingService.isRunning) app.runtime.armedAlarmAt = 0L
        Reminders.schedule(context)
    }
}

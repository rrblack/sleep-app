package com.sleeptide.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sleeptide.Notifications
import com.sleeptide.R
import com.sleeptide.app
import com.sleeptide.data.AlarmMode
import com.sleeptide.tracking.SleepTrackingService
import com.sleeptide.util.Fmt

object Reminders {
    private fun pendingIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 200, Intent(ctx, ReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** (Re)schedules the daily bedtime reminder according to settings. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(ctx)
        am.cancel(pi)
        val s = ctx.app.settings.current
        if (!s.bedtimeReminder) return
        val at = AlarmScheduler.nextOccurrence(s.reminderHour, s.reminderMinute)
        am.setWindow(AlarmManager.RTC_WAKEUP, at, 5 * 60_000L, pi)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = context.app.settings.current
        if (s.bedtimeReminder && !SleepTrackingService.isRunning) {
            val text = if (s.alarmMode != AlarmMode.OFF) {
                "Go to bed soon to get ${Fmt.duration(s.sleepGoalMin)} of sleep before your ${Fmt.time(context, s.alarmHour, s.alarmMinute)} alarm."
            } else {
                "Time to wind down. Your sleep goal is ${Fmt.duration(s.sleepGoalMin)}."
            }
            val n = NotificationCompat.Builder(context, Notifications.CH_REMINDER)
                .setSmallIcon(R.drawable.ic_stat_moon)
                .setContentTitle("Bedtime soon")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(Notifications.openAppIntent(context))
                .build()
            try {
                NotificationManagerCompat.from(context).notify(Notifications.ID_REMINDER, n)
            } catch (_: SecurityException) {
            }
        }
        Reminders.schedule(context)
    }
}

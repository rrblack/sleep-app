package com.sleeptide

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifications {
    const val CH_TRACKING = "tracking"
    const val CH_ALARM = "alarm"
    const val CH_REMINDER = "reminder"

    const val ID_TRACKING = 1
    const val ID_ALARM = 2
    const val ID_REMINDER = 3

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val tracking = NotificationChannel(CH_TRACKING, "Sleep tracking", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while your sleep is being tracked"
            setShowBadge(false)
        }
        val alarm = NotificationChannel(CH_ALARM, "Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Wake-up alarm"
            setSound(null, null) // the alarm service plays the sound itself
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val reminder = NotificationChannel(CH_REMINDER, "Bedtime reminder", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Reminds you when it is time to go to bed"
        }
        nm.createNotificationChannels(listOf(tracking, alarm, reminder))
    }

    fun openAppIntent(ctx: Context, sessionId: Long? = null): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (sessionId != null) putExtra(MainActivity.EXTRA_OPEN_SESSION, sessionId)
        }
        return PendingIntent.getActivity(
            ctx, (sessionId ?: 0L).toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

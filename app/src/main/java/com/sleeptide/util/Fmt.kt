package com.sleeptide.util

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Fmt {
    fun duration(min: Int): String {
        val h = min / 60
        val m = min % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    fun time(ctx: Context, ms: Long): String = DateFormat.getTimeFormat(ctx).format(Date(ms))

    fun time(ctx: Context, hour: Int, minute: Int): String {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        return time(ctx, c.timeInMillis)
    }

    fun day(ms: Long): String = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(ms))
    fun weekday(ms: Long): String = SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ms))
    fun shortDate(ms: Long): String = SimpleDateFormat("M/d", Locale.getDefault()).format(Date(ms))
    fun longDate(ms: Long): String = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(ms))

    /** Converts dBFS from the phone microphone to an approximate sound pressure level. */
    fun approxSpl(dbfs: Float): Int = (dbfs + 118f).toInt().coerceAtLeast(0)
}

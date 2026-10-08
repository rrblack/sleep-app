package com.sleeptide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sleeptide.data.Mood
import com.sleeptide.data.SleepSession
import com.sleeptide.util.Fmt
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val PERIODS = listOf("Week" to 7, "Month" to 30, "3 months" to 90, "All" to 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen() {
    val app = rememberApp()
    val ctx = LocalContext.current
    val all by rememberRepoData { repo.list() }
    var period by rememberSaveable { mutableIntStateOf(0) }
    val days = PERIODS[period].second
    val cutoff = if (days == 0) 0L else System.currentTimeMillis() - days * 24L * 3600_000L
    val nights = remember(all, period) { all.orEmpty().filter { it.startMs >= cutoff }.sortedBy { it.startMs } }
    val goal = app.settings.current.sleepGoalMin

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Statistics", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PERIODS.forEachIndexed { i, (label, _) ->
                SegmentedButton(
                    selected = period == i,
                    onClick = { period = i },
                    shape = SegmentedButtonDefaults.itemShape(i, PERIODS.size)
                ) { Text(label) }
            }
        }
        if (nights.isEmpty()) {
            Text("No nights in this period yet.", color = Palette.TextDim, modifier = Modifier.padding(top = 24.dp))
            return@Column
        }

        val avgQuality = nights.map { it.quality }.average().roundToInt()
        val avgAsleep = nights.map { it.asleepMin }.average().roundToInt()
        val avgInBed = nights.map { it.inBedMin }.average().roundToInt()
        val bedtimes = nights.map { minutesFromNoon(it.startMs) }
        val wakeTimes = nights.map { minutesOfDay(it.endMs) }
        val avgBed = bedtimes.average()
        val regularity = sqrt(bedtimes.map { (it - avgBed) * (it - avgBed) }.average()).roundToInt()
        val snoreNights = nights.count { it.snoreMin > 0 }
        val avgSnore = nights.map { it.snoreMin }.average().roundToInt()

        SectionCard {
            CardTitle("Averages · ${nights.size} night${if (nights.size == 1) "" else "s"}")
            StatRow("Sleep quality" to "$avgQuality%", "Time asleep" to Fmt.duration(avgAsleep))
            StatRow("Time in bed" to Fmt.duration(avgInBed), "Snoring" to if (avgSnore > 0) Fmt.duration(avgSnore) else "None")
            StatRow(
                "Went to bed" to Fmt.time(ctx, ((avgBed.roundToInt() + 720) % 1440) / 60, ((avgBed.roundToInt() + 720) % 1440) % 60),
                "Woke up" to wakeTimes.average().roundToInt().let { Fmt.time(ctx, it / 60, it % 60) }
            )
            StatRow("Bedtime regularity" to "±${Fmt.duration(regularity)}", "Nights with snoring" to "$snoreNights of ${nights.size}")
        }

        val grouped = group(nights)
        SectionCard {
            CardTitle("Sleep quality")
            BarChart(grouped.map { g -> g.second.map { it.quality }.average().toFloat() }, grouped.map { it.first }, Palette.Lavender, maxValue = 100f, valueLabel = { "${it.roundToInt()}" })
        }
        SectionCard {
            CardTitle("Time asleep (hours)")
            BarChart(
                grouped.map { g -> g.second.map { it.asleepMin }.average().toFloat() / 60f },
                grouped.map { it.first }, Palette.Teal, goal = goal / 60f,
                valueLabel = { String.format(java.util.Locale.US, "%.1f", it) }
            )
            Text("Dashed line: your ${Fmt.duration(goal)} goal", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        SectionCard {
            CardTitle("Snoring (minutes)")
            BarChart(grouped.map { g -> g.second.map { it.snoreMin }.average().toFloat() }, grouped.map { it.first }, Palette.Rose, valueLabel = { "${it.roundToInt()}" })
        }

        val impacts = noteImpacts(nights)
        SectionCard {
            CardTitle("How sleep notes affect you")
            if (impacts.isEmpty()) {
                Text("Add sleep notes before bed. Once some nights have a note and some don't, you'll see how each one changes your sleep quality.", color = Palette.TextDim)
            }
            impacts.forEach { (tag, diff, count) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(tag, style = MaterialTheme.typography.titleSmall)
                        Text("$count night${if (count == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    }
                    Text(
                        "${if (diff >= 0) "+" else "−"}${abs(diff)}% quality",
                        color = if (diff >= 0) Palette.Teal else Palette.Sunrise,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }

        val moods = nights.filter { it.mood != Mood.NONE }.groupBy { it.mood }
        if (moods.isNotEmpty()) {
            SectionCard {
                CardTitle("Wake-up mood")
                listOf(Mood.GOOD, Mood.OK, Mood.BAD).forEach { m ->
                    val list = moods[m] ?: return@forEach
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("${Mood.emoji(m)}  ${Mood.label(m)}", modifier = Modifier.weight(1f))
                        Text("${list.size}× · avg ${list.map { it.quality }.average().roundToInt()}% quality", color = Palette.TextDim)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatRow(a: Pair<String, String>, b: Pair<String, String>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        listOf(a, b).forEach { (label, value) ->
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
                Text(value, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun minutesOfDay(ms: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
}

/** Bedtimes straddle midnight, so average them on a noon-to-noon scale. */
private fun minutesFromNoon(ms: Long): Double = ((minutesOfDay(ms) + 720) % 1440).toDouble()

/** One bar per night for up to a month, otherwise one bar per week. */
private fun group(nights: List<SleepSession>): List<Pair<String, List<SleepSession>>> {
    if (nights.size <= 31) {
        return nights.map { (if (nights.size <= 7) Fmt.weekday(it.startMs) else Fmt.shortDate(it.startMs)) to listOf(it) }
    }
    return nights.groupBy {
        val c = Calendar.getInstance().apply {
            timeInMillis = it.startMs
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        c.timeInMillis
    }.toSortedMap().map { (week, list) -> Fmt.shortDate(week) to list }
}

private fun noteImpacts(nights: List<SleepSession>): List<Triple<String, Int, Int>> {
    val tags = nights.flatMap { it.notes }.distinct()
    return tags.mapNotNull { tag ->
        val with = nights.filter { tag in it.notes }
        val without = nights.filter { tag !in it.notes }
        if (with.isEmpty() || without.isEmpty()) return@mapNotNull null
        val diff = (with.map { it.quality }.average() - without.map { it.quality }.average()).roundToInt()
        Triple(tag, diff, with.size)
    }.sortedByDescending { abs(it.second) }
}

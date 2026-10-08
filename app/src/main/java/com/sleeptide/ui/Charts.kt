package com.sleeptide.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleeptide.data.SleepSession
import com.sleeptide.data.SoundEventRecord
import com.sleeptide.util.Fmt
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

fun qualityColor(q: Int): Color = when {
    q >= 75 -> Palette.Teal
    q >= 50 -> Palette.Lavender
    else -> Palette.Sunrise
}

@Composable
fun QualityRing(quality: Int, size: Dp, stroke: Dp = size / 10, showLabel: Boolean = true) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val sw = stroke.toPx()
            val inset = sw / 2
            val arcSize = Size(this.size.width - sw, this.size.height - sw)
            drawArc(Palette.Surface2, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(sw))
            drawArc(
                brush = Brush.sweepGradient(listOf(Palette.Lavender, Palette.Teal, Palette.Lavender)),
                startAngle = -90f, sweepAngle = 360f * quality.coerceIn(0, 100) / 100f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(sw, cap = StrokeCap.Round)
            )
        }
        if (showLabel) {
            Text(
                "$quality%",
                fontSize = (size.value * 0.24f).sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** Sleep Cycle style graph: awake at the top, deep sleep at the bottom, snoring as red bars. */
@Composable
fun SleepGraph(session: SleepSession, events: List<SoundEventRecord>, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Palette.TextDim, fontSize = 10.sp)
    Canvas(modifier.fillMaxWidth().height(210.dp)) {
        val depth = session.depth
        val n = depth.size
        val leftPad = 44.dp.toPx()
        val bottomPad = 22.dp.toPx()
        val top = 8.dp.toPx()
        val w = size.width - leftPad
        val h = size.height - bottomPad - top
        val levels = listOf("Awake" to 0f, "Sleep" to 0.5f, "Deep" to 1f)
        levels.forEach { (label, y) ->
            val py = top + y * h
            drawLine(
                Palette.Outline, Offset(leftPad, py), Offset(size.width, py), 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
            )
            val layout = measurer.measure(label, labelStyle)
            drawText(layout, topLeft = Offset(0f, py - layout.size.height / 2f))
        }
        if (n < 2) return@Canvas
        val xOf = { i: Int -> leftPad + w * i / (n - 1).toFloat() }

        // Snoring bars along the bottom.
        session.snorePerMin.forEachIndexed { i, sec ->
            if (sec > 0 && i < n) {
                val bh = h * 0.35f * min(1f, sec / 60f)
                drawRect(
                    Palette.Snore.copy(alpha = 0.55f),
                    topLeft = Offset(xOf(i), top + h - bh),
                    size = Size(max(2f, w / n), bh)
                )
            }
        }

        val path = Path()
        val fill = Path()
        depth.forEachIndexed { i, d ->
            val x = xOf(i)
            val y = top + d.coerceIn(0f, 1f) * h
            if (i == 0) { path.moveTo(x, y); fill.moveTo(x, top + h); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo(xOf(n - 1), top + h)
        fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(Palette.Sunrise.copy(alpha = 0.18f), Palette.Lavender.copy(alpha = 0.25f), Palette.StageDeep.copy(alpha = 0.45f)), startY = top, endY = top + h))
        drawPath(path, Brush.verticalGradient(listOf(Palette.Sunrise, Palette.Lavender, Palette.StageDeep), startY = top, endY = top + h), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))

        // Sleep talk markers.
        events.filter { it.type == "TALK" }.forEach { e ->
            val i = ((e.startMs - session.startMs) / 60_000L).toInt()
            if (i in 0 until n) drawCircle(Palette.Teal, 4.dp.toPx(), Offset(xOf(i), top + 4.dp.toPx()))
        }

        // Hour labels.
        val cal = Calendar.getInstance().apply {
            timeInMillis = session.startMs
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.HOUR_OF_DAY, 1)
        }
        val totalMs = (n - 1) * 60_000f
        val hours = ((session.startMs + totalMs.toLong() - cal.timeInMillis) / 3_600_000L).toInt() + 1
        val step = if (hours > 8) 2 else 1
        var idx = 0
        var lastRight = -1f
        val gap = 6.dp.toPx()
        while (cal.timeInMillis <= session.startMs + totalMs) {
            if (idx % step == 0) {
                val x = leftPad + w * (cal.timeInMillis - session.startMs) / totalMs
                val layout = measurer.measure(Fmt.time(ctx, cal.timeInMillis), labelStyle)
                val lx = (x - layout.size.width / 2f).coerceIn(leftPad, size.width - layout.size.width)
                // Skip a label that would collide with the previous one.
                if (lx >= lastRight + gap) {
                    drawText(layout, topLeft = Offset(lx, size.height - layout.size.height))
                    lastRight = lx + layout.size.width
                }
            }
            cal.add(Calendar.HOUR_OF_DAY, 1)
            idx++
        }
    }
}

@Composable
fun MiniDepth(depth: List<Float>, modifier: Modifier = Modifier, color: Color = Palette.Lavender) {
    Canvas(modifier) {
        val n = depth.size
        if (n < 2) return@Canvas
        val path = Path()
        depth.forEachIndexed { i, d ->
            val x = size.width * i / (n - 1)
            val y = d.coerceIn(0f, 1f) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun StageBar(deep: Int, light: Int, awake: Int, modifier: Modifier = Modifier) {
    val total = max(1, deep + light + awake)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
            if (awake > 0) Box(Modifier.weight(awake.toFloat()).height(14.dp).background(Palette.StageAwake))
            if (light > 0) Box(Modifier.weight(light.toFloat()).height(14.dp).background(Palette.StageLight))
            if (deep > 0) Box(Modifier.weight(deep.toFloat()).height(14.dp).background(Palette.StageDeep))
            if (deep + light + awake == 0) Box(Modifier.weight(1f).height(14.dp).background(Palette.Surface2))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Legend(Palette.StageAwake, "Awake", awake, total)
            Legend(Palette.StageLight, "Light", light, total)
            Legend(Palette.StageDeep, "Deep", deep, total)
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, minutes: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
            Text(
                "${Fmt.duration(minutes)} · ${minutes * 100 / total}%",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** Simple bar chart. [goal] draws a dashed reference line. */
@Composable
fun BarChart(
    values: List<Float>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier,
    maxValue: Float? = null,
    goal: Float? = null,
    valueLabel: (Float) -> String = { it.toInt().toString() },
) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(color = Palette.TextDim, fontSize = 10.sp)
    Canvas(modifier.fillMaxWidth().height(160.dp)) {
        val n = values.size
        if (n == 0) return@Canvas
        val bottomPad = 18.dp.toPx()
        val topPad = 14.dp.toPx()
        val h = size.height - bottomPad - topPad
        val maxV = max(maxValue ?: 0f, max(values.maxOrNull() ?: 0f, goal ?: 0f)).takeIf { it > 0f } ?: 1f
        val slot = size.width / n
        val barW = min(slot * 0.62f, 28.dp.toPx())
        val labelEvery = max(1, (n + 9) / 10)
        values.forEachIndexed { i, v ->
            val bh = h * (v / maxV).coerceIn(0f, 1f)
            val x = slot * i + (slot - barW) / 2
            drawRoundRect(
                Palette.Surface2, Offset(x, topPad), Size(barW, h), CornerRadius(barW / 3)
            )
            if (bh > 0) {
                drawRoundRect(color, Offset(x, topPad + h - bh), Size(barW, bh), CornerRadius(barW / 3))
            }
            if (n <= 10 && v > 0) {
                val l = measurer.measure(valueLabel(v), style)
                drawText(l, topLeft = Offset(x + barW / 2 - l.size.width / 2, max(0f, topPad + h - bh - l.size.height - 2)))
            }
            if (i % labelEvery == 0 && i < labels.size) {
                val l = measurer.measure(labels[i], style)
                drawText(l, topLeft = Offset(x + barW / 2 - l.size.width / 2, size.height - l.size.height))
            }
        }
        goal?.let {
            val y = topPad + h - h * (it / maxV)
            drawLine(
                Palette.Sunrise, Offset(0f, y), Offset(size.width, y), 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            )
        }
    }
}

/** Live sound level from the microphone, scaled for display. */
@Composable
fun LevelMeter(db: Float, modifier: Modifier = Modifier) {
    val level = ((db + 80f) / 60f).coerceIn(0f, 1f)
    Box(modifier.height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Surface2)) {
        Box(
            Modifier.fillMaxWidth(level).height(8.dp).clip(RoundedCornerShape(4.dp))
                .background(Brush.horizontalGradient(listOf(Palette.Teal, Palette.Lavender, Palette.Rose)))
        )
    }
}

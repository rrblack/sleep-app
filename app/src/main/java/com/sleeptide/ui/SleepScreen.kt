package com.sleeptide.ui

import android.Manifest
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleeptide.alarm.AlarmScheduler
import com.sleeptide.data.AlarmMode
import com.sleeptide.data.SleepSound
import com.sleeptide.tracking.LiveTracking
import com.sleeptide.tracking.NoisePlayer
import com.sleeptide.tracking.SleepTrackingService
import com.sleeptide.util.Fmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

private val WINDOWS = listOf(10, 20, 30, 45, 60, 90)
private val SOUND_MINUTES = listOf(15, 30, 45, 60, 90)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SleepScreen(snackbar: SnackbarHostState) {
    val app = rememberApp()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by app.settings.state.collectAsStateWithLifecycle()
    var showTimePicker by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(20_000); value = System.currentTimeMillis() }
    }
    // Re-check permissions every time the screen resumes (e.g. coming back from system settings).
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    fun startTracking() {
        SleepTrackingService.start(ctx)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Reliability.hasMic(ctx)) startTracking()
        else scope.launch { snackbar.showSnackbar("SleepTide needs the microphone to hear your sleep. Allow it in app settings.") }
    }

    fun onStart() {
        val needed = buildList {
            if (!Reliability.hasMic(ctx)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && !Reliability.notificationsEnabled(ctx)) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) startTracking() else permissionLauncher.launch(needed.toTypedArray())
    }

    val alarmAt = AlarmScheduler.nextOccurrence(s.alarmHour, s.alarmMinute, now)
    val windowStart = alarmAt - s.wakeWindowMin * 60_000L

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text(greeting(now), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(Fmt.longDate(now), color = Palette.TextDim)

        // Alarm
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Alarm, null, tint = Palette.TextDim)
                Spacer(Modifier.width(8.dp))
                Text("Wake up", style = MaterialTheme.typography.titleMedium, color = Palette.TextDim)
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                AlarmMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = s.alarmMode == mode,
                        onClick = { app.settings.update { it.copy(alarmMode = mode) } },
                        shape = SegmentedButtonDefaults.itemShape(i, AlarmMode.entries.size),
                    ) { Text(mode.label) }
                }
            }
            if (s.alarmMode != AlarmMode.OFF) {
                Text(
                    Fmt.time(ctx, s.alarmHour, s.alarmMinute),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
                        .clickable { showTimePicker = true },
                    textAlign = TextAlign.Center
                )
                Text(
                    if (s.alarmMode == AlarmMode.SMART)
                        "Wakes you in light sleep between ${Fmt.time(ctx, windowStart)} and ${Fmt.time(ctx, alarmAt)}"
                    else "Rings at ${Fmt.time(ctx, alarmAt)}",
                    color = Palette.TextDim,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (s.alarmMode == AlarmMode.SMART) {
                    Spacer(Modifier.height(12.dp))
                    Text("Wake-up window", style = MaterialTheme.typography.labelLarge, color = Palette.TextDim)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WINDOWS.forEach { w ->
                            FilterChip(
                                selected = s.wakeWindowMin == w,
                                onClick = { app.settings.update { it.copy(wakeWindowMin = w) } },
                                label = { Text("$w min") },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.Surface2)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                val inBed = ((alarmAt - now) / 60_000L).toInt()
                Text(
                    "Start now for ${Fmt.duration(inBed)} in bed · goal ${Fmt.duration(s.sleepGoalMin)}",
                    color = if (inBed >= s.sleepGoalMin) Palette.Teal else Palette.Sunrise,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    "Tracking only. No alarm will ring.",
                    color = Palette.TextDim,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }

        // Reliability warnings
        key(resumed) {
            if (!Reliability.notificationsEnabled(ctx) && Build.VERSION.SDK_INT < 33) {
                WarningCard(Icons.Filled.NotificationsOff, "Notifications are off", "The alarm uses a notification to show on your lock screen.", "Turn on") {
                    Reliability.openNotificationSettings(ctx)
                }
            }
            if (s.alarmMode != AlarmMode.OFF && !Reliability.canFullScreen(ctx)) {
                WarningCard(Icons.Filled.Alarm, "Allow full-screen alarms", "Without this the alarm only shows as a notification instead of filling the lock screen.", "Allow") {
                    Reliability.openFullScreenSettings(ctx)
                }
            }
            if (!Reliability.ignoringBatteryOptimizations(ctx)) {
                WarningCard(Icons.Filled.BatteryAlert, "Let SleepTide run all night", "Battery optimization can pause tracking while you sleep. Tracking uses about as much power as playing music, so keep the phone charging.", "Allow") {
                    Reliability.requestIgnoreBattery(ctx)
                }
            }
            if (!Reliability.exactAlarms(ctx)) {
                WarningCard(Icons.Filled.Alarm, "Allow alarms & reminders", "Needed so the alarm rings on time.", "Allow") {
                    Reliability.openExactAlarmSettings(ctx)
                }
            }
        }

        // Sleep notes
        SectionCard {
            CardTitle("Sleep notes for tonight")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.noteTags.forEach { tag ->
                    val selected = tag in s.tonightNotes
                    FilterChip(
                        selected = selected,
                        onClick = {
                            app.settings.update {
                                it.copy(tonightNotes = if (selected) it.tonightNotes - tag else it.tonightNotes + tag)
                            }
                        },
                        label = { Text(tag) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.Surface2)
                    )
                }
            }
        }

        SleepSoundCard()

        Button(
            onClick = ::onStart,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(32.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Lavender)
        ) {
            Icon(Icons.Filled.Bedtime, null)
            Spacer(Modifier.width(8.dp))
            Text("Start sleep", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "Place your phone on the nightstand or on the mattress near your pillow. Keep it charging. You can lock the screen.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
    }

    if (showTimePicker) {
        val state = rememberTimePickerState(s.alarmHour, s.alarmMinute, DateFormat.is24HourFormat(ctx))
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            containerColor = Palette.Surface1,
            title = { Text("Wake-up time") },
            text = {
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(clockDialColor = Palette.Surface2, timeSelectorUnselectedContainerColor = Palette.Surface2)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    app.settings.update { it.copy(alarmHour = state.hour, alarmMinute = state.minute) }
                    showTimePicker = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepSoundCard() {
    val app = rememberApp()
    val s by app.settings.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<NoisePlayer?>(null) }
    DisposableEffect(Unit) { onDispose { preview?.stop() } }

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.MusicNote, null, tint = Palette.TextDim)
            Spacer(Modifier.width(8.dp))
            Text("Sleep sounds", style = MaterialTheme.typography.titleMedium, color = Palette.TextDim, modifier = Modifier.weight(1f))
            if (s.sleepSound != SleepSound.OFF) {
                IconButton(onClick = {
                    val p = preview
                    if (p != null) {
                        p.stop(); preview = null
                    } else {
                        preview = NoisePlayer(s.sleepSound, s.sleepSoundVolume).also { it.start(0) }
                    }
                }) { Icon(if (preview != null) Icons.Filled.Stop else Icons.Filled.PlayArrow, "Preview") }
            }
        }
        Box {
            OutlinedButton(onClick = { menu = true }) { Text(s.sleepSound.label) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                SleepSound.entries.forEach { snd ->
                    DropdownMenuItem(text = { Text(snd.label) }, onClick = {
                        preview?.stop(); preview = null
                        app.settings.update { it.copy(sleepSound = snd) }
                        menu = false
                    })
                }
            }
        }
        if (s.sleepSound != SleepSound.OFF) {
            Spacer(Modifier.height(8.dp))
            Text("Fade out after", style = MaterialTheme.typography.labelLarge, color = Palette.TextDim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SOUND_MINUTES.forEach { m ->
                    FilterChip(
                        selected = s.sleepSoundMinutes == m,
                        onClick = { app.settings.update { it.copy(sleepSoundMinutes = m) } },
                        label = { Text("$m min") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.Surface2)
                    )
                }
            }
            Text(
                "Sound detection pauses while sleep sounds play. Motion tracking keeps running.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextDim
            )
        }
    }
}

private fun greeting(now: Long): String {
    val h = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
    return when (h) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
}

/** Full-screen night view while tracking is running. */
@Composable
fun TrackingScreen(live: LiveTracking) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    BackHandler { confirm = true }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color.Black, Palette.Night)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .systemBarsPadding()
            .padding(24.dp)
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            Text(Fmt.time(ctx, now), fontSize = 72.sp, fontWeight = FontWeight.ExtraLight, color = Palette.Text.copy(alpha = 0.85f))
            Text(Fmt.longDate(now), color = Palette.TextDim)
            Spacer(Modifier.height(12.dp))
            val alarmLine = when {
                live.snoozedUntil > now -> "Snoozed until ${Fmt.time(ctx, live.snoozedUntil)}"
                live.alarmMode == AlarmMode.SMART -> "Smart alarm ${Fmt.time(ctx, live.windowStartMs)} – ${Fmt.time(ctx, live.alarmTargetMs)}"
                live.alarmMode == AlarmMode.REGULAR -> "Alarm at ${Fmt.time(ctx, live.alarmTargetMs)}"
                else -> "No alarm"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Alarm, null, tint = Palette.Lavender)
                Spacer(Modifier.width(6.dp))
                Text(alarmLine, color = Palette.Lavender)
            }

            Spacer(Modifier.weight(1f))

            SectionCard {
                val elapsed = ((now - live.startMs) / 60_000L).toInt()
                Text("Sleeping for ${Fmt.duration(elapsed)}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                if (live.micOk) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Mic, null, tint = Palette.TextDim)
                        Spacer(Modifier.width(8.dp))
                        LevelMeter(live.currentDb, Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Text("≈${Fmt.approxSpl(live.currentDb)} dB", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("Microphone unavailable. Tracking movement only.", color = Palette.Sunrise)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.GraphicEq, null, tint = if (live.snoring) Palette.Rose else Palette.TextDim)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            live.snoring -> "Snoring detected"
                            live.snoreSec >= 60 -> "Snoring so far: ${Fmt.duration(live.snoreSec / 60)}"
                            else -> "No snoring so far"
                        },
                        color = if (live.snoring) Palette.Rose else Palette.TextDim
                    )
                }
                if (live.sleepSoundPlaying) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.MusicNote, null, tint = Palette.Teal)
                        Spacer(Modifier.width(8.dp))
                        Text("Sleep sounds playing", color = Palette.Teal)
                    }
                }
                if (live.activity.size >= 2) {
                    Spacer(Modifier.height(12.dp))
                    val recent = live.activity.takeLast(120)
                    val depth = remember(recent) {
                        com.sleeptide.tracking.SleepStager.analyze(recent).depth
                    }
                    MiniDepth(depth, Modifier.fillMaxWidth().height(48.dp))
                }
            }

            Spacer(Modifier.height(20.dp))
            OutlinedButton(
                onClick = { confirm = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp)
            ) { Text("Wake up", style = MaterialTheme.typography.titleMedium) }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Palette.Surface1,
            title = { Text("End sleep session?") },
            text = { Text("Your night will be analyzed and saved. The alarm will be turned off.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    SleepTrackingService.stop(ctx)
                }) { Text("End & save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        confirm = false
                        SleepTrackingService.discard(ctx)
                    }) { Text("Discard", color = Palette.Rose) }
                    TextButton(onClick = { confirm = false }) { Text("Keep tracking") }
                }
            }
        )
    }
}

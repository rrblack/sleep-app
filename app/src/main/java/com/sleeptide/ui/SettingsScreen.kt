package com.sleeptide.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleeptide.BuildConfigInfo
import com.sleeptide.alarm.AlarmScheduler
import com.sleeptide.alarm.Reminders
import com.sleeptide.data.AppSettings
import com.sleeptide.data.Mood
import com.sleeptide.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class Choice<T>(val title: String, val options: List<Pair<String, T>>, val selected: T, val onPick: (T) -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(snackbar: SnackbarHostState) {
    val app = rememberApp()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by app.settings.state.collectAsStateWithLifecycle()
    var choice by remember { mutableStateOf<Choice<*>?>(null) }
    var reminderPicker by remember { mutableStateOf(false) }
    var soundDialog by remember { mutableStateOf(false) }
    var addTag by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()

    fun update(f: (AppSettings) -> AppSettings) = app.settings.update(f)

    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            if (uri != null) {
                val name = try { RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) } catch (_: Exception) { null } ?: "System sound"
                update { it.copy(alarmSoundUri = uri.toString(), alarmSoundName = name) }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)

        SectionCard {
            CardTitle("Alarm")
            SettingRow("Alarm sound", s.alarmSoundName) { soundDialog = true }
            SliderRow("Alarm volume", s.alarmVolume) { v -> update { it.copy(alarmVolume = v) } }
            SettingRow("Gradual volume", if (s.rampSeconds == 0) "Off" else "Rises over ${rampLabel(s.rampSeconds)}") {
                choice = Choice("Gradual volume", listOf(0, 30, 60, 120, 180).map { (if (it == 0) "Off" else rampLabel(it)) to it }, s.rampSeconds) { v -> update { it.copy(rampSeconds = v) } }
            }
            SwitchRow("Vibrate", null, s.vibrate) { v -> update { it.copy(vibrate = v) } }
            SettingRow("Snooze length", "${s.snoozeMinutes} minutes") {
                choice = Choice("Snooze length", listOf(1, 3, 5, 9, 10, 15, 20).map { "$it minutes" to it }, s.snoozeMinutes) { v -> update { it.copy(snoozeMinutes = v) } }
            }
            SettingRow("Test alarm", "Rings in 5 seconds so you can check sound and volume") {
                AlarmScheduler.schedule(ctx, System.currentTimeMillis() + 5000)
                scope.launch { snackbar.showSnackbar("Test alarm in 5 seconds. Lock your phone to see the lock-screen view.") }
            }
        }

        SectionCard {
            CardTitle("Sleep")
            SettingRow("Sleep goal", Fmt.duration(s.sleepGoalMin)) {
                choice = Choice("Sleep goal", (12..20).map { it * 30 }.map { Fmt.duration(it) to it }, s.sleepGoalMin) { v -> update { it.copy(sleepGoalMin = v) } }
            }
            SwitchRow("Bedtime reminder", if (s.bedtimeReminder) "Every day at ${Fmt.time(ctx, s.reminderHour, s.reminderMinute)}" else "Off", s.bedtimeReminder) { v ->
                update { it.copy(bedtimeReminder = v) }
                Reminders.schedule(ctx)
            }
            if (s.bedtimeReminder) SettingRow("Reminder time", Fmt.time(ctx, s.reminderHour, s.reminderMinute)) { reminderPicker = true }
        }

        SectionCard {
            CardTitle("Tracking")
            SettingRow("Sound sensitivity", listOf("Low", "Medium", "High")[s.sensitivity.coerceIn(0, 2)]) {
                choice = Choice(
                    "Sound sensitivity",
                    listOf("Low (noisy room or partner)" to 0, "Medium" to 1, "High (quiet room)" to 2),
                    s.sensitivity
                ) { v -> update { it.copy(sensitivity = v) } }
            }
            SwitchRow("Use motion sensor", "Detects movement when the phone lies on the mattress", s.useMotion) { v -> update { it.copy(useMotion = v) } }
            SwitchRow("Record snoring & sleep talk", "Saves short audio clips you can play back", s.recordSounds) { v -> update { it.copy(recordSounds = v) } }
            SettingRow("Keep recordings", if (s.keepRecordingsDays == 0) "Forever" else "${s.keepRecordingsDays} days") {
                choice = Choice("Keep recordings", listOf("7 days" to 7, "30 days" to 30, "90 days" to 90, "Forever" to 0), s.keepRecordingsDays) { v -> update { it.copy(keepRecordingsDays = v) } }
            }
            SliderRow("Sleep sound volume", s.sleepSoundVolume) { v -> update { it.copy(sleepSoundVolume = v) } }
        }

        SectionCard {
            CardTitle("Sleep notes")
            s.noteTags.forEach { tag ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(tag, Modifier.weight(1f))
                    IconButton(onClick = { update { it.copy(noteTags = it.noteTags - tag, tonightNotes = it.tonightNotes - tag) } }) {
                        Icon(Icons.Filled.Close, "Remove $tag", tint = Palette.TextDim)
                    }
                }
            }
            TextButton(onClick = { addTag = true }) { Text("Add a note") }
        }

        key(lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
            SectionCard {
                CardTitle("Permissions & reliability")
                StatusRow("Microphone", Reliability.hasMic(ctx)) { Reliability.openAppSettings(ctx) }
                StatusRow("Notifications", Reliability.notificationsEnabled(ctx)) { Reliability.openNotificationSettings(ctx) }
                StatusRow("Full-screen alarm", Reliability.canFullScreen(ctx)) { Reliability.openFullScreenSettings(ctx) }
                StatusRow("Exact alarms", Reliability.exactAlarms(ctx)) { Reliability.openExactAlarmSettings(ctx) }
                StatusRow("Unrestricted battery", Reliability.ignoringBatteryOptimizations(ctx)) { Reliability.requestIgnoreBattery(ctx) }
            }
        }

        SectionCard {
            CardTitle("Your data")
            SettingRow("Export to CSV", "Share all nights as a spreadsheet") {
                scope.launch {
                    val intent = withContext(Dispatchers.IO) { exportCsv(ctx, app) }
                    ctx.startActivity(Intent.createChooser(intent, "Export sleep data"))
                }
            }
            SettingRow("Delete all data", "Removes every night and recording") { confirmDeleteAll = true }
        }

        Text(
            "SleepTide ${BuildConfigInfo.versionName(ctx)} · Sleep analysis is an estimate and not medical advice.",
            style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, modifier = Modifier.padding(4.dp)
        )
        Spacer(Modifier.height(8.dp))
    }

    choice?.let { ChoiceDialog(it) { choice = null } }

    if (soundDialog) {
        AlertDialog(
            onDismissRequest = { soundDialog = false },
            containerColor = Palette.Surface1,
            title = { Text("Alarm sound") },
            text = {
                Column {
                    RadioRow("Gentle chime (built-in)", s.alarmSoundUri == null) {
                        update { it.copy(alarmSoundUri = null, alarmSoundName = "Gentle chime") }
                        soundDialog = false
                    }
                    RadioRow("Choose a phone sound…", s.alarmSoundUri != null) {
                        soundDialog = false
                        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, s.alarmSoundUri?.let(Uri::parse))
                        ringtonePicker.launch(intent)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { soundDialog = false }) { Text("Close") } }
        )
    }

    if (reminderPicker) {
        val state = rememberTimePickerState(s.reminderHour, s.reminderMinute, DateFormat.is24HourFormat(ctx))
        AlertDialog(
            onDismissRequest = { reminderPicker = false },
            containerColor = Palette.Surface1,
            title = { Text("Reminder time") },
            text = { TimePicker(state, colors = TimePickerDefaults.colors(clockDialColor = Palette.Surface2, timeSelectorUnselectedContainerColor = Palette.Surface2)) },
            confirmButton = {
                TextButton(onClick = {
                    update { it.copy(reminderHour = state.hour, reminderMinute = state.minute) }
                    Reminders.schedule(ctx)
                    reminderPicker = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { reminderPicker = false }) { Text("Cancel") } }
        )
    }

    if (addTag) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addTag = false },
            containerColor = Palette.Surface1,
            title = { Text("New sleep note") },
            text = { OutlinedTextField(text, { text = it.take(24) }, singleLine = true, placeholder = { Text("e.g. Read a book") }) },
            confirmButton = {
                TextButton(onClick = {
                    val t = text.trim()
                    if (t.isNotEmpty()) update { if (t in it.noteTags) it else it.copy(noteTags = it.noteTags + t) }
                    addTag = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addTag = false }) { Text("Cancel") } }
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            containerColor = Palette.Surface1,
            title = { Text("Delete all sleep data?") },
            text = { Text("This removes every night, statistic and recording. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    scope.launch {
                        withContext(Dispatchers.IO) { app.repo.deleteAll() }
                        snackbar.showSnackbar("All sleep data deleted")
                    }
                }) { Text("Delete everything", color = Palette.Rose) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } }
        )
    }
}

private fun rampLabel(sec: Int) = if (sec < 60) "$sec seconds" else "${sec / 60} minute${if (sec >= 120) "s" else ""}"

@Composable
private fun SettingRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}

@Composable
private fun SliderRow(title: String, value: Float, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(vertical = 4.dp)) {
        Text("$title · ${(local * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge)
        Slider(local, { local = it }, valueRange = 0.1f..1f, onValueChangeFinished = { onChange(local) })
    }
}

@Composable
private fun StatusRow(title: String, ok: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        if (ok) Text("Allowed", color = Palette.Teal, style = MaterialTheme.typography.labelLarge)
        else TextButton(onClick = onFix) { Text("Fix", color = Palette.Sunrise) }
    }
    HorizontalDivider(color = Palette.Surface2)
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected, null)
        Spacer(Modifier.width(10.dp))
        Text(label)
    }
}

@Composable
private fun <T> ChoiceDialog(choice: Choice<T>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface1,
        title = { Text(choice.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                choice.options.forEach { (label, v) ->
                    RadioRow(label, v == choice.selected) {
                        choice.onPick(v)
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun exportCsv(ctx: android.content.Context, app: com.sleeptide.SleepTideApp): Intent {
    val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, "sleeptide-export.csv")
    val iso = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    file.bufferedWriter().use { w ->
        w.write("start,end,quality_pct,time_in_bed_min,asleep_min,deep_min,light_min,awake_min,fell_asleep_after_min,times_awake,snoring_min,avg_noise_db,mood,notes\n")
        app.repo.list().sortedBy { it.startMs }.forEach { s ->
            w.write(
                listOf(
                    iso.format(Date(s.startMs)), iso.format(Date(s.endMs)), s.quality, s.inBedMin, s.asleepMin,
                    s.deepMin, s.lightMin, s.awakeMin, s.latencyMin, s.awakenings, s.snoreMin,
                    Fmt.approxSpl(s.avgDb), if (s.mood == Mood.NONE) "" else Mood.label(s.mood), q(s.notes.joinToString("; "))
                ).joinToString(",") + "\n"
            )
        }
    }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    return Intent(Intent.ACTION_SEND).setType("text/csv")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "SleepTide export")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

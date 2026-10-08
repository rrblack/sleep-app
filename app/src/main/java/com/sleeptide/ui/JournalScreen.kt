package com.sleeptide.ui

import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.sleeptide.data.AlarmMode
import com.sleeptide.data.Mood
import com.sleeptide.data.SleepSession
import com.sleeptide.data.SoundEventRecord
import com.sleeptide.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun JournalScreen(onOpen: (Long) -> Unit) {
    val sessions by rememberRepoData { repo.list() }
    val list = sessions
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Sleep journal", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
        }
        if (list != null && list.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Bedtime, null, Modifier.size(56.dp), tint = Palette.Lavender)
                    Spacer(Modifier.height(12.dp))
                    Text("No nights yet", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Start a sleep session tonight and your results will show up here in the morning.",
                        color = Palette.TextDim, textAlign = TextAlign.Center
                    )
                }
            }
        }
        items(list.orEmpty(), key = { it.id }) { s -> NightCard(s) { onOpen(s.id) } }
    }
}

@Composable
private fun NightCard(s: SleepSession, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.Surface1)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            QualityRing(s.quality, 60.dp, 6.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(Fmt.day(s.startMs), style = MaterialTheme.typography.titleMedium)
                Text("${Fmt.time(ctx, s.startMs)} – ${Fmt.time(ctx, s.endMs)}", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
                val extras = buildList {
                    add("Asleep ${Fmt.duration(s.asleepMin)}")
                    if (s.snoreMin > 0) add("Snored ${Fmt.duration(s.snoreMin)}")
                    if (s.mood != Mood.NONE) add(Mood.emoji(s.mood))
                }
                Text(extras.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            MiniDepth(s.depth, Modifier.width(72.dp).height(36.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NightDetailScreen(id: Long, onBack: () -> Unit) {
    val app = rememberApp()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by rememberRepoData(id) { repo.get(id) to repo.events(id) }
    var confirmDelete by remember { mutableStateOf(false) }
    val player = remember { ClipPlayer() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    androidx.compose.material3.Scaffold(
        containerColor = Palette.Night,
        topBar = {
            TopAppBar(
                title = { Text(data?.first?.let { Fmt.day(it.startMs) } ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.DeleteOutline, "Delete night") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Night)
            )
        }
    ) { padding ->
        val session = data?.first
        val events = data?.second.orEmpty()
        if (session == null) {
            if (data != null) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("This night was deleted") }
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QualityRing(session.quality, 112.dp, 10.dp)
                    Spacer(Modifier.width(20.dp))
                    Column {
                        Text("Sleep quality", color = Palette.TextDim)
                        Text(qualityWord(session.quality), style = MaterialTheme.typography.headlineSmall, color = qualityColor(session.quality))
                        Spacer(Modifier.height(4.dp))
                        Text("${Fmt.time(ctx, session.startMs)} – ${Fmt.time(ctx, session.endMs)}", color = Palette.TextDim)
                    }
                }
            }
            item {
                SectionCard {
                    CardTitle("Sleep graph")
                    SleepGraph(session, events)
                    Spacer(Modifier.height(16.dp))
                    StageBar(session.deepMin, session.lightMin, session.awakeMin)
                }
            }
            item {
                SectionCard {
                    CardTitle("Details")
                    val woke = when {
                        session.wokeMs > 0 && session.alarmMode == AlarmMode.SMART && session.wokeMs < session.alarmTargetMs - 60_000 ->
                            "Smart alarm at ${Fmt.time(ctx, session.wokeMs)}"
                        session.wokeMs > 0 -> "Alarm at ${Fmt.time(ctx, session.wokeMs)}"
                        else -> "Ended manually"
                    }
                    val rows = listOf(
                        "Time in bed" to Fmt.duration(session.inBedMin),
                        "Time asleep" to Fmt.duration(session.asleepMin),
                        "Fell asleep after" to Fmt.duration(session.latencyMin),
                        "Times awake" to session.awakenings.toString(),
                        "Snoring" to if (session.snoreMin > 0) Fmt.duration(session.snoreMin) else "None",
                        "Avg. noise" to "≈${Fmt.approxSpl(session.avgDb)} dB",
                        "Woke up" to woke,
                    )
                    rows.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            pair.forEach { (label, value) ->
                                Column(Modifier.weight(1f)) {
                                    Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
                                    Text(value, style = MaterialTheme.typography.titleMedium)
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            item {
                SectionCard {
                    CardTitle("Wake-up mood")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(Mood.BAD, Mood.OK, Mood.GOOD).forEach { m ->
                            val selected = session.mood == m
                            Column(
                                Modifier.clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) Palette.Surface2 else Palette.Surface1)
                                    .clickable { scope.launch(Dispatchers.IO) { app.repo.setMood(id, if (selected) Mood.NONE else m) } }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(Mood.emoji(m), fontSize = 28.sp)
                                Text(Mood.label(m), style = MaterialTheme.typography.labelMedium, color = if (selected) Palette.Text else Palette.TextDim)
                            }
                        }
                    }
                }
            }
            item {
                SectionCard {
                    CardTitle("Sleep notes")
                    val settings = app.settings.current
                    val tags = (settings.noteTags + session.notes).distinct()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tags.forEach { tag ->
                            val selected = tag in session.notes
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val next = if (selected) session.notes - tag else session.notes + tag
                                    scope.launch(Dispatchers.IO) { app.repo.setNotes(id, next) }
                                },
                                label = { Text(tag) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.Surface2)
                            )
                        }
                    }
                }
            }
            item {
                SectionCard {
                    CardTitle("Sounds")
                    val recordings = events.filter { it.file != null && File(it.file).exists() }
                    val snoreEpisodes = events.count { it.type == "SNORE" }
                    if (snoreEpisodes > 0) {
                        Text(
                            "$snoreEpisodes snoring episode${if (snoreEpisodes == 1) "" else "s"} detected",
                            color = Palette.TextDim, style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (recordings.isEmpty()) {
                        Text(
                            if (app.settings.current.recordSounds) "No snoring or sleep talk was recorded." else "Sound recording is turned off in Settings.",
                            color = Palette.TextDim
                        )
                    }
                    recordings.forEach { e ->
                        SoundRow(
                            e = e,
                            playing = player.playingId == e.id,
                            onPlay = { player.toggle(e) },
                            onShare = {
                                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(e.file!!))
                                val send = Intent(Intent.ACTION_SEND).setType("audio/wav")
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                ctx.startActivity(Intent.createChooser(send, "Share recording"))
                            },
                            onDelete = {
                                if (player.playingId == e.id) player.stop()
                                scope.launch(Dispatchers.IO) { app.repo.deleteRecording(e) }
                            }
                        )
                    }
                }
            }
            item {
                Text(
                    "Sleep stages are estimated from movement and sound. SleepTide is not a medical device.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Palette.Surface1,
            title = { Text("Delete this night?") },
            text = { Text("The sleep data and its recordings will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    player.stop()
                    scope.launch {
                        withContext(Dispatchers.IO) { app.repo.delete(id) }
                        onBack()
                    }
                }) { Text("Delete", color = Palette.Rose) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SoundRow(e: SoundEventRecord, playing: Boolean, onPlay: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val snore = e.type == "SNORE"
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (snore) Palette.Rose.copy(alpha = 0.2f) else Palette.Teal.copy(alpha = 0.2f))
                .clickable(onClick = onPlay),
            contentAlignment = Alignment.Center
        ) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", tint = if (snore) Palette.Rose else Palette.Teal)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (snore) Icons.Filled.GraphicEq else Icons.Filled.RecordVoiceOver, null, Modifier.size(16.dp), tint = Palette.TextDim)
                Spacer(Modifier.width(4.dp))
                Text(if (snore) "Snoring" else "Sleep talk", style = MaterialTheme.typography.titleSmall)
            }
            val dur = if (snore) " · episode ${Fmt.duration(((e.durationMs + 30_000) / 60_000).toInt())}" else ""
            Text("${Fmt.time(ctx, e.startMs)}$dur", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "Share", tint = Palette.TextDim) }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete recording", tint = Palette.TextDim) }
    }
}

private fun qualityWord(q: Int) = when {
    q >= 85 -> "Excellent"
    q >= 70 -> "Good"
    q >= 50 -> "Fair"
    else -> "Poor"
}

/** One shared player so only one recording plays at a time. */
private class ClipPlayer {
    private var mp: MediaPlayer? = null
    var playingId by mutableStateOf<Long?>(null)
        private set

    fun toggle(e: SoundEventRecord) {
        if (playingId == e.id) { stop(); return }
        stop()
        val path = e.file ?: return
        try {
            mp = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
            playingId = e.id
        } catch (_: Exception) {
            stop()
        }
    }

    fun stop() {
        mp?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        mp = null
        playingId = null
    }

    fun release() = stop()
}

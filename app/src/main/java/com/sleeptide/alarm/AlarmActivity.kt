package com.sleeptide.alarm

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleeptide.MainActivity
import com.sleeptide.app
import com.sleeptide.data.Mood
import com.sleeptide.tracking.AlarmState
import com.sleeptide.ui.Palette
import com.sleeptide.ui.SleepTideTheme
import com.sleeptide.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-screen alarm shown over the lock screen. After stopping, asks how you feel. */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this) { /* the alarm must be snoozed or stopped */ }

        setContent {
            SleepTideTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides Palette.Text
                ) {
                AlarmScreen(
                    onSnooze = {
                        AlarmService.snooze(this)
                        finish()
                    },
                    onStop = {
                        val sessionId = app.runtime.activeSessionId
                        AlarmService.dismiss(this)
                        sessionId
                    },
                    onMood = { sessionId, mood ->
                        if (mood != null && sessionId != 0L) {
                            withContext(Dispatchers.IO) { app.repo.setMood(sessionId, mood) }
                        }
                        if (sessionId != 0L) {
                            startActivity(
                                Intent(this, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    .putExtra(MainActivity.EXTRA_OPEN_SESSION, sessionId)
                            )
                        }
                        finish()
                    },
                    onRingingEnded = { finish() },
                )
                }
            }
        }
    }
}

@Composable
private fun AlarmScreen(
    onSnooze: () -> Unit,
    onStop: () -> Long,
    onMood: suspend (Long, Int?) -> Unit,
    onRingingEnded: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val settings = ctx.app.settings.current
    val ringing by AlarmState.ringing.collectAsStateWithLifecycle()
    var stoppedSession by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    LaunchedEffect(ringing) {
        // Snoozed or stopped from the notification while this screen is open.
        if (!ringing && stoppedSession == null) {
            delay(400)
            if (stoppedSession == null) onRingingEnded()
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Palette.Night, androidx.compose.ui.graphics.Color(0xFF3B2E5A), androidx.compose.ui.graphics.Color(0xFFB9765A)))
        ),
        contentAlignment = Alignment.Center
    ) {
        val session = stoppedSession
        if (session == null) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(48.dp))
                    Text("Good morning", style = MaterialTheme.typography.titleLarge, color = Palette.TextDim)
                    Text(Fmt.time(ctx, now), fontSize = 76.sp, fontWeight = FontWeight.Light)
                    Text(Fmt.longDate(now), style = MaterialTheme.typography.titleMedium, color = Palette.TextDim)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = onSnooze,
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                        shape = RoundedCornerShape(36.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Surface2, contentColor = Palette.Text)
                    ) {
                        Icon(Icons.Filled.Snooze, null)
                        Text("  Snooze ${settings.snoozeMinutes} min", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(20.dp))
                    HoldToStopButton { stoppedSession = onStop() }
                    Spacer(Modifier.height(24.dp))
                }
            }
        } else if (session == 0L) {
            LaunchedEffect(Unit) { onMood(0L, null) }
        } else {
            Column(
                Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("How do you feel?", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf(Mood.BAD, Mood.OK, Mood.GOOD).forEach { m ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(84.dp).clip(CircleShape).background(Palette.Surface2)
                                    .pointerInput(m) { detectTapGestures { scope.launch { onMood(session, m) } } },
                                contentAlignment = Alignment.Center
                            ) { Text(Mood.emoji(m), fontSize = 40.sp) }
                            Spacer(Modifier.height(8.dp))
                            Text(Mood.label(m), color = Palette.TextDim)
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
                TextButton(onClick = { scope.launch { onMood(session, null) } }) { Text("Skip") }
            }
        }
    }
}

/** Stopping needs a deliberate one-second press so a half-asleep tap doesn't kill the alarm. */
@Composable
private fun HoldToStopButton(onStop: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    var pressing by remember { mutableStateOf(false) }
    LaunchedEffect(pressing) {
        if (pressing) {
            val start = System.currentTimeMillis()
            while (pressing) {
                progress = ((System.currentTimeMillis() - start) / 1000f).coerceAtMost(1f)
                if (progress >= 1f) { pressing = false; onStop(); break }
                delay(16)
            }
        } else if (progress < 1f) progress = 0f
    }
    Box(
        Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(36.dp)).background(Palette.Lavender.copy(alpha = 0.25f))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressing = true
                    tryAwaitRelease()
                    pressing = false
                })
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth(progress).height(72.dp).background(Palette.Lavender))
        Text(
            "Hold to stop",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            color = Palette.Text
        )
    }
}

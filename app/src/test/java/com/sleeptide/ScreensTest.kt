package com.sleeptide

import android.app.AlarmManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleeptide.alarm.AlarmActivity
import com.sleeptide.alarm.AlarmScheduler
import com.sleeptide.data.AlarmMode
import com.sleeptide.data.Mood
import com.sleeptide.data.SleepSound
import com.sleeptide.data.SoundEventRecord
import com.sleeptide.tracking.AlarmState
import com.sleeptide.tracking.LiveTracking
import com.sleeptide.tracking.SessionFinalizer
import com.sleeptide.tracking.SleepTrackingService
import com.sleeptide.tracking.TrackingState
import com.sleeptide.tracking.WavWriter
import com.sleeptide.ui.AppRoot
import com.sleeptide.ui.JournalScreen
import com.sleeptide.ui.NightDetailScreen
import com.sleeptide.ui.SettingsScreen
import com.sleeptide.ui.SleepScreen
import com.sleeptide.ui.SleepTideTheme
import com.sleeptide.ui.StatsScreen
import com.sleeptide.ui.TrackingScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Calendar
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

private val app: SleepTideApp get() = ApplicationProvider.getApplicationContext()

/** Software-renders a view tree; works in Robolectric's native graphics mode without waiting for frames. */
private fun snapshot(view: android.view.View): Bitmap {
    val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    view.draw(android.graphics.Canvas(bmp))
    return bmp
}

private fun save(bitmap: Bitmap, name: String) {
    val dir = File(System.getProperty("screenshotDir") ?: "build/screenshots").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

/** Fills the database with a week of plausible nights, including snoring and recordings. */
private fun seedWeek(): Long {
    val rnd = Random(42)
    val tags = listOf("Caffeine", "Worked out", "Alcohol", "Stressful day", "Screen time")
    var lastId = 0L
    for (d in 7 downTo 1) {
        val start = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -d)
            set(Calendar.HOUR_OF_DAY, 22 + rnd.nextInt(2))
            set(Calendar.MINUTE, rnd.nextInt(60))
        }.timeInMillis
        val minutes = 400 + rnd.nextInt(100)
        val falling = 8 + rnd.nextInt(20)
        val wakeAt = if (rnd.nextBoolean()) 150 + rnd.nextInt(150) else -1
        val activity = List(minutes) { i ->
            val phase = (i % 92) / 92.0
            val lateNight = i > minutes / 2
            when {
                i < falling -> 12f + rnd.nextInt(20)
                wakeAt >= 0 && i in wakeAt until wakeAt + 5 -> 15f + rnd.nextInt(15)
                i > minutes - 4 -> 14f
                (phase < 0.18 || phase > 0.82 || (lateNight && phase > 0.55)) && rnd.nextFloat() < 0.6f -> 0.5f + rnd.nextFloat() * 3f
                else -> if (rnd.nextFloat() < 0.03f) 1f else 0f
            }
        }
        val snoring = d % 2 == 1
        val snore = List(minutes) { i -> if (snoring && i in 70..160 && rnd.nextFloat() < 0.7f) 20 + rnd.nextInt(35) else 0 }
        val noise = List(minutes) { -78f + rnd.nextFloat() * 8f }
        val notes = tags.filter { rnd.nextFloat() < 0.3f }
        val id = app.repo.createSession(start, AlarmMode.SMART, start + minutes * 60_000L + 10 * 60_000L, 30, notes)
        app.repo.saveProgress(id, activity, snore, noise)
        val end = start + minutes * 60_000L
        SessionFinalizer.finalize(app, id, end, end)
        app.repo.setMood(id, listOf(Mood.BAD, Mood.OK, Mood.GOOD, Mood.GOOD)[rnd.nextInt(4)])
        if (snoring) {
            val clip = File(app.repo.recordingsDir(id), "snore_${start + 80 * 60_000L}.wav").apply { parentFile?.mkdirs() }
            WavWriter(clip, 16000).use { w ->
                val buf = ShortArray(16000) { k -> (sin(2 * PI * 110 * k / 16000) * 8000).toInt().toShort() }
                w.write(buf, 0, buf.size)
            }
            app.repo.addEvent(SoundEventRecord(0, id, "SNORE", start + 80 * 60_000L, 52 * 60_000L, -32f, clip.absolutePath))
            val talk = File(app.repo.recordingsDir(id), "talk_${start + 200 * 60_000L}.wav")
            WavWriter(talk, 16000).use { it.write(ShortArray(8000), 0, 8000) }
            app.repo.addEvent(SoundEventRecord(0, id, "TALK", start + 200 * 60_000L, 1800, -40f, talk.absolutePath))
        }
        lastId = id
    }
    return lastId
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ScreensTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun reset() {
        app.repo.deleteAll()
        app.settings.update { it.copy(tonightNotes = setOf("Worked out"), sleepSound = SleepSound.OFF) }
        TrackingState.live.value = null
    }

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        // Screens contain live clocks that tick forever; drive time by hand instead.
        rule.mainClock.autoAdvance = false
        rule.setContent { SleepTideTheme { Surface(Modifier.fillMaxSize()) { content() } } }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        save(snapshot(rule.activity.window.decorView), name)
    }

    @Test
    fun sleepHome() = shoot("1-sleep") { SleepScreen(androidx.compose.material3.SnackbarHostState()) }

    @Test
    fun appRootShowsNavigation() {
        rule.mainClock.autoAdvance = false
        rule.setContent { SleepTideTheme { AppRoot(mutableStateOf(null)) } }
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithText("Start sleep").assertExists()
        rule.onNodeWithText("Journal").performClick()
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithText("No nights yet").assertExists()
        rule.onNodeWithText("Statistics").performClick()
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithText("No nights in this period yet.").assertExists()
        rule.onNodeWithText("Settings").performClick()
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithText("Alarm sound").assertExists()
    }

    @Test
    fun tracking() {
        val now = System.currentTimeMillis()
        val activity = List(200) { i -> if (i % 90 < 12) 1.5f else 0f }
        shoot("2-tracking") {
            TrackingScreen(
                LiveTracking(
                    sessionId = 1, startMs = now - 200 * 60_000L, alarmMode = AlarmMode.SMART,
                    alarmTargetMs = now + 5 * 3600_000L, windowStartMs = now + 4 * 3600_000L + 30 * 60_000L,
                    currentDb = -52f, snoring = true, snoreSec = 23 * 60, minutes = 200, activity = activity,
                    micOk = true, sleepSoundPlaying = false, smartTriggered = false, snoozedUntil = 0L
                )
            )
        }
    }

    @Test
    fun journal() {
        seedWeek()
        shoot("3-journal") { JournalScreen {} }
    }

    @Test
    @Config(qualifiers = "w411dp-h1900dp-xhdpi")
    fun nightDetail() {
        val id = seedWeek()
        shoot("4-night") { NightDetailScreen(id) {} }
        rule.onNodeWithText("Sleep graph").assertExists()
        rule.onNodeWithText("Sleep talk").assertExists()
        rule.onNodeWithText("1 snoring episode detected").assertExists()
    }

    @Test
    @Config(qualifiers = "w411dp-h2000dp-xhdpi")
    fun statistics() {
        seedWeek()
        shoot("5-stats") { StatsScreen() }
        rule.onNodeWithText("How sleep notes affect you").assertExists()
    }

    @Test
    @Config(qualifiers = "w411dp-h2300dp-xhdpi")
    fun settings() = shoot("6-settings") { SettingsScreen(androidx.compose.material3.SnackbarHostState()) }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class AlarmScreenTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    @Test
    fun alarmScreenShowsSnoozeAndStop() {
        AlarmState.ringing.value = true
        rule.mainClock.autoAdvance = false
        ActivityScenario.launch(AlarmActivity::class.java).use {
            rule.mainClock.advanceTimeBy(1000)
            rule.onNodeWithText("Hold to stop").assertExists()
            rule.onNodeWithText("Snooze", substring = true).assertExists()
            it.onActivity { a -> save(snapshot(a.window.decorView), "7-alarm") }
        }
        AlarmState.ringing.value = false
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TrackingServiceTest {

    private fun waitUntil(timeoutMs: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun startArmsAlarmAndShortSessionIsDiscardedOnStop() {
        app.repo.deleteAll()
        app.settings.update { it.copy(alarmMode = AlarmMode.SMART, alarmHour = 7, alarmMinute = 0, wakeWindowMin = 30, sleepSound = SleepSound.OFF) }
        val controller = Robolectric.buildService(
            SleepTrackingService::class.java, Intent().setAction(SleepTrackingService.ACTION_START)
        ).create().startCommand(0, 1)
        assertTrue(SleepTrackingService.isRunning)
        val id = app.runtime.activeSessionId
        assertTrue(id != 0L)

        val am = app.getSystemService(AlarmManager::class.java)
        val expected = AlarmScheduler.nextOccurrence(7, 0)
        assertEquals(expected, am.nextAlarmClock?.triggerTime)
        waitUntil { TrackingState.live.value != null }
        assertNotNull(TrackingState.live.value)

        controller.withIntent(Intent().setAction(SleepTrackingService.ACTION_STOP)).startCommand(0, 2)
        waitUntil { !SleepTrackingService.isRunning }
        assertFalse(SleepTrackingService.isRunning)
        assertNull(TrackingState.live.value)
        assertEquals(0L, app.runtime.activeSessionId)
        assertNull("too-short session should be removed", app.repo.get(id))
        assertNull("alarm cancelled when stopped by hand", am.nextAlarmClock)
        assertTrue(TrackingState.message.value!!.contains("shorter"))
        TrackingState.message.value = null
    }

    @Test
    fun staleSessionIsRepairedOnLaunch() {
        app.repo.deleteAll()
        val start = System.currentTimeMillis() - 8 * 3600_000L
        val id = app.repo.createSession(start, AlarmMode.REGULAR, start + 8 * 3600_000L, 0, emptyList())
        app.repo.saveProgress(id, List(300) { if (it < 15) 20f else 0f }, List(300) { 0 }, List(300) { -70f })
        app.runtime.activeSessionId = id
        SessionFinalizer.repairStale(app)
        val s = app.repo.get(id)!!
        assertFalse(s.inProgress)
        assertEquals(300, s.inBedMin)
        assertTrue(s.quality > 0)
        assertEquals(0L, app.runtime.activeSessionId)
    }
}

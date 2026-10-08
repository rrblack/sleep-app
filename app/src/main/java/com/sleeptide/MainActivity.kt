package com.sleeptide

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.sleeptide.alarm.Reminders
import com.sleeptide.tracking.SessionFinalizer
import com.sleeptide.ui.AppRoot
import com.sleeptide.ui.SleepTideTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_SESSION = "open_session"
    }

    private val openSession = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        Thread {
            SessionFinalizer.repairStale(app)
            SessionFinalizer.cleanupRecordings(app)
        }.start()
        Reminders.schedule(this)
        setContent {
            SleepTideTheme {
                AppRoot(openSession)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_OPEN_SESSION, 0L) ?: 0L
        if (id != 0L) openSession.value = id
    }
}

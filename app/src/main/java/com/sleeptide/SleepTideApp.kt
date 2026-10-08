package com.sleeptide

import android.app.Application
import android.content.Context
import com.sleeptide.data.RuntimeState
import com.sleeptide.data.SettingsRepository
import com.sleeptide.data.SleepRepository

class SleepTideApp : Application() {
    lateinit var settings: SettingsRepository
        private set
    lateinit var repo: SleepRepository
        private set
    lateinit var runtime: RuntimeState
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        repo = SleepRepository(this)
        runtime = RuntimeState(this)
        Notifications.createChannels(this)
    }
}

val Context.app: SleepTideApp
    get() = applicationContext as SleepTideApp

# SleepTide

A Sleep Cycle–style sleep tracker and smart alarm for Android (built for Google Pixel, works on Android 8+).

## Install on your Pixel

1. Copy `SleepTide.apk` to your phone (or download it there).
2. Open it. Android asks to allow installs from that app (Files, Chrome, Drive…). Allow it and tap **Install**.
3. Open SleepTide, tap **Start sleep**, and allow the microphone and notifications.
4. Tap each orange card on the Sleep screen once (full-screen alarms, battery), so tracking and the alarm work overnight.
5. Try **Settings → Test alarm** to check the sound.

## Features

- **Smart alarm**: wakes you during light sleep inside a 10–90 minute window, and rings at the set time if light sleep is not detected first. Regular alarm and tracking-only modes are also available.
- **Alarm**: built-in gentle chime or any phone sound, gradual volume, vibration, snooze length, hold-to-stop, lock-screen display. Survives reboots.
- **Sleep tracking**: microphone (and the motion sensor when the phone is on the mattress) records movement and sound every minute.
- **Snoring detection**: rhythmic low-frequency sounds are identified as snoring, timed, and shown on the graph.
- **Recordings**: short clips of snoring and sleep talk with playback, share and delete. Auto-cleanup after 7/30/90 days.
- **Sleep graph and quality score**: awake/light/deep stages, time asleep, time to fall asleep, awakenings, noise level.
- **Wake-up mood** and **sleep notes** (caffeine, alcohol, workout… plus your own).
- **Statistics**: week/month/3 months/all. Covers quality, time asleep vs. goal, snoring, bedtime and wake time, regularity, how each sleep note affects your sleep, and mood.
- **Sleep sounds**: rain, ocean, fan, white/pink/brown noise with fade-out timer.
- **Bedtime reminder**, **CSV export**, delete all data.

Sleep stages are estimated from movement and sound. This is not a medical device.

## Build from source

Requires JDK 17+ and the Android SDK (platform 35, build-tools 35). Open the folder in Android Studio, or:

```
./gradlew :app:assembleRelease      # APK in app/build/outputs/apk/release/
./gradlew :app:testDebugUnitTest    # analysis, service and screen tests (writes docs/screenshots)
```

Builds are signed with `keystore/sleeptide.jks` (password `sleeptide`) so new versions install over old ones.

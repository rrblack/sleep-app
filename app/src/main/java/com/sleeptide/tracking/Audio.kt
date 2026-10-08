package com.sleeptide.tracking

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.sleeptide.data.SleepSound
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Writes 16-bit mono PCM to a .wav file. */
class WavWriter(file: File, private val sampleRate: Int) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private val buffer = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
    private var dataBytes = 0L

    init {
        raf.setLength(0)
        raf.write(header(0, sampleRate))
    }

    fun write(samples: ShortArray, offset: Int, length: Int) {
        var i = offset
        val end = offset + length
        while (i < end) {
            buffer.clear()
            while (i < end && buffer.remaining() >= 2) buffer.putShort(samples[i++])
            raf.write(buffer.array(), 0, buffer.position())
        }
        dataBytes += length * 2L
    }

    override fun close() {
        raf.seek(0)
        raf.write(header(dataBytes, sampleRate))
        raf.close()
    }

    companion object {
        fun header(dataLen: Long, sampleRate: Int): ByteArray {
            val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()); b.putInt((36 + dataLen).toInt())
            b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray())
            b.putInt(16); b.putShort(1); b.putShort(1)
            b.putInt(sampleRate); b.putInt(sampleRate * 2)
            b.putShort(2); b.putShort(16)
            b.put("data".toByteArray()); b.putInt(dataLen.toInt())
            return b.array()
        }
    }
}

/**
 * Keeps the last few seconds of audio so a clip can include the sound that triggered it,
 * then keeps writing for a few more seconds.
 */
class ClipRecorder(private val sampleRate: Int, preRollSec: Int) {
    private val ring = ShortArray(sampleRate * preRollSec)
    private var pos = 0
    private var filled = 0
    private var writer: WavWriter? = null
    private var remaining = 0L

    @Synchronized
    fun feed(buf: ShortArray, n: Int) {
        writer?.let {
            try {
                it.write(buf, 0, n)
            } catch (e: IOException) {
                close()
            }
            remaining -= n
            if (remaining <= 0) close()
        }
        for (i in 0 until n) {
            ring[pos] = buf[i]
            pos = (pos + 1) % ring.size
        }
        filled = min(ring.size, filled + n)
    }

    @Synchronized
    fun start(file: File, postRollSec: Int): Boolean {
        if (writer != null) return false
        val w = try {
            file.parentFile?.mkdirs()
            WavWriter(file, sampleRate)
        } catch (e: IOException) {
            return false
        }
        val start = (pos - filled + ring.size) % ring.size
        if (filled > 0) {
            if (start + filled <= ring.size) {
                w.write(ring, start, filled)
            } else {
                val first = ring.size - start
                w.write(ring, start, first)
                w.write(ring, 0, filled - first)
            }
        }
        writer = w
        remaining = sampleRate.toLong() * postRollSec
        return true
    }

    @Synchronized
    fun close() {
        try {
            writer?.close()
        } catch (_: IOException) {
        }
        writer = null
    }
}

/** Generates soothing noise sample by sample (values roughly in -1..1). */
class NoiseGenerator(private val sound: SleepSound, private val sampleRate: Int) {
    private val rnd = Random()
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f; private var b3 = 0f
    private var b4 = 0f; private var b5 = 0f; private var b6 = 0f
    private var brownState = 0f
    private var lowpass = 0f
    private var drop = 0f
    private var t = 0L

    private fun white() = rnd.nextFloat() * 2f - 1f

    private fun pink(w: Float): Float {
        b0 = 0.99886f * b0 + w * 0.0555179f
        b1 = 0.99332f * b1 + w * 0.0750759f
        b2 = 0.96900f * b2 + w * 0.1538520f
        b3 = 0.86650f * b3 + w * 0.3104856f
        b4 = 0.55000f * b4 + w * 0.5329522f
        b5 = -0.7616f * b5 - w * 0.0168980f
        val p = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362f
        b6 = w * 0.115926f
        return p * 0.11f
    }

    private fun brown(w: Float): Float {
        brownState = (brownState + 0.02f * w) / 1.02f
        return brownState * 3.5f
    }

    fun next(): Float {
        t++
        val w = white()
        val v = when (sound) {
            SleepSound.OFF -> 0f
            SleepSound.WHITE -> w * 0.3f
            SleepSound.PINK -> pink(w) * 0.9f
            SleepSound.BROWN -> brown(w)
            SleepSound.OCEAN -> {
                val sec = t.toFloat() / sampleRate
                val phase = (sin(2 * PI * sec / 9.0).toFloat() + 1f) / 2f
                val env = 0.2f + 0.8f * phase * phase
                (brown(w) * 0.7f + pink(w) * 0.5f) * env
            }
            SleepSound.RAIN -> {
                val p = pink(w)
                lowpass += (p - lowpass) * 0.15f
                val hiss = (p - lowpass) * 1.6f
                if (rnd.nextFloat() < 0.0006f) drop = 0.4f + rnd.nextFloat() * 0.6f
                drop *= 0.995f
                hiss * 0.8f + w * drop * 0.3f + brown(w) * 0.25f
            }
            SleepSound.FAN -> {
                val hum = sin(2 * PI * 110.0 * t / sampleRate).toFloat() * 0.025f
                brown(w) * 0.75f + pink(w) * 0.25f + hum
            }
        }
        return v.coerceIn(-1f, 1f)
    }
}

/** Streams generated noise with a fade-in and a long fade-out, then stops by itself. */
class NoisePlayer(private val sound: SleepSound, private val volume: Float) {
    @Volatile private var running = false
    private var thread: Thread? = null

    val isPlaying: Boolean get() = running

    /** [durationMs] <= 0 plays until [stop] is called. */
    fun start(durationMs: Long, onFinished: (() -> Unit)? = null) {
        if (running || sound == SleepSound.OFF) return
        val sr = 22050
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sr)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf, sr))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            onFinished?.invoke()
            return
        }
        running = true
        thread = Thread({
            val gen = NoiseGenerator(sound, sr)
            val buf = ShortArray(1024)
            val total = if (durationMs > 0) durationMs * sr / 1000 else Long.MAX_VALUE
            val fadeIn = sr * 3L
            val fadeOut = if (durationMs > 0) min(sr * 120L, total / 3) else 1L
            var n = 0L
            try {
                track.play()
                while (running && n < total) {
                    for (i in buf.indices) {
                        var g = volume
                        if (n < fadeIn) g *= n.toFloat() / fadeIn
                        if (durationMs > 0 && total - n < fadeOut) g *= (total - n).toFloat() / fadeOut
                        buf[i] = (gen.next() * g * 32000f).toInt().toShort()
                        n++
                    }
                    track.write(buf, 0, buf.size)
                }
                track.stop()
            } catch (_: Exception) {
            } finally {
                track.release()
                running = false
                onFinished?.invoke()
            }
        }, "sleep-sound").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }
}

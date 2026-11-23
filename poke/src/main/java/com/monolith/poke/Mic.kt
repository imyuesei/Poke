package com.monolith.poke

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Microphone with voice-activity detection. While on, it cuts what you say into messages:
 * speech starts after ~120 ms of voice and a message ends after [pauseMs] of silence, then
 * the audio (16 kHz mono) goes to [onMessage] right away. [onLevel] reports loudness for the
 * mic button's animation.
 */
class Mic(
    private val pauseMs: Int,
    private val onMessage: (FloatArray) -> Unit,
    private val onLevel: (Float, Boolean) -> Unit,
) {
    companion object {
        const val RATE = 16000
    }

    @Volatile private var running = false

    val isOn get() = running

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread(name = "mic") {
            val frame = RATE / 50 // 20 ms
            val endFrames = max(10, pauseMs / 20)
            val preroll = RATE * 3 / 10
            val maxLen = RATE * 30 // Whistle takes up to 30 s per pass
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, max(min, RATE))
            } catch (_: Exception) {
                running = false
                return@thread
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                running = false
                return@thread
            }
            val ring = FloatArray(preroll)
            var ringPos = 0
            var floor = 0.004f
            var voiced = 0
            var silent = 0
            var seg: FloatArray? = null
            var segLen = 0
            val buf = ShortArray(frame)
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(buf, 0, frame)
                    if (n <= 0) continue
                    var sum = 0f
                    val f = FloatArray(n) { buf[it] / 32768f }
                    for (x in f) sum += x * x
                    val level = sqrt(sum / n)
                    val s = seg
                    onLevel(level, s != null)
                    if (s == null) {
                        for (x in f) { ring[ringPos] = x; ringPos = (ringPos + 1) % ring.size }
                        if (level < floor * 2f) floor = floor * 0.98f + level * 0.02f
                        voiced = if (level > max(floor * 3.5f, 0.008f)) voiced + 1 else 0
                        if (voiced >= 6) {
                            val ns = FloatArray(maxLen)
                            for (i in ring.indices) ns[i] = ring[(ringPos + i) % ring.size]
                            segLen = ring.size
                            seg = ns
                            silent = 0
                        }
                    } else {
                        val take = minOf(n, s.size - segLen)
                        System.arraycopy(f, 0, s, segLen, take)
                        segLen += take
                        if (level < max(floor * 2.2f, 0.006f)) silent++ else silent = 0
                        if (silent >= endFrames || segLen >= s.size) {
                            val keep = max(RATE / 2, segLen - (silent - 10).coerceAtLeast(0) * frame)
                            val clip = s.copyOf(minOf(keep, segLen))
                            seg = null
                            voiced = 0
                            onMessage(clip)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
                running = false
                onLevel(0f, false)
            }
        }
    }

    fun stop() {
        running = false
    }
}

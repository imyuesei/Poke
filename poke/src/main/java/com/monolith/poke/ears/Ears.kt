package com.monolith.poke.ears

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.monolith.poke.Mind
import com.monolith.poke.Voice
import com.monolith.poke.brain.Brain
import com.monolith.poke.brain.Episodes
import com.monolith.poke.brain.Skills
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Poke's ears (stage 2). Two ways in:
 *  - Hold the island and talk; let go when done. Holding without talking is petting.
 *  - Optional wake word: say "Poke …" while the screen is on. A cheap voice-activity
 *    detector waits for speech; only speech segments go to Whistle, so the model isn't
 *    running all the time.
 * What it hears goes Whistle (speech to text) -> Needle 3 (what to do) -> Skills (do it and
 * answer in caveman talk) -> bubble + voice + episodic memory.
 */
object Ears {
    const val RATE = 16000
    /** Biases Whistle toward Poke's name and the words people use with it. */
    private const val KEYWORDS = "Poke\nhey Poke\npark\nparked\nremember"
    private val WAKE = Regex("^\\W*(?:hey|hi|ok|okay|oi)?\\W*(poke|pokey|poké|polk|poak|pok|pope|folk)\\b[\\s,.!?]*", RegexOption.IGNORE_CASE)

    private val main = Handler(Looper.getMainLooper())
    @Volatile var held = false
        private set
    @Volatile var listening = false
        private set
    /** After "Poke?" with nothing else, the next thing said doesn't need the wake word. */
    @Volatile private var attentiveUntil = 0L

    fun hasMic(c: Context) = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ hold to talk

    /** The island is being held: listen until [stopHeld]. */
    fun startHeld(c: Context) {
        if (held) return
        val app = c.applicationContext
        if (!hasMic(app)) {
            Mind.say("Me no hear. Open Poke, let me listen.")
            return
        }
        held = true
        EarsService.ensure(app) // a microphone foreground service keeps recording allowed
        thread(name = "poke-ears-held") {
            // the wake loop lets go of the mic while held
            val deadline = SystemClock.elapsedRealtime() + 300
            while (listening && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            val pcm = record { held }
            held = false
            val speech = pcm.size > RATE / 3 && rms(pcm, 0, pcm.size) > 0.004f
            if (speech) process(app, pcm, requireWake = false)
        }
    }

    fun stopHeld() {
        held = false
    }

    // ------------------------------------------------------------------ wake word

    /** The always-on loop (run by [EarsService] while the screen is on). */
    @SuppressLint("MissingPermission")
    fun wakeLoop(c: Context, keepGoing: () -> Boolean) {
        val app = c.applicationContext
        val frame = RATE / 50 // 20 ms
        val preroll = RATE * 3 / 10
        val maxLen = RATE * 8
        while (keepGoing()) {
            if (held || Voice.speaking || !hasMic(app)) {
                Thread.sleep(150)
                continue
            }
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, max(min, RATE))
            } catch (_: Exception) {
                Thread.sleep(1000)
                continue
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                Thread.sleep(1000)
                continue
            }
            listening = true
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
                while (keepGoing() && !held && !Voice.speaking) {
                    val n = rec.read(buf, 0, frame)
                    if (n <= 0) continue
                    var sum = 0f
                    val f = FloatArray(n) { buf[it] / 32768f }
                    for (x in f) sum += x * x
                    val level = sqrt(sum / n)
                    val s = seg
                    if (s == null) {
                        // keep a short pre-roll so the first syllable isn't lost
                        for (x in f) { ring[ringPos] = x; ringPos = (ringPos + 1) % ring.size }
                        // the noise floor follows quiet moments
                        if (level < floor * 2f) floor = floor * 0.98f + level * 0.02f
                        voiced = if (level > max(floor * 3.5f, 0.008f)) voiced + 1 else 0
                        if (voiced >= 6) { // ~120 ms of voice: start a segment
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
                        silent = if (level < max(floor * 2.2f, 0.006f)) silent + 1 else 0
                        if (silent >= 40 || segLen >= s.size) { // 800 ms of quiet, or 8 s
                            val clip = s.copyOf(segLen)
                            seg = null
                            voiced = 0
                            process(app, clip, requireWake = SystemClock.elapsedRealtime() > attentiveUntil)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
                listening = false
            }
        }
    }

    // ------------------------------------------------------------------ understanding

    /** Whistle -> (wake word) -> Needle -> Skills; replies in the bubble and out loud. */
    private fun process(c: Context, pcm: FloatArray, requireWake: Boolean) {
        if (!Brain.engineBuilt) return
        if (!Brain.hasModels(c)) {
            if (!requireWake) say(c, "Me no brain yet. Open Poke lab.")
            return
        }
        if (!requireWake) main.post { Mind.onEvent(Mind.Event.THINKING) }
        Brain.async({ Brain.hear(c, pcm, KEYWORDS) }) { r ->
            val heard = r.getOrNull() ?: return@async
            var text = heard.text
            if (text.isBlank()) return@async
            if (requireWake) {
                val m = WAKE.find(text) ?: return@async // not for Poke
                text = text.substring(m.range.last + 1).trim()
                if (text.length < 2) {
                    attentiveUntil = SystemClock.elapsedRealtime() + 7000
                    Mind.onEvent(Mind.Event.WAKE)
                    return@async
                }
                Mind.onEvent(Mind.Event.THINKING)
            } else {
                // a wake word said while holding is just a name
                WAKE.find(text)?.let { m -> if (text.substring(m.range.last + 1).isNotBlank()) text = text.substring(m.range.last + 1).trim() }
            }
            attentiveUntil = 0L
            understand(c, text)
        }
    }

    /** Needle reads [text]; Poke acts, answers, and remembers the moment. */
    fun understand(c: Context, text: String) {
        Brain.async({
            val thought = Brain.think(c, text, Skills.TOOLS, Skills.system())
            val reply = Skills.act(c, thought)
            val emb = runCatching { Brain.embed(c, text) }.getOrNull()
            Episodes.log(c, "heard", text, reply.detail, emb)
            reply
        }) { r ->
            r.onSuccess { reply -> say(c, reply.words) }
                .onFailure { say(c, "Hmm? Me head hurt.") }
        }
    }

    private fun say(c: Context, words: String) {
        main.post {
            Mind.say(words)
            Voice.say(c, words, Mind)
        }
    }

    // ------------------------------------------------------------------ recording

    @SuppressLint("MissingPermission")
    private fun record(keepGoing: () -> Boolean): FloatArray {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(min, RATE))
        } catch (_: Exception) {
            return FloatArray(0)
        }
        val maxSamples = RATE * 15
        val pcm = ShortArray(maxSamples)
        var n = 0
        try {
            rec.startRecording()
            val buf = ShortArray(1024)
            while (keepGoing() && n < maxSamples) {
                val r = rec.read(buf, 0, buf.size)
                if (r <= 0) continue
                val take = minOf(r, maxSamples - n)
                System.arraycopy(buf, 0, pcm, n, take)
                n += take
            }
        } catch (_: Exception) {
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        return FloatArray(n) { pcm[it] / 32768f }
    }

    private fun rms(a: FloatArray, from: Int, to: Int): Float {
        var s = 0f
        for (i in from until to) s += a[i] * a[i]
        return sqrt(s / max(1, to - from))
    }

    /** Opens the app so Android lets Poke ask for the microphone. */
    fun openForPermission(c: Context) {
        try {
            c.startActivity(Intent(c, com.monolith.poke.PokeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}

package com.monolith.poke.ears

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
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
 * Poke's ears (stage 2).
 *  - Swipe down on the island: Poke listens. Each time you stop talking for 1 second, what
 *    you said is one input and goes straight to Whistle. Swipe up to stop (it also stops by
 *    itself after a minute of quiet).
 *  - Optional wake word: say "Poke, …" while the screen is on.
 * Poke never listens to itself: the microphone is ignored while it speaks (plus an echo
 * tail), and anything that matches what it just said is dropped.
 * Whistle (speech to text) -> Needle 3 (meaning) -> Skills (act, caveman answer) -> bubble,
 * voice and episodic memory.
 */
object Ears {
    const val RATE = 16000
    private const val END_SILENCE_MS = 1000
    private const val SESSION_IDLE_MS = 60_000L
    /** Biases Whistle toward Poke's name and the words people use with it. */
    private const val KEYWORDS = "Poke\nhey Poke\npark\nparked\nremember\ntimer"
    private val WAKE = Regex("^\\W*(?:hey|hi|ok|okay|oi)?\\W*(poke|pokey|poké|polk|poak|pok|pope|folk)\\b[\\s,.!?]*", RegexOption.IGNORE_CASE)

    private val main = Handler(Looper.getMainLooper())
    /** Ears opened by a swipe down. */
    @Volatile var session = false
        private set
    @Volatile private var lastSpeech = 0L
    @Volatile private var micBusy = false
    @Volatile private var attentiveUntil = 0L

    fun hasMic(c: Context) = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ swipe sessions

    /** Swipe down: open the ears until [stopSession]. */
    fun startSession(c: Context) {
        val app = c.applicationContext
        if (session) return
        if (!hasMic(app)) {
            Mind.say("Me no hear. Open Poke, let me listen.")
            return
        }
        session = true
        lastSpeech = SystemClock.elapsedRealtime()
        Mind.onEvent(Mind.Event.LISTEN_ON)
        EarsService.ensure(app) // a microphone foreground service keeps recording allowed
        thread(name = "poke-ears-session") {
            // the wake-word loop lets go of the mic first
            val deadline = SystemClock.elapsedRealtime() + 500
            while (micBusy && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            listen(app,
                keepGoing = { session && SystemClock.elapsedRealtime() - lastSpeech < SESSION_IDLE_MS },
                onSegment = { clip -> process(app, clip, requireWake = false) })
            if (session) main.post { stopSession() } // went quiet for a minute
        }
    }

    /** Swipe up: close the ears. */
    fun stopSession() {
        if (!session) return
        session = false
        Mind.onEvent(Mind.Event.LISTEN_OFF)
    }

    // ------------------------------------------------------------------ wake word

    /** The wake-word loop (run by [EarsService] while the screen is on). */
    fun wakeLoop(c: Context, keepGoing: () -> Boolean) {
        val app = c.applicationContext
        listen(app, keepGoing = { keepGoing() && !session }) { clip ->
            process(app, clip, requireWake = SystemClock.elapsedRealtime() > attentiveUntil)
        }
    }

    // ------------------------------------------------------------------ the segmenter

    /**
     * Reads the microphone and cuts it into utterances: speech starts after ~120 ms of voice
     * and ends after [END_SILENCE_MS] of quiet; each utterance goes to [onSegment] at once.
     * Nothing is recorded while Poke itself is making a sound.
     */
    @SuppressLint("MissingPermission")
    private fun listen(c: Context, keepGoing: () -> Boolean, onSegment: (FloatArray) -> Unit) {
        if (!hasMic(c)) return
        val frame = RATE / 50 // 20 ms
        val endFrames = END_SILENCE_MS / 20
        val preroll = RATE * 3 / 10
        val maxLen = RATE * 20
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(min, RATE))
        } catch (_: Exception) {
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return
        }
        micBusy = true
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
            while (keepGoing()) {
                val n = rec.read(buf, 0, frame)
                if (n <= 0) continue
                if (Voice.speaking) { // Poke is talking: don't hear itself
                    seg = null
                    voiced = 0
                    continue
                }
                var sum = 0f
                val f = FloatArray(n) { buf[it] / 32768f }
                for (x in f) sum += x * x
                val level = sqrt(sum / n)
                val s = seg
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
                        lastSpeech = SystemClock.elapsedRealtime()
                    }
                } else {
                    val take = minOf(n, s.size - segLen)
                    System.arraycopy(f, 0, s, segLen, take)
                    segLen += take
                    if (level < max(floor * 2.2f, 0.006f)) silent++ else {
                        silent = 0
                        lastSpeech = SystemClock.elapsedRealtime()
                    }
                    if (silent >= endFrames || segLen >= s.size) {
                        // 1 s of quiet: one complete input (trim most of the trailing silence)
                        val keep = max(RATE / 2, segLen - (silent - 10).coerceAtLeast(0) * frame)
                        val clip = s.copyOf(minOf(keep, segLen))
                        seg = null
                        voiced = 0
                        onSegment(clip)
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            micBusy = false
        }
    }

    // ------------------------------------------------------------------ understanding

    @Volatile private var lastSaid = ""
    @Volatile private var whistleMs: Long? = null

    /** Whistle -> (wake word) -> Needle -> Skills; replies in the bubble and out loud. */
    private fun process(c: Context, pcm: FloatArray, requireWake: Boolean) {
        if (!Brain.engineBuilt) return
        if (!Brain.hasModels(c)) {
            if (!requireWake) say(c, "Me no brain yet. Open Poke lab.")
            return
        }
        if (!requireWake) main.post { Mind.onEvent(Mind.Event.THINKING) }
        Brain.async({ Brain.hear(c, pcm, KEYWORDS) }) { r ->
            val heard = r.getOrNull()
            if (heard == null) {
                com.monolith.poke.brain.Talk.note("(whistle failed)", r.exceptionOrNull()?.message ?: "error", null)
                return@async
            }
            var text = heard.text
            if (text.isBlank()) {
                if (!requireWake) com.monolith.poke.brain.Talk.note("(nothing)", "whistle heard no words", heard.ms)
                return@async
            }
            if (isEcho(text)) {
                com.monolith.poke.brain.Talk.note(text, "ignored: Poke's own voice", heard.ms)
                return@async
            }
            whistleMs = heard.ms
            if (requireWake) {
                val m = WAKE.find(text)
                if (m == null) { // not for Poke
                    com.monolith.poke.brain.Talk.note(text, "ignored: no wake word", heard.ms)
                    return@async
                }
                text = text.substring(m.range.last + 1).trim()
                if (text.length < 2) {
                    attentiveUntil = SystemClock.elapsedRealtime() + 7000
                    Mind.onEvent(Mind.Event.WAKE)
                    return@async
                }
                Mind.onEvent(Mind.Event.THINKING)
            } else {
                WAKE.find(text)?.let { m -> if (text.substring(m.range.last + 1).isNotBlank()) text = text.substring(m.range.last + 1).trim() }
            }
            attentiveUntil = 0L
            understand(c, text)
        }
    }

    /** Something Poke just said, heard back through the speaker. */
    private fun isEcho(heard: String): Boolean {
        val a = words(heard)
        val b = words(lastSaid)
        if (a.isEmpty() || b.isEmpty()) return false
        val overlap = a.count { it in b }.toFloat() / a.size
        return overlap >= 0.6f
    }

    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }.toSet()

    /** Needle reads [text]; Poke acts, answers, and remembers the moment. */
    fun understand(c: Context, text: String) {
        val wMs = whistleMs
        whistleMs = null
        Brain.async({
            val h = Skills.handle(c, text)
            val emb = runCatching { Brain.embed(c, text) }.getOrNull()
            Episodes.log(c, "heard", text, "[${h.reply.how}] ${h.reply.detail} → ${h.reply.words}", emb)
            com.monolith.poke.brain.Talk.add(com.monolith.poke.brain.Talk.Turn(System.currentTimeMillis(), text, wMs,
                h.needleMs, h.reply.how, h.reply.call, h.reply.words, h.reply.detail))
            h.reply
        }) { r ->
            r.onSuccess { reply -> say(c, reply.words) }
                .onFailure { e ->
                    Episodes.log(c, "error", text, e.message ?: "failed")
                    com.monolith.poke.brain.Talk.note(text, "error: ${e.message}", wMs)
                    say(c, "Hmm? Me head hurt.")
                }
        }
    }

    private fun say(c: Context, words: String) {
        main.post {
            lastSaid = words
            Mind.say(words)
            Voice.say(c, words, Mind)
        }
    }
}

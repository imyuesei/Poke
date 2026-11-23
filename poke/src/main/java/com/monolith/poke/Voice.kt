package com.monolith.poke

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.random.Random

/**
 * Poke's voice. It doesn't say words of its own: it makes emotional sounds ("hehe!",
 * "aww…", "hmm?", "whoa!", "hmph.") in the phone's best offline voice, with pitch and pace
 * set by its mood and personality. Caveman sentences ("Car. Level 3. Me remember!") use
 * the same voice.
 */
object Voice {
    enum class Kind { HAPPY, CALL, SULK, GRUMBLE, STARTLED, PURR, CONFUSED, PROUD, LISTEN }

    private val sounds = mapOf(
        Kind.HAPPY to listOf("hehe!", "yay!", "ooh!", "wheee!", "haha!", "heehee!"),
        Kind.CALL to listOf("hey?", "hmm?", "psst!", "ahem.", "helloo?"),
        Kind.SULK to listOf("aww…", "hmph…", "mmh…", "aww.", "sigh…"),
        Kind.GRUMBLE to listOf("ugh.", "hmph!", "grr.", "tch.", "nnngh."),
        Kind.STARTLED to listOf("whoa!", "eep!", "huh?!", "oh!", "ah!"),
        Kind.PURR to listOf("awww…", "mmm…", "hehe…", "ohh…"),
        Kind.CONFUSED to listOf("hmm?", "huh?", "uhh…", "hm."),
        Kind.PROUD to listOf("mm-hm!", "aha!", "ooh!", "yup!"),
        Kind.LISTEN to listOf("hmm?", "mm?", "yes?", "ooh?"),
    )

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var lastSound = 0L
    private val pending = mutableListOf<Triple<String, Float, Float>>()

    /** True while Poke is talking (the ears ignore that). */
    @Volatile var speaking = false
        private set

    /** Makes an emotional sound of [kind], shaped by Poke's mood and personality. */
    fun chirp(c: Context?, kind: Kind, mind: Mind, proactive: Boolean = false) {
        c ?: return
        if (!mind.soundAllowed(proactive)) return
        val now = System.currentTimeMillis()
        if (now - lastSound < 400) return
        lastSound = now
        val options = sounds[kind] ?: return
        val text = options[Random.nextInt(options.size)]
        val e = (mind.traits[Mind.ENERGETIC] + 1f) / 2f
        val (pitch, rate) = when (kind) {
            Kind.HAPPY -> 1.3f + 0.15f * mind.valence to 1.1f
            Kind.CALL -> 1.2f to 0.95f
            Kind.SULK -> 0.95f to 0.78f
            Kind.GRUMBLE -> 0.82f to 1.0f
            Kind.STARTLED -> 1.5f to 1.25f
            Kind.PURR -> 1.15f to 0.78f
            Kind.CONFUSED -> 1.12f to 0.9f
            Kind.PROUD -> 1.25f to 1.05f
            Kind.LISTEN -> 1.25f to 0.95f
        }
        // energetic Poke is a little higher and quicker; every sound varies slightly
        val p = pitch * (0.95f + 0.12f * e) * (0.96f + 0.08f * Random.nextFloat())
        val r = rate * (0.92f + 0.16f * e) + 0.2f * (mind.arousal - 0.4f)
        speak(c, text, p, r.coerceIn(0.6f, 1.6f))
    }

    /** Speaks a caveman sentence in Poke's voice. */
    fun say(c: Context, text: String, mind: Mind) {
        if (!mind.soundAllowed(false)) return
        val p = 1.25f + 0.2f * mind.valence + 0.1f * mind.arousal
        val r = 1.0f + 0.2f * mind.arousal
        speak(c, text, p, r)
    }

    private fun speak(c: Context, text: String, pitch: Float, rate: Float) {
        val t = tts
        if (t == null) {
            pending.add(Triple(text, pitch, rate))
            tts = TextToSpeech(c.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    setup()
                    pending.forEach { (tx, pi, ra) -> utter(tx, pi, ra) }
                }
                pending.clear()
            }
            return
        }
        if (!ready) {
            pending.add(Triple(text, pitch, rate))
            return
        }
        utter(text, pitch, rate)
    }

    private fun utter(text: String, pitch: Float, rate: Float) {
        val t = tts ?: return
        t.setPitch(pitch)
        t.setSpeechRate(rate)
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "poke-${System.nanoTime()}")
    }

    /** Picks the most natural offline English voice the phone has. */
    private fun setup() {
        val t = tts ?: return
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { speaking = true }
            override fun onDone(utteranceId: String?) { speaking = t.isSpeaking }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { speaking = false }
        })
        try {
            val best = t.voices
                ?.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired && !it.features.contains("notInstalled") }
                ?.maxWithOrNull(compareBy({ it.quality }, { if (it.locale.country == "US") 1 else 0 }, { -it.latency }))
            if (best != null) t.voice = best else t.language = Locale.US
        } catch (_: Exception) {
            t.language = Locale.US
        }
    }
}

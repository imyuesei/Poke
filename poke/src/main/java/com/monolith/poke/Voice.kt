package com.monolith.poke

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Poke's voice. Like a Pokémon it only really says its name: "po", "ke", "poke", built
 * from a tiny synthesizer (a pitched, vowel-coloured tone with a consonant click), and
 * bent by mood and personality: pitch, rise or fall, speed, repeats and loudness.
 * Caveman sentences ("Car. Level three. Me remember!") are spoken with eSpeak NG ([Speech]).
 */
object Voice {
    enum class Kind { HAPPY, CALL, SULK, GRUMBLE, STARTLED, PURR, CONFUSED, PROUD, LISTEN, ALARM }

    private const val RATE = 22050
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var lastSound = 0L

    /** A syllable: consonant ('p', 'k' or none), vowel ('o', 'e', 'u'), pitch glide, length. */
    private class Syl(val cons: Char, val vowel: Char, val f0a: Float, val f0b: Float, val ms: Int, val amp: Float = 1f,
                      val growl: Boolean = false, val tremolo: Boolean = false)

    /** Says something in Poke's voice, shaped by its current mood and personality. */
    fun chirp(c: Context?, kind: Kind, mind: Mind, proactive: Boolean = false) {
        c ?: return
        if (!mind.soundAllowed(proactive)) return
        val now = System.currentTimeMillis()
        if (now - lastSound < 350) return
        lastSound = now
        // personality and mood shape the voice: energetic and happy is higher and quicker
        val e = (mind.traits[Mind.ENERGETIC] + 1f) / 2f
        val bold = (mind.traits[Mind.BOLD] + 1f) / 2f
        val base = 380f * 2f.pow((0.35f * mind.arousal + 0.15f * mind.valence + 0.15f * e - 0.15f * bold) )
        val speed = 1.15f - 0.35f * mind.arousal - 0.1f * e
        val st = { s: Float -> 2f.pow(s / 12f) } // semitones
        val sy = when (kind) {
            Kind.HAPPY -> if (Random.nextFloat() < 0.4f) listOf(
                Syl('p', 'o', base, base * st(2f), (90 * speed).toInt()),
                Syl('k', 'e', base * st(3f), base * st(8f), (130 * speed).toInt()),
            ) else listOf(
                Syl('p', 'o', base * st(1f), base * st(4f), (110 * speed).toInt()),
                Syl('k', 'e', base * st(5f), base * st(9f), (120 * speed).toInt()),
                Syl('p', 'o', base * st(6f), base * st(10f), (90 * speed).toInt(), 0.8f),
            )
            Kind.PROUD -> listOf(
                Syl('p', 'o', base * st(-1f), base * st(2f), 140),
                Syl('k', 'e', base * st(4f), base * st(7f), 200),
            )
            Kind.CALL -> listOf(Syl('p', 'o', base, base * st(7f), (260 * speed).toInt(), 0.9f)) // "po?"
            Kind.LISTEN -> listOf(Syl('p', 'o', base * st(2f), base * st(9f), 180, 0.8f)) // a perked-up "po?"
            Kind.ALARM -> (0 until 3).flatMap { // "po-ke! po-ke! po-ke!" for a finished timer
                listOf(
                    Syl('p', 'o', base * st(3f), base * st(5f), 110),
                    Syl('k', 'e', base * st(8f), base * st(12f), 150),
                    Syl(' ', 'u', base, base, 160, 0f),
                )
            }
            Kind.SULK -> listOf(
                Syl('p', 'o', base * st(-2f), base * st(-4f), 260, 0.6f),
                Syl('k', 'e', base * st(-5f), base * st(-9f), 420, 0.5f),
            )
            Kind.GRUMBLE -> listOf(
                Syl('p', 'o', base * st(-7f), base * st(-8f), 110, 0.8f, growl = true),
                Syl('k', 'e', base * st(-8f), base * st(-10f), 140, 0.8f, growl = true),
            )
            Kind.STARTLED -> listOf(Syl('p', 'o', base * st(6f), base * st(13f), 140, 1f))
            Kind.PURR -> listOf(Syl('p', 'u', base * st(-3f), base * st(-1f), 650, 0.55f, tremolo = true))
            Kind.CONFUSED -> listOf(
                Syl('p', 'o', base, base * st(-1f), 120, 0.7f),
                Syl(' ', 'o', base * st(-1f), base * st(6f), 260, 0.7f),
            )
        }
        val pcm = render(sy)
        chirpUntil = System.currentTimeMillis() + pcm.size * 1000L / RATE + 500
        worker.execute { play(pcm) }
    }

    // ------------------------------------------------------------- synthesis

    private fun render(syls: List<Syl>): ShortArray {
        val gap = (RATE * 0.035f).toInt()
        val total = syls.sumOf { (it.ms * RATE / 1000) + gap } + RATE / 20
        val out = FloatArray(total)
        var at = 0
        for (s in syls) {
            at += syllable(s, out, at) + gap
        }
        // gentle limiter
        val peak = max(0.0001f, out.maxOf { kotlin.math.abs(it) })
        val g = 0.85f / peak
        return ShortArray(out.size) { (out[it] * g * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
    }

    /** Formants for each vowel (Hz): a soft "o", an "eh" and an "oo". */
    private fun formants(v: Char) = when (v) {
        'e' -> floatArrayOf(480f, 1900f, 2600f)
        'u' -> floatArrayOf(330f, 800f, 2300f)
        else -> floatArrayOf(520f, 900f, 2500f)
    }

    private fun syllable(s: Syl, out: FloatArray, start: Int): Int {
        var i = start
        // consonant: a short filtered noise click
        if (s.cons != ' ') {
            val len = (RATE * (if (s.cons == 'k') 0.018f else 0.010f)).toInt()
            var prev = 0f
            for (j in 0 until len) {
                if (i >= out.size) break
                val n = Random.nextFloat() * 2f - 1f
                val hp = n - prev * (if (s.cons == 'k') 0.2f else 0.85f)
                prev = n
                val env = 1f - j.toFloat() / len
                out[i++] += hp * env * 0.35f * s.amp
            }
        }
        val len = s.ms * RATE / 1000
        val f = formants(s.vowel)
        var phase = 0.0
        for (j in 0 until len) {
            if (i >= out.size) break
            val t = j.toFloat() / len
            // glide in log-frequency, slight vibrato
            val f0 = s.f0a * (s.f0b / s.f0a).pow(t) * (1f + 0.012f * sin(2.0 * PI * 6.0 * j / RATE).toFloat())
            phase += 2.0 * PI * f0 / RATE
            var v = 0f
            var h = 1
            while (h <= 14 && h * f0 < RATE / 2.2f) {
                val fh = h * f0
                var a = 0f
                for ((k, fk) in f.withIndex()) {
                    val bw = 90f + 40f * k
                    a += (if (k == 0) 1f else 0.5f / k) / (1f + ((fh - fk) / bw).pow(2))
                }
                v += (a / h) * sin(h * phase).toFloat()
                h++
            }
            if (s.growl) v += 0.35f * sin(phase * 0.5).toFloat() * (if (sin(phase * 0.25) > 0) 1f else 0.4f)
            // envelope: quick attack, smooth release
            val attack = min(1f, j / (RATE * 0.012f))
            val release = min(1f, (len - j) / (RATE * 0.05f))
            var env = attack * release * (0.85f + 0.15f * exp(-3f * t))
            if (s.tremolo) env *= 0.75f + 0.25f * sin(2.0 * PI * 9.0 * j / RATE).toFloat()
            out[i++] += v * env * s.amp
        }
        return i - start
    }

    private fun play(pcm: ShortArray) {
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(pcm.size * 1000L / RATE + 80)
            track.release()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------- words

    /** True while Poke is making any sound (its ears ignore that, plus an echo tail). */
    val speaking: Boolean get() = System.currentTimeMillis() < chirpUntil || Speech.speaking()
    @Volatile private var chirpUntil = 0L

    /** Speaks a caveman sentence with eSpeak NG, pitched by mood (if words are turned on). */
    fun say(c: Context, text: String, mind: Mind) {
        if (!mind.soundAllowed(false) || !Speech.wordsOn(c)) return
        val e = (mind.traits[Mind.ENERGETIC] + 1f) / 2f
        val pitch = (62 + 18 * mind.valence + 10 * mind.arousal + 6 * e).toInt()
        val range = (60 + 30 * mind.arousal).toInt()
        val wpm = (165 + 50 * mind.arousal + 20 * e - (if (mind.valence < -0.3f) 25 else 0)).toInt()
        Speech.say(c, text, pitch, range, wpm)
    }
}

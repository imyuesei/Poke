package com.monolith.poke

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.util.concurrent.Executors

/**
 * Poke's words, spoken with eSpeak NG: tiny, fully offline and very fast (a sentence takes a
 * few milliseconds to synthesize), so the answer starts right away. Its data lives in the
 * APK's assets and is copied to app storage once.
 */
object Speech {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "poke-speech") }
    @Volatile private var rate = 0
    @Volatile private var track: AudioTrack? = null
    /** Until when Poke is talking (the ears stay deaf until then, plus an echo tail). */
    @Volatile var busyUntil = 0L
        private set

    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary("pokebrain")
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun speaking() = System.currentTimeMillis() < busyUntil

    /** Whether words are spoken aloud (a setting in the brain lab; the bubble always shows them). */
    fun wordsOn(c: Context) = c.getSharedPreferences("poke_voice", Context.MODE_PRIVATE).getBoolean("words", true)
    fun setWordsOn(c: Context, on: Boolean) =
        c.getSharedPreferences("poke_voice", Context.MODE_PRIVATE).edit().putBoolean("words", on).apply()

    private fun ensure(c: Context): Boolean {
        if (rate > 0) return true
        if (!loaded) return false
        val root = c.filesDir
        val dir = File(root, "espeak-ng-data")
        val stamp = File(dir, ".version")
        val version = try {
            c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime.toString()
        } catch (_: Exception) {
            "0"
        }
        if (!stamp.exists() || stamp.readText() != version) {
            dir.deleteRecursively()
            copyAssets(c, "espeak-ng-data", dir)
            stamp.writeText(version)
        }
        rate = try { nativeInit(root.absolutePath) } catch (_: Throwable) { -1 }
        return rate > 0
    }

    private fun copyAssets(c: Context, path: String, to: File) {
        val list = c.assets.list(path) ?: return
        if (list.isEmpty()) {
            to.parentFile?.mkdirs()
            c.assets.open(path).use { input -> to.outputStream().use { input.copyTo(it) } }
            return
        }
        to.mkdirs()
        for (name in list) copyAssets(c, "$path/$name", File(to, name))
    }

    /**
     * Says [text]. Interrupts whatever Poke was saying (it never queues up repeats).
     * [pitch] and [range] are 0-100, [wpm] is words per minute.
     */
    fun say(c: Context, text: String, pitch: Int, range: Int, wpm: Int) {
        val app = c.applicationContext
        busyUntil = System.currentTimeMillis() + 400
        worker.execute {
            if (!ensure(app)) return@execute
            val pcm = try {
                nativeSynth(text, "en-us+f3", wpm.coerceIn(80, 400), pitch.coerceIn(0, 99), range.coerceIn(0, 99))
            } catch (_: Throwable) {
                null
            } ?: return@execute
            if (pcm.isEmpty()) return@execute
            play(pcm)
        }
    }

    private fun play(pcm: ShortArray) {
        try {
            track?.let {
                try { it.stop() } catch (_: Exception) {}
                it.release()
            }
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(pcm, 0, pcm.size)
            val ms = pcm.size * 1000L / rate
            busyUntil = System.currentTimeMillis() + ms + 700 // + echo tail
            t.play()
            track = t
        } catch (_: Exception) {
        }
    }

    private external fun nativeInit(dataDir: String): Int
    private external fun nativeSynth(text: String, voice: String, rate: Int, pitch: Int, range: Int): ShortArray?
}

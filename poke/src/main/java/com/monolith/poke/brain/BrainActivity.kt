package com.monolith.poke.brain

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.monolith.poke.Mind
import com.monolith.poke.Voice
import kotlin.concurrent.thread

/**
 * Poke's brain lab (stage 0): download the brain, talk or type to Poke, see what Needle and
 * Whistle make of it and how fast, and watch Poke's mind. Opened by holding the "poke" title.
 */
class BrainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var said: TextView
    private lateinit var detail: TextView
    private lateinit var mind: TextView
    private lateinit var memory: TextView
    private lateinit var input: EditText
    private lateinit var talk: TextView
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var recording = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        window.statusBarColor = BG
        window.navigationBarColor = BG
        Mind.attach(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }
        fun text(size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(6), 0, dp(6))
        }
        fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFF0E0F11.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = GradientDrawable().apply { setColor(0xFFEFF6FA.toInt()); cornerRadius = 26 * d }
            setOnClickListener { onClick() }
        }
        fun gap(h: Int) = View(this).apply { minimumHeight = dp(h) }

        col.addView(text(26f, bold = true).apply { text = "poke's brain" })
        status = text(13f, 0xAAFFFFFF.toInt())
        col.addView(status)
        col.addView(button("download / load brain") { prepare() })
        col.addView(gap(16))

        input = EditText(this).apply {
            hint = "tell poke something…  e.g. I parked on level 3 near the blue pillar"
            setHintTextColor(0x66FFFFFF)
            setTextColor(Color.WHITE)
            textSize = 15f
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            setOnEditorActionListener { _, _, _ -> send(); true }
        }
        col.addView(input)
        col.addView(button("tell poke") { send() })
        col.addView(gap(10))
        talk = button("hold to talk") {}
        talk.setOnTouchListener(::onTalkTouch)
        col.addView(talk)
        col.addView(gap(10))
        col.addView(button("speed test") { benchmark() })

        col.addView(gap(16))
        col.addView(text(12f, 0x88FFFFFF.toInt()).apply { text = "POKE SAYS" })
        said = text(22f, bold = true).apply { text = "…" }
        col.addView(said)
        detail = text(12f, 0x99FFFFFF.toInt()).apply { typeface = Typeface.MONOSPACE }
        col.addView(detail)

        col.addView(gap(12))
        col.addView(text(12f, 0x88FFFFFF.toInt()).apply { text = "MIND" })
        mind = text(12f, 0xCCFFFFFF.toInt()).apply { typeface = Typeface.MONOSPACE }
        col.addView(mind)
        col.addView(gap(12))
        col.addView(text(12f, 0x88FFFFFF.toInt()).apply { text = "MEMORY" })
        memory = text(13f, 0xCCFFFFFF.toInt())
        col.addView(memory)

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(col) })
        refresh()
        main.post(pulse)
    }

    private val pulse = object : Runnable {
        override fun run() {
            mind.text = Mind.describe() + "\nface ${Mind.face.name.lowercase()}"
            main.postDelayed(this, 1000)
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(pulse)
        Mind.detach()
        super.onDestroy()
    }

    private fun refresh() {
        val engine = if (Brain.engineBuilt) "engine ✓" else "engine ✗ (not in this build)"
        val models = Brain.MODELS.joinToString("  ") { m ->
            val f = Brain.modelFile(this, m)
            if (f.exists()) "${m.name} ✓ ${f.length() / 1_000_000} MB" else "${m.name} —"
        }
        val loaded = if (Brain.loaded) "loaded in ${Brain.loadMs} ms" else "not loaded"
        status.text = "$engine · $loaded\n$models"
        memory.text = Memory.summary(this)
    }

    private fun prepare() {
        if (busy) return
        busy = true
        status.text = "starting…"
        thread {
            try {
                Brain.download(this) { msg -> main.post { status.text = msg } }
                Brain.async({ Brain.load(this) }) { r ->
                    busy = false
                    r.exceptionOrNull()?.let { said.text = "Po…?"; detail.text = "load failed: ${it.message}" }
                    refresh()
                }
            } catch (e: Exception) {
                main.post {
                    busy = false
                    status.text = "download failed: ${e.message}"
                }
            }
        }
    }

    private fun send() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        understand(t, null)
    }

    /** Needle reads [text] and Poke acts and answers. */
    private fun understand(text: String, heardMs: Long?) {
        said.text = "…"
        Brain.async({
            val thought = Brain.think(this, text, Skills.TOOLS, Skills.system())
            thought to Skills.act(this, thought)
        }) { r ->
            r.onSuccess { (thought, reply) ->
                said.text = reply.words
                Voice.say(this, reply.words, Mind)
                val call = thought.firstCall?.toString() ?: thought.raw.optJSONArray("suppressed_calls")?.toString() ?: "none"
                detail.text = buildString {
                    append("heard: \"").append(text).append("\"\n")
                    if (heardMs != null) append("whistle: ").append(heardMs).append(" ms\n")
                    append("needle: ").append(thought.ms).append(" ms")
                    val c = thought.confidence
                    if (!c.isNaN()) append(" · confidence ").append("%.2f".format(c))
                    append("\ncall: ").append(call)
                    append("\n").append(reply.detail)
                    thought.raw.optString("error").takeIf { it.isNotBlank() && it != "null" }?.let { append("\nerror: ").append(it) }
                }
                refresh()
            }.onFailure {
                said.text = "Po…?"
                detail.text = "error: ${it.message}"
            }
        }
    }

    // ------------------------------------------------------------------ ears

    @SuppressLint("ClickableViewAccessibility")
    private fun onTalkTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
                    return true
                }
                startRecording()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> recording = false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (recording) return
        recording = true
        talk.text = "listening… (let go when done)"
        thread {
            val rate = 16000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
            val maxSamples = rate * 15
            val pcm = ShortArray(maxSamples)
            var n = 0
            try {
                rec.startRecording()
                val buf = ShortArray(1024)
                while (recording && n < maxSamples) {
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
                recording = false
            }
            val samples = FloatArray(n) { pcm[it] / 32768f }
            main.post {
                talk.text = "hold to talk"
                if (n < rate / 4) return@post
                said.text = "…"
                Brain.async({ Brain.hear(this, samples, "Poke") }) { r ->
                    r.onSuccess { h ->
                        if (h.text.isBlank()) {
                            said.text = "Po…?"
                            detail.text = "whistle heard nothing (${h.ms} ms) ${h.raw.optString("error")}"
                        } else {
                            understand(h.text, h.ms)
                        }
                    }.onFailure { said.text = "Po…?"; detail.text = "error: ${it.message}" }
                }
            }
        }
    }

    // ------------------------------------------------------------------ speed test

    private fun benchmark() {
        val prompts = listOf(
            "I parked on level 3 near the blue pillar",
            "where did I park",
            "my keys are in the kitchen drawer",
            "good job poke",
            "set a timer for 5 minutes",
            "my sister's name is Mia",
        )
        said.text = "testing…"
        Brain.async({
            prompts.map { p ->
                val t = Brain.think(this, p, Skills.TOOLS, Skills.system())
                Triple(p, t.ms, t.firstCall?.optString("name") ?: "—") to t.confidence
            }
        }) { r ->
            r.onSuccess { rows ->
                said.text = "Poke think fast!"
                detail.text = rows.joinToString("\n") { (row, c) ->
                    "${row.second} ms  ${row.third}  ${if (c.isNaN()) "" else "%.2f".format(c)}  \"${row.first}\""
                } + "\navg ${rows.map { it.first.second }.average().toInt()} ms"
                refresh()
            }.onFailure { said.text = "Po…?"; detail.text = "error: ${it.message}" }
        }
    }

    companion object {
        const val BG = 0xFF101113.toInt()
    }
}

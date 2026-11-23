package com.monolith.poke

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.monolith.poke.brain.Brain
import kotlin.concurrent.thread

/** Settings: models, CPU threads per model, voice input, Needle's tools and facts, chat history. */
class SettingsActivity : Activity() {
    private lateinit var col: LinearLayout
    private lateinit var modelStatus: TextView
    private val main = Handler(Looper.getMainLooper())
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(ChatActivity.BG); addView(col) })

        col.addView(text(24f, bold = true).apply { text = "Settings" })

        // models
        header("MODELS")
        modelStatus = text(13f, 0xCCFFFFFF.toInt())
        col.addView(modelStatus)
        col.addView(button("Download / load models") { prepare() })
        refreshModels()

        // threads
        header("CPU THREADS")
        for (m in Brain.MODELS) col.addView(threadRow(m))
        col.addView(note("Auto uses every core. More threads are faster until the phone gets hot. Changing a value reloads the model."))

        // voice
        header("SPEECH RECOGNITION")
        val auto = button("") {}
        fun showAuto() { auto.text = if (Settings.autoSend(this)) "Send what I say straight away: on" else "Send what I say straight away: off (fills the box)" }
        auto.setOnClickListener { Settings.setAutoSend(this, !Settings.autoSend(this)); showAuto() }
        showAuto()
        col.addView(auto)
        col.addView(stepRow({ "Pause that ends a message: ${"%.2f".format(Settings.pauseSeconds(this))} s" },
            { Settings.setPauseSeconds(this, Settings.pauseSeconds(this) - 0.25f) },
            { Settings.setPauseSeconds(this, Settings.pauseSeconds(this) + 0.25f) }))

        // tools
        header("NEEDLE'S TOOLS (JSON)")
        col.addView(note("Needle answers each message with a call to one of these tools, or no call if none fits. " +
            "Five tools or fewer with plain names work best. The built-in tools (set_timer, get_weather, search_web, " +
            "send_message, create_note) carry out real actions; tools you add only show the call."))
        val tools = EditText(this).apply {
            setText(Settings.tools(this@SettingsActivity))
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = false
            minLines = 8
            gravity = Gravity.TOP
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { setColor(0xFF1E2023.toInt()); cornerRadius = 12 * d }
        }
        col.addView(tools)
        val toolsMsg = note("")
        col.addView(button("Save tools") {
            val err = Settings.setTools(this, tools.text.toString())
            toolsMsg.text = err?.let { "Not saved: $it" } ?: "Saved."
        })
        col.addView(button("Reset to example tools") {
            Settings.resetTools(this)
            tools.setText(Settings.tools(this))
            toolsMsg.text = "Reset."
        })
        col.addView(toolsMsg)

        // facts
        header("FACTS FOR NEEDLE")
        col.addView(note("Optional environment facts, e.g. \"locale: en-US; user: Sam; location: Berlin\". Today's date is added automatically."))
        val facts = EditText(this).apply {
            setText(Settings.facts(this@SettingsActivity))
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { setColor(0xFF1E2023.toInt()); cornerRadius = 12 * d }
        }
        col.addView(facts)
        col.addView(button("Save facts") { Settings.setFacts(this, facts.text.toString()) })

        // notes saved by create_note
        header("NOTES")
        val notes = Notes.all(this)
        val fmt = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
        col.addView(text(14f, 0xDDFFFFFF.toInt()).apply {
            text = if (notes.isEmpty()) "(none yet — try “note that the wifi password is cactus42”)"
            else notes.joinToString("\n\n") { "${fmt.format(java.util.Date(it.first))}\n${it.second}" }
            setTextIsSelectable(true)
        })
        if (notes.isNotEmpty()) col.addView(button("Delete all notes") { Notes.clear(this); recreate() })

        // chat
        header("CHAT")
        col.addView(button("Clear chat history") { History.clear(this) })

        header("ABOUT")
        col.addView(note(try {
            "Version " + packageManager.getPackageInfo(packageName, 0).versionName +
                " · Needle 3 and Whistle by Cactus, running fully on this phone. The app updates itself."
        } catch (_: Exception) {
            ""
        }))
    }

    private fun refreshModels() {
        val engine = if (Brain.engineBuilt) "engine ✓" else "engine ✗ (not in this build)"
        val files = Brain.MODELS.joinToString("\n") { m ->
            val f = Brain.modelFile(this, m)
            if (f.exists()) "${m.name} ✓ ${f.length() / 1_000_000} MB" else "${m.name} — not downloaded (~${m.approxMb} MB)"
        }
        val loaded = if (Brain.loaded) "loaded in ${Brain.loadMs} ms" else "not loaded"
        modelStatus.text = "$engine · $loaded\n$files"
    }

    private fun prepare() {
        if (busy) return
        busy = true
        modelStatus.text = "Starting…"
        thread {
            try {
                Brain.download(this) { msg -> main.post { modelStatus.text = msg } }
                Brain.async({ Brain.load(this) }) { r ->
                    busy = false
                    refreshModels()
                    r.exceptionOrNull()?.let { modelStatus.append("\nLoad failed: ${it.message}") }
                }
            } catch (e: Exception) {
                main.post {
                    busy = false
                    modelStatus.text = "Download failed: ${e.message}"
                }
            }
        }
    }

    private fun threadRow(m: Brain.Model): View = stepRow(
        {
            val n = Brain.threads(this, m)
            val who = if (m == Brain.NEEDLE) "Needle 3" else "Whistle"
            "$who: ${if (n == 0) "auto" else n.toString()} (of ${Brain.cores})"
        },
        { Brain.setThreads(this, m, Brain.threads(this, m) - 1); refreshModels() },
        { Brain.setThreads(this, m, Brain.threads(this, m) + 1); refreshModels() },
    )

    private fun stepRow(label: () -> String, minus: () -> Unit, plus: () -> Unit): View {
        val l = text(14f).apply { text = label() }
        fun step(s: String, action: () -> Unit) = TextView(this).apply {
            text = s
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(0xFF0E0F11.toInt())
            background = GradientDrawable().apply { setColor(0xFFE8EAED.toInt()); cornerRadius = 20 * d }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(40)).apply { leftMargin = dp(8) }
            setOnClickListener { action(); l.text = label() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(l, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(step("−", minus))
            addView(step("+", plus))
        }
    }

    private fun header(t: String) {
        col.addView(View(this).apply { minimumHeight = dp(20) })
        col.addView(text(12f, 0x88FFFFFF.toInt()).apply { text = t })
    }

    private fun note(t: String) = text(12f, 0x88FFFFFF.toInt()).apply { text = t }

    private fun text(size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(0xFF0E0F11.toInt())
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply { setColor(0xFFE8EAED.toInt()); cornerRadius = 22 * d }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }
}

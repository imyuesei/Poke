package com.monolith.poke

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.monolith.poke.agent.Agent
import com.monolith.poke.agent.Result
import com.monolith.poke.agent.SendMessage
import com.monolith.poke.brain.Brain
import org.json.JSONArray
import org.json.JSONObject

/**
 * A chat with Needle 3, on the phone. Type or tap the mic (Whistle turns speech into text;
 * a pause ends a message). Needle answers every message with the tool call it picks from
 * the tools in settings, or with no call when none fits.
 */
class ChatActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var mic: ImageButton
    private lateinit var send: ImageButton
    private lateinit var status: TextView
    private var micDot: GradientDrawable? = null
    private var listener: Mic? = null
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        // top bar: title, status and the settings icon
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(6), dp(8))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        status = TextView(this).apply { textSize = 12f; setTextColor(0x99FFFFFF.toInt()) }
        titles.addView(status)
        bar.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(icon(R.drawable.ic_settings, "Settings") { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(bar)

        // messages
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        scroll = ScrollView(this).apply { addView(list); isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // input row: the chat box, send (when there is text), and the mic on its right
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(8), dp(10))
        }
        input = EditText(this).apply {
            hint = "Message"
            setHintTextColor(0x66FFFFFF)
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 5
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(16), dp(11), dp(16), dp(11))
            background = GradientDrawable().apply { setColor(0xFF1E2023.toInt()); cornerRadius = 24 * d }
            setOnEditorActionListener { _, _, _ -> sendTyped(); true }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    send.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
                }
            })
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        send = icon(R.drawable.ic_send, "Send") { sendTyped() }.apply { visibility = View.GONE }
        row.addView(send)
        mic = icon(R.drawable.ic_mic, "Speech recognition") { toggleMic() }
        micDot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF2A2D31.toInt()) }
        mic.background = micDot
        row.addView(mic, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(6) })
        root.addView(row)

        setContentView(root)
        History.load(this).forEach { show(it, scroll = false) }
        scrollDown()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        Updater.check(this, this)
        if (Brain.hasModels(this) && !Brain.loaded) {
            Brain.async({ Brain.load(this) }) { refreshStatus() }
        }
        if (History.load(this).isEmpty() && list.childCount == 0) intro()
    }

    override fun onPause() {
        stopMic()
        super.onPause()
    }

    private fun refreshStatus() {
        status.text = when {
            !Brain.engineBuilt -> "engine missing from this build"
            !Brain.hasModels(this) -> "models not downloaded · open settings"
            !Brain.loaded -> "loading Needle 3 + Whistle…"
            listener?.isOn == true -> "listening…"
            else -> "Needle 3 + Whistle · on device"
        }
    }

    private fun intro() {
        val m = Msg("needle", "Hi. I'm Needle 3 running on your phone, with Whistle for speech.\n\n" +
            "I don't write replies: I pick a tool for your message, fill in its details and run it, and can follow up " +
            "on the result. I can set timers, check the weather, search, draft messages, save notes, run skills, " +
            "and remember things you tell me (tell me your name and city).\n\n" +
            (if (!Brain.hasModels(this)) "First, open settings and download the models (~52 MB)." else "Try: \"remind me to set a 5 minute timer\" or \"text Sam I'm running late\"."),
            info = true)
        show(m)
    }

    // ------------------------------------------------------------------ sending

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        ask(t, voice = null)
    }

    private val agent by lazy { Agent(this, ui) }

    private fun ask(text: String, voice: Long?) {
        val mine = Msg("user", text, ms = voice, voice = voice != null)
        show(mine)
        History.add(this, mine)
        if (!Brain.hasModels(this)) {
            show(Msg("needle", "Download the models in settings first.", info = true))
            return
        }
        thinking = show(Msg("needle", "…", info = true))
        agent.ask(text)
    }

    private var thinking: View? = null

    private fun keep(m: Msg) {
        thinking?.let { list.removeView(it); thinking = null }
        show(m)
        History.add(this, m)
    }

    /** The agent's steps, as chat messages. */
    private val ui = object : Agent.Ui {
        override fun tools(names: List<String>) {
            if (Settings.showTrace(this@ChatActivity)) keep(Msg("trace", "tools: " + names.joinToString(", "), info = true))
        }

        override fun call(text: String, confidence: Double?, ms: Long, reasoning: String, held: Boolean) =
            keep(Msg("needle", reasoning, call = text, confidence = confidence, ms = ms, held = held))

        override fun result(r: Result) = keep(Msg("result", (if (r.ok) "✓ " else "✗ ") + r.say, info = true))

        override fun ask(question: String, yes: () -> Unit) {
            thinking?.let { list.removeView(it); thinking = null }
            show(Msg("ask", question, info = true, onYes = yes))
        }

        override fun none(text: String) = keep(Msg("needle", text, info = true))

        override fun info(text: String) = keep(Msg("trace", text, info = true))

        override fun done() {
            thinking?.let { list.removeView(it); thinking = null }
            refreshStatus()
        }
    }

    // ------------------------------------------------------------------ mic

    private fun toggleMic() {
        if (listener?.isOn == true) stopMic() else startMic()
    }

    private fun startMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!Brain.hasModels(this)) {
            show(Msg("needle", "Download the models in settings first.", info = true))
            return
        }
        val m = Mic((Settings.pauseSeconds(this) * 1000).toInt(),
            onMessage = { clip -> runOnUiThread { heard(clip) } },
            onLevel = { level, speaking ->
                runOnUiThread {
                    micDot?.setColor(if (listener?.isOn == true) (if (speaking) 0xFFE5484D.toInt() else 0xFF9B2C30.toInt()) else 0xFF2A2D31.toInt())
                    val s = 1f + (level * 6f).coerceAtMost(0.25f)
                    mic.scaleX = s
                    mic.scaleY = s
                }
            })
        listener = m
        m.start()
        micDot?.setColor(0xFF9B2C30.toInt())
        refreshStatus()
    }

    private fun stopMic() {
        listener?.stop()
        listener = null
        micDot?.setColor(0xFF2A2D31.toInt())
        mic.scaleX = 1f
        mic.scaleY = 1f
        refreshStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startMic()
        if (requestCode == SendMessage.CONTACTS_REQUEST) SendMessage.onContactsAnswered(this)
    }

    /** One spoken message: Whistle turns it into text. */
    private fun heard(clip: FloatArray) {
        Brain.async({ Brain.hear(this, clip) }) { r ->
            val h = r.getOrNull() ?: return@async
            if (h.text.isBlank()) return@async
            if (Settings.autoSend(this)) ask(h.text, voice = h.ms) else {
                input.setText((input.text.toString() + " " + h.text).trim())
                input.setSelection(input.text.length)
            }
        }
    }

    // ------------------------------------------------------------------ views

    private fun icon(res: Int, label: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(res)
        contentDescription = label
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener { onClick() }
    }

    private fun show(m: Msg, scroll: Boolean = true): View {
        val mine = m.role == "user"
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(when {
                    mine -> 0xFF2B4C7E.toInt()
                    m.role == "result" -> 0xFF173326.toInt()
                    m.role == "trace" -> 0xFF141518.toInt()
                    m.role == "ask" -> 0xFF2E2716.toInt()
                    m.info -> 0xFF18191C.toInt()
                    else -> 0xFF1E2023.toInt()
                })
                cornerRadius = 18 * d
            }
        }
        if (m.call != null) {
            bubble.addView(TextView(this).apply {
                text = m.call
                textSize = 15f
                setTextColor(if (m.held) 0xFFE8C46A.toInt() else 0xFF8AB4F8.toInt())
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            })
        }
        if (m.text.isNotBlank()) {
            bubble.addView(TextView(this).apply {
                text = m.text
                textSize = if (m.call != null || m.role == "trace") 12f else 15f
                setTextColor(if (m.role == "result") 0xFFB8F0CF.toInt() else if (m.info) 0xBBFFFFFF.toInt() else Color.WHITE)
                if (m.call != null) setTextColor(0xAAFFFFFF.toInt())
                setTextIsSelectable(true)
            })
        }
        val meta = listOfNotNull(
            if (m.voice) "🎤 whistle ${m.ms} ms" else null,
            if (!mine && m.ms != null) "needle ${m.ms} ms" else null,
            m.confidence?.let { "confidence ${"%.2f".format(it)}" },
        )
        if (meta.isNotEmpty()) {
            bubble.addView(TextView(this).apply {
                text = meta.joinToString(" · ")
                textSize = 11f
                setTextColor(0x88FFFFFF.toInt())
                setPadding(0, dp(4), 0, 0)
            })
        }
        val yes = m.onYes
        if (yes != null) {
            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            fun choice(label: String, color: Int, action: () -> Unit) = TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(0xFF0E0F11.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(16), dp(7), dp(16), dp(7))
                background = GradientDrawable().apply { setColor(color); cornerRadius = 16 * d }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { rightMargin = dp(8) }
                setOnClickListener {
                    buttons.visibility = View.GONE
                    action()
                }
            }
            buttons.addView(choice("Run", 0xFFE8C46A.toInt()) { yes() })
            buttons.addView(choice("Cancel", 0xFFE8EAED.toInt()) { keep(Msg("trace", "Cancelled.", info = true)) })
            bubble.addView(buttons)
        }
        val wrap = FrameLayout(this).apply { setPadding(0, dp(4), 0, dp(4)) }
        wrap.addView(bubble, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            if (mine) Gravity.END else Gravity.START).apply {
            if (mine) leftMargin = dp(48) else rightMargin = dp(48)
        })
        list.addView(wrap)
        if (scroll) scrollDown()
        return wrap
    }

    private fun scrollDown() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    companion object {
        const val BG = 0xFF0F1012.toInt()
    }
}

/** One chat message. Needle's messages carry the tool call it made. */
class Msg(
    val role: String,
    val text: String,
    val call: String? = null,
    val confidence: Double? = null,
    val ms: Long? = null,
    val voice: Boolean = false,
    val held: Boolean = false,
    val info: Boolean = false,
    /** For a question from the agent: what Run does (not saved in history). */
    val onYes: (() -> Unit)? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("role", role).put("text", text).put("call", call)
        .put("confidence", confidence).put("ms", ms).put("voice", voice).put("held", held).put("info", info)

    companion object {
        fun fromJson(o: JSONObject) = Msg(
            o.optString("role"), o.optString("text"),
            if (o.isNull("call")) null else o.optString("call"),
            if (o.isNull("confidence")) null else o.optDouble("confidence"),
            if (o.isNull("ms")) null else o.optLong("ms"),
            o.optBoolean("voice"), o.optBoolean("held"), o.optBoolean("info"),
        )
    }
}

/** The chat history, kept on the phone (last 200 messages). */
object History {
    private fun p(c: Context) = c.getSharedPreferences("chat", Context.MODE_PRIVATE)

    fun load(c: Context): List<Msg> = try {
        val a = JSONArray(p(c).getString("messages", "[]"))
        (0 until a.length()).map { Msg.fromJson(a.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }

    fun add(c: Context, m: Msg) {
        val a = JSONArray(p(c).getString("messages", "[]"))
        a.put(m.toJson())
        val trimmed = JSONArray()
        for (i in maxOf(0, a.length() - 200) until a.length()) trimmed.put(a.get(i))
        p(c).edit().putString("messages", trimmed.toString()).apply()
    }

    fun clear(c: Context) = p(c).edit().remove("messages").apply()
}

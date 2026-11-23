package com.monolith.poke.brain

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.monolith.poke.Mind
import kotlin.concurrent.thread

/**
 * Poke's journal (stages 3-5): how it is growing up, a chart of its personality over time,
 * its diary (written at night), its opinions, the tricks it knows and what it remembers.
 */
class JournalActivity : Activity() {
    private lateinit var col: LinearLayout
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Mind.attach(this)
        @Suppress("DEPRECATION")
        window.statusBarColor = BrainActivity.BG
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(BrainActivity.BG); addView(col) })
        load()
    }

    override fun onDestroy() {
        Mind.detach()
        super.onDestroy()
    }

    private fun load() {
        val app = applicationContext
        thread {
            val history = Sleep.history(app, 60)
            val diary = Sleep.diary(app, 14)
            val opinions = Opinions.summary(app)
            val tricks = Tricks.summary(app)
            val memories = Memory.summary(app)
            runOnUiThread { render(history, diary, opinions, tricks, memories) }
        }
    }

    private fun render(history: List<Sleep.Snapshot>, diary: List<Pair<String, String>>, opinions: String, tricks: String, memories: String) {
        col.removeAllViews()
        col.addView(text(26f, bold = true).apply { text = "poke's journal" })
        col.addView(text(14f, 0xCCFFFFFF.toInt()).apply {
            text = "${Mind.stage().name.lowercase()} · ${Mind.ageDays()} days old\n" + Mind.describe().substringBefore("\n")
        })

        header("PERSONALITY OVER TIME")
        col.addView(TraitChart(this, history), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180)))
        col.addView(text(11f, 0xAAFFFFFF.toInt()).apply {
            text = Mind.TRAIT_NAMES.indices.joinToString("   ") { "● ${Mind.TRAIT_NAMES[it]}" }
            setTextColor(0xAAFFFFFF.toInt())
        })
        if (history.size < 2) col.addView(text(11f, 0x77FFFFFF.toInt()).apply { text = "(the chart fills in one point a day)" })

        header("DIARY")
        col.addView(text(13f, 0xDDFFFFFF.toInt()).apply {
            text = if (diary.isEmpty()) "(Poke writes its diary at night while it sleeps)"
            else diary.joinToString("\n\n") { "${it.first}\n${it.second}" }
        })

        header("OPINIONS")
        col.addView(text(13f, 0xDDFFFFFF.toInt()).apply { text = opinions })
        col.addView(text(11f, 0x77FFFFFF.toInt()).apply {
            text = "Poke forms opinions about apps (needs Accessibility on) and about things you talk about."
        })

        header("TRICKS")
        col.addView(text(13f, 0xDDFFFFFF.toInt()).apply { text = tricks })
        if (com.monolith.poke.brain.Actions.needsQuietPermission(this)) {
            col.addView(button("let Poke control do not disturb") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            })
        }
        col.addView(button("forget all tricks") { thread { Tricks.forgetAll(applicationContext); load() } })

        header("MEMORIES")
        col.addView(text(13f, 0xDDFFFFFF.toInt()).apply { text = memories })
        col.addView(button("forget opinions") { thread { Opinions.clear(applicationContext); load() } })
    }

    private fun header(t: String) {
        col.addView(View(this).apply { minimumHeight = dp(18) })
        col.addView(text(12f, 0x88FFFFFF.toInt()).apply { text = t })
    }

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
        setPadding(dp(16), dp(11), dp(16), dp(11))
        background = GradientDrawable().apply { setColor(0xFFEFF6FA.toInt()); cornerRadius = 22 * d }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    companion object {
        val COLORS = intArrayOf(0xFFFFC857.toInt(), 0xFF6FD3FF.toInt(), 0xFFFF8FB1.toInt(), 0xFFB48CFF.toInt(), 0xFF7CE38B.toInt())
    }

    /** Five lines, one per trait, from -1 to +1 over the days. */
    private class TraitChart(c: Context, val history: List<Sleep.Snapshot>) : View(c) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * c.resources.displayMetrics.density }
        private val grid = Paint().apply { color = 0x22FFFFFF; strokeWidth = 1f }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawLine(0f, h / 2, w, h / 2, grid)
            canvas.drawLine(0f, 0f, w, 0f, grid)
            canvas.drawLine(0f, h - 1, w, h - 1, grid)
            val pts = history.ifEmpty { listOf(Sleep.Snapshot("now", Mind.traits.copyOf(), "")) }
            for (i in 0 until 5) {
                line.color = COLORS[i]
                dot.color = COLORS[i]
                path.reset()
                pts.forEachIndexed { j, s ->
                    val x = if (pts.size == 1) w / 2 else j * w / (pts.size - 1)
                    val y = h / 2 - s.traits[i].coerceIn(-1f, 1f) * (h / 2 - 6)
                    if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    if (pts.size == 1) canvas.drawCircle(x, y, 5f, dot)
                }
                canvas.drawPath(path, line)
            }
        }
    }
}

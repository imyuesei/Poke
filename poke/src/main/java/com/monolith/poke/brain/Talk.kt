package com.monolith.poke.brain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The live conversation feed shown in the brain lab: every time Poke hears something (from
 * the island, the wake word or the lab), what it heard, how long hearing and thinking took,
 * how it decided (rules / Needle / guard) and what it answered.
 */
object Talk {
    class Turn(val at: Long, val heard: String, val whistleMs: Long?, val needleMs: Long?, val how: String,
               val call: String, val reply: String, val detail: String)

    private val turns = ArrayDeque<Turn>()
    @Volatile var version = 0
        private set

    @Synchronized
    fun add(t: Turn) {
        turns.addFirst(t)
        while (turns.size > 30) turns.removeLast()
        version++
    }

    /** Also records what was heard when nothing came of it (silence, echo, no wake word). */
    fun note(heard: String, why: String, whistleMs: Long?) =
        add(Turn(System.currentTimeMillis(), heard, whistleMs, null, why, "", "", ""))

    @Synchronized
    fun render(n: Int): String {
        if (turns.isEmpty()) return "(nothing heard yet — swipe down on the island and talk)"
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        return turns.take(n).joinToString("\n\n") { t ->
            buildString {
                append(fmt.format(Date(t.at))).append("  heard: \"").append(t.heard).append('"')
                val times = listOfNotNull(t.whistleMs?.let { "whistle $it ms" }, t.needleMs?.let { "needle $it ms" })
                if (times.isNotEmpty()) append("\n  ").append(times.joinToString(" · "))
                append("\n  ").append(t.how)
                if (t.call.isNotBlank()) append(": ").append(t.call.take(160))
                if (t.reply.isNotBlank()) append("\n  poke: ").append(t.reply)
                if (t.detail.isNotBlank()) append("  (").append(t.detail).append(')')
            }
        }
    }
}

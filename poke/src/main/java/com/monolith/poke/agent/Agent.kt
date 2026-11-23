package com.monolith.poke.agent

import android.app.Activity
import com.monolith.poke.Settings
import com.monolith.poke.brain.Brain
import org.json.JSONObject

/**
 * The agent loop, the Needle way.
 *
 * An LLM harness (like Hermes) loops: the model thinks in text, calls tools, reads results,
 * and finally writes an answer. Needle 3 can't write, so here:
 *  1. Retrieve: pick the few tools that fit the message (embeddings).
 *  2. Ground: give Needle facts, not instructions: the date and the user's profile.
 *  3. Call: Needle returns one checked call with a confidence.
 *  4. Gate: low confidence, or a tool that sends something, asks the user first.
 *  5. Fill: a missing required argument is taken from the profile (e.g. your city).
 *  6. Act: run the tool; its result line is the answer (no generated text).
 *  7. Loop: feed the result back as JSON; Needle may make a follow-up call or finish
 *     (up to a few steps).
 *  8. Follow-up: if a short message makes no call ("and in Paris?"), retry it against the
 *     previous tool.
 *  9. Learn: facts told during the turn update the profile (structured extraction).
 */
class Agent(private val a: Activity, private val ui: Ui) {

    /** What the chat shows as the agent works. */
    interface Ui {
        fun tools(names: List<String>)
        fun call(text: String, confidence: Double?, ms: Long, reasoning: String, held: Boolean)
        fun result(r: Result)
        fun ask(question: String, yes: () -> Unit)
        fun none(text: String)
        fun info(text: String)
        fun done()
    }

    private var lastCall: JSONObject? = null
    @Volatile private var busy = false

    private class Turn(val query: String, val system: String, val tools: List<Tool>) {
        val seen = mutableSetOf<String>()
        var steps = 0
    }

    fun ask(query: String) {
        if (busy) return ui.info("Still working on the last message…")
        busy = true
        Router.route(query)?.let { return routed(query, it) }
        Brain.async({
            val tools = Registry.select(a, query)
            val profile = Memory.asFacts(a)
            val system = Settings.system(a) + if (profile.isNotBlank()) "; $profile" else ""
            Brain.begin(a, system, Registry.schemas(tools))
            var thought = Brain.step(a, query)
            var retried: String? = null
            // elimination: Needle is unsure, so ask again without the tool it picked and keep
            // whichever call is more confident
            val conf = thought.raw.optDouble("confidence", 1.0)
            val picked = (thought.firstCall ?: thought.raw.optJSONArray("suppressed_calls")?.optJSONObject(0))?.optString("name")
            if (picked != null && conf < Settings.confidence(a) && tools.size > 1) {
                val rest = tools.filter { it.name != picked }
                Brain.begin(a, system, Registry.schemas(rest))
                val second = Brain.step(a, query)
                val conf2 = second.raw.optDouble("confidence", 0.0)
                val got = second.firstCall ?: second.raw.optJSONArray("suppressed_calls")?.optJSONObject(0)
                if (got != null && conf2 > conf) {
                    thought = second
                    retried = "unsure about $picked (${"%.2f".format(conf)}), asked again without it"
                } else {
                    Brain.begin(a, system, Registry.schemas(tools)) // back to the first conversation
                    Brain.step(a, query)
                }
            }
            Turn(query, system, tools) to (thought to (tools to retried))
        }) { r ->
            val (turn, rest) = r.getOrElse { e ->
                ui.none("Error: ${e.message}")
                return@async finish()
            }
            val (thought, more) = rest
            val (tools, retried) = more
            ui.tools(tools.map { it.name })
            retried?.let { ui.info(it) }
            handle(turn, thought, followUpTried = false)
        }
    }

    /** A routed call (memory statements and questions): run it directly, no model needed. */
    private fun routed(query: String, call: JSONObject) {
        val tool = Registry.find(a, call.optString("name")) ?: return finish()
        ui.info("router: ${tool.name}")
        ui.call(format(call), null, 0, "", held = false)
        tool.run(a, call.optJSONObject("arguments") ?: JSONObject()) { r ->
            ui.result(r)
            finish()
        }
    }

    private fun handle(turn: Turn, t: Brain.Thought, followUpTried: Boolean) {
        val raw = t.raw
        if (!raw.optBoolean("success", true) && raw.has("error") && !raw.isNull("error")) {
            ui.none("Error: ${raw.optString("error")}")
            return finish()
        }
        val conf = raw.optDouble("confidence", Double.NaN).takeIf { !it.isNaN() }
        val reasoning = raw.optString("reasoning").takeIf { it.isNotBlank() && it != "null" } ?: ""
        val call = t.firstCall
        val held = raw.optJSONArray("suppressed_calls")?.optJSONObject(0)

        if (call == null && held == null) {
            if (turn.steps > 0) return finish() // Needle is done after a result
            val prev = lastCall
            if (!followUpTried && prev != null && turn.query.split(" ").size <= 7) {
                // a short follow-up like "and in Paris?": try it against the previous tool
                val tool = Registry.find(a, prev.optString("name"))
                if (tool != null) {
                    Brain.async({
                        Brain.begin(a, turn.system + "; previous: " + prev.toString(), Registry.schemas(listOf(tool)))
                        Brain.step(a, turn.query)
                    }) { r2 -> r2.onSuccess { handle(turn, it, followUpTried = true) }.onFailure { ui.none("No tool fits this message."); finish() } }
                    return
                }
            }
            ui.none("No tool fits this message." + if (reasoning.isNotBlank()) "\n$reasoning" else "")
            return finish()
        }

        val c = call ?: held!!
        ui.call(format(c), conf, t.ms, reasoning, held = call == null)
        val tool = Registry.find(a, c.optString("name"))
        if (tool == null) {
            ui.none("Needle picked an unknown tool.")
            return finish()
        }
        val args = fill(tool, c.optJSONObject("arguments") ?: JSONObject())
        val threshold = Settings.confidence(a)
        val unsure = call == null || (conf != null && conf < threshold)
        if (unsure || tool.confirm) {
            // ask first; the loop ends here and the call runs on a yes
            ui.ask(if (tool.confirm && !unsure) "Run ${tool.name}?" else "Not sure — run it anyway?") {
                tool.run(a, args) { r -> ui.result(r); if (r.ok) lastCall = JSONObject(c.toString()).put("arguments", args) }
            }
            return finish()
        }
        execute(turn, tool, c, args)
    }

    /** Fills missing required arguments from the profile. */
    private fun fill(tool: Tool, args: JSONObject): JSONObject {
        for (req in tool.required) {
            if (args.optString(req).isBlank()) {
                Memory.fill(a, req)?.let { v ->
                    args.put(req, v)
                    ui.info("Filled $req from your profile: $v")
                }
            }
        }
        return args
    }

    private fun execute(turn: Turn, tool: Tool, call: JSONObject, args: JSONObject) {
        turn.steps++
        turn.seen.add(tool.name + args.toString())
        tool.run(a, args) { r ->
            ui.result(r)
            if (r.ok) lastCall = JSONObject(call.toString()).put("arguments", args)
            if (!r.ok || turn.steps >= Settings.maxSteps(a)) return@run finish()
            // feed the result back: Needle may follow up with another call, or finish
            Brain.async({ Brain.step(a, r.forNeedle(tool.name)) }) { next ->
                val t2 = next.getOrNull() ?: return@async finish()
                val c2 = t2.firstCall
                if (t2.raw.optString("type") == "respond" || c2 == null) return@async finish()
                val tool2 = Registry.find(a, c2.optString("name")) ?: return@async finish()
                val args2 = c2.optJSONObject("arguments") ?: JSONObject()
                if (!turn.seen.add(tool2.name + args2.toString())) return@async finish() // no repeats
                turn.seen.remove(tool2.name + args2.toString())
                handle(turn, t2, followUpTried = true)
            }
        }
    }

    private fun finish() {
        Brain.async({
            Brain.end()
            Memory.digest(a)
        }) { r ->
            r.getOrNull()?.takeIf { it.isNotBlank() }?.let { ui.info("Learned about you: $it") }
            busy = false
            ui.done()
        }
    }

    companion object {
        /** set_timer {"minutes":5} -> set_timer(minutes: 5) */
        fun format(call: JSONObject): String {
            val args = call.optJSONObject("arguments") ?: JSONObject()
            val parts = args.keys().asSequence().map { k ->
                val v = args.get(k)
                "$k: " + if (v is String) "\"$v\"" else v.toString()
            }.toList()
            return call.optString("name") + "(" + parts.joinToString(", ") + ")"
        }
    }
}

package com.monolith.poke.agent

import android.app.Activity
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Skills, the Needle way. A Hermes skill is a markdown procedure the LLM reads and follows;
 * Needle can't follow prose, so a skill is a macro: a named tool (Needle sees it like any other
 * tool and picks it by its description) whose steps are calls to other tools, with {slot}s
 * filled from the skill's own arguments.
 *
 * Stored as JSON (editable in settings), e.g.
 *   {"name": "good_night", "description": "Get ready for bed",
 *    "slots": {"minutes": "how long to sleep"},
 *    "steps": [{"tool": "set_timer", "args": {"minutes": "{minutes}"}},
 *              {"tool": "create_note", "args": {"text": "went to bed"}}]}
 * Each skill keeps a count of runs and failures, shown in settings.
 */
object Skills {
    private fun p(c: Context) = c.getSharedPreferences("skills", Context.MODE_PRIVATE)

    val EXAMPLE = """
[
  {"name": "morning_brief", "description": "Morning briefing: weather and a note to start the day",
   "slots": {"location": "city for the weather"},
   "steps": [{"tool": "get_weather", "args": {"location": "{location}"}},
             {"tool": "create_note", "args": {"text": "Morning! Plan the day."}}]}
]
""".trim()

    fun json(c: Context): String = p(c).getString("skills", EXAMPLE) ?: EXAMPLE

    fun save(c: Context, json: String): String? = try {
        val a = JSONArray(json)
        for (i in 0 until a.length()) {
            val s = a.getJSONObject(i)
            require(s.optString("name").matches(Regex("[a-z][a-z0-9_]*"))) { "skill ${i + 1}: name must be lower_snake_case" }
            require((s.optJSONArray("steps")?.length() ?: 0) > 0) { "skill “${s.optString("name")}” has no steps" }
        }
        p(c).edit().putString("skills", a.toString(2)).apply()
        null
    } catch (e: Exception) {
        e.message ?: "not valid JSON"
    }

    fun reset(c: Context) = p(c).edit().remove("skills").apply()

    fun all(c: Context): List<SkillTool> = try {
        val a = JSONArray(json(c))
        (0 until a.length()).map { SkillTool(a.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }

    fun stats(c: Context, name: String): Pair<Int, Int> = p(c).getInt("runs_$name", 0) to p(c).getInt("fails_$name", 0)

    fun count(c: Context, name: String, ok: Boolean) {
        val e = p(c).edit().putInt("runs_$name", p(c).getInt("runs_$name", 0) + 1)
        if (!ok) e.putInt("fails_$name", p(c).getInt("fails_$name", 0) + 1)
        e.apply()
    }
}

/** A skill as a tool: its slots are its arguments; running it runs its steps in order. */
class SkillTool(val spec: JSONObject) : Tool(
    spec.optString("name"),
    spec.optString("description"),
    JSONObject().also { props ->
        spec.optJSONObject("slots")?.let { slots ->
            slots.keys().forEach { k -> props.put(k, JSONObject().put("type", "string").put("description", slots.optString(k))) }
        }
    },
) {
    override val kind = "skill"

    override fun run(a: Activity, args: JSONObject, done: (Result) -> Unit) {
        val steps = spec.optJSONArray("steps") ?: JSONArray()
        val lines = mutableListOf<String>()
        var allOk = true
        fun next(i: Int) {
            if (i >= steps.length()) {
                Skills.count(a, name, allOk)
                done(Result(allOk, "Skill $name: " + lines.joinToString(" · ")))
                return
            }
            val step = steps.getJSONObject(i)
            val tool = Registry.find(a, step.optString("tool"))
            if (tool == null || tool is SkillTool) {
                lines.add("✗ no tool “${step.optString("tool")}”")
                allOk = false
                return next(i + 1)
            }
            val filled = fillSlots(a, step.optJSONObject("args") ?: JSONObject(), args)
            tool.run(a, filled) { r ->
                lines.add((if (r.ok) "✓ " else "✗ ") + r.say)
                if (!r.ok) allOk = false
                next(i + 1)
            }
        }
        next(0)
    }

    /** "{location}" -> the skill's argument, else the profile; numbers stay numbers. */
    private fun fillSlots(c: Context, template: JSONObject, args: JSONObject): JSONObject {
        val out = JSONObject()
        template.keys().forEach { k ->
            val v = template.get(k)
            if (v is String) {
                val filled = Regex("\\{(\\w+)\\}").replace(v) { m ->
                    args.optString(m.groupValues[1]).ifBlank { Memory.fill(c, m.groupValues[1]) ?: "" }
                }
                out.put(k, filled.toIntOrNull() ?: filled)
            } else {
                out.put(k, v)
            }
        }
        return out
    }
}

package com.monolith.poke

import android.content.Context
import org.json.JSONArray

/** User settings: the tools Needle chooses between, extra facts for it, and voice input. */
object Settings {
    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * Needle 3 answers every message with a tool call chosen from these (or none). They are
     * examples; edit them in settings. Needle works best with five tools or fewer, plain
     * names and no shared prefixes.
     */
    val DEFAULT_TOOLS = """
[
  {"name": "get_weather", "description": "Get the weather for a place",
   "parameters": {"type": "object", "properties": {"location": {"type": "string"}}, "required": ["location"]}},
  {"name": "set_timer", "description": "Start a countdown timer",
   "parameters": {"type": "object", "properties": {"minutes": {"type": "integer"}, "seconds": {"type": "integer"}}, "required": []}},
  {"name": "send_message", "description": "Send a text message to a contact",
   "parameters": {"type": "object", "properties": {"to": {"type": "string"}, "text": {"type": "string"}}, "required": ["to", "text"]}},
  {"name": "create_note", "description": "Save a note",
   "parameters": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}},
  {"name": "search_web", "description": "Search the web",
   "parameters": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}}
]
""".trim()

    fun tools(c: Context): String = p(c).getString("tools", DEFAULT_TOOLS) ?: DEFAULT_TOOLS

    /** Saves the tools if they are a valid JSON array; returns an error message otherwise. */
    fun setTools(c: Context, json: String): String? = try {
        val a = JSONArray(json)
        for (i in 0 until a.length()) {
            val t = a.getJSONObject(i)
            require(t.optString("name").isNotBlank()) { "tool ${i + 1} has no name" }
        }
        p(c).edit().putString("tools", a.toString(2)).apply()
        null
    } catch (e: Exception) {
        e.message ?: "not valid JSON"
    }

    fun resetTools(c: Context) = p(c).edit().remove("tools").apply()

    /** Extra environment facts for Needle, e.g. "locale: en-US; user: Sam" (the date is added automatically). */
    fun facts(c: Context): String = p(c).getString("facts", "") ?: ""
    fun setFacts(c: Context, v: String) = p(c).edit().putString("facts", v.trim()).apply()

    /** Send what Whistle heard straight away (otherwise it goes into the text box to edit). */
    fun autoSend(c: Context): Boolean = p(c).getBoolean("auto_send", true)
    fun setAutoSend(c: Context, on: Boolean) = p(c).edit().putBoolean("auto_send", on).apply()

    /** Seconds of silence that end one spoken message. */
    fun pauseSeconds(c: Context): Float = p(c).getFloat("pause", 1f)
    fun setPauseSeconds(c: Context, s: Float) = p(c).edit().putFloat("pause", s.coerceIn(0.5f, 3f)).apply()

    fun system(c: Context): String {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd EEE HH:mm", java.util.Locale.US).format(java.util.Date())
        val extra = facts(c)
        return "date: $date" + if (extra.isNotBlank()) "; $extra" else ""
    }
}

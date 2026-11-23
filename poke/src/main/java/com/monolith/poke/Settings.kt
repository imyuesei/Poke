package com.monolith.poke

import android.content.Context
import org.json.JSONArray

/** User settings: custom tools, facts for Needle, agent limits, and voice input. */
object Settings {
    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * Extra tools you define (JSON, same shape as the built-ins). Needle can call them; they
     * show the call but aren't connected to an action.
     */
    val DEFAULT_TOOLS = "[]"

    fun tools(c: Context): String {
        val v = p(c).getString("tools", DEFAULT_TOOLS) ?: DEFAULT_TOOLS
        // older versions stored copies of the built-in tools here; those now live in code
        return if (v.contains("\"get_weather\"") && v.contains("\"search_web\"")) DEFAULT_TOOLS else v
    }

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

    /** Below this confidence the agent asks before running a call. */
    fun confidence(c: Context): Float = p(c).getFloat("confidence", 0.5f)
    fun setConfidence(c: Context, v: Float) = p(c).edit().putFloat("confidence", v.coerceIn(0f, 0.95f)).apply()

    /** How many tool calls one message may lead to (Needle reads each result and may follow up). */
    fun maxSteps(c: Context): Int = p(c).getInt("max_steps", 3)
    fun setMaxSteps(c: Context, v: Int) = p(c).edit().putInt("max_steps", v.coerceIn(1, 6)).apply()

    /** Show the agent's working (which tools it considered, profile fills) in the chat. */
    fun showTrace(c: Context): Boolean = p(c).getBoolean("trace", true)
    fun setShowTrace(c: Context, on: Boolean) = p(c).edit().putBoolean("trace", on).apply()

    fun system(c: Context): String {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd EEE HH:mm", java.util.Locale.US).format(java.util.Date())
        val extra = facts(c)
        return "date: $date" + if (extra.isNotBlank()) "; $extra" else ""
    }
}

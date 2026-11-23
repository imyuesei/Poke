package com.monolith.poke.agent

import android.content.Context
import com.monolith.poke.Settings
import com.monolith.poke.brain.Brain
import org.json.JSONArray
import java.util.Locale

/**
 * All tools: built-ins, skills and custom tools from settings. Needle 3 has no tool-retrieval
 * head and reads every tool it's given, so a large list would confuse it (it works best with
 * five or fewer). The registry picks the few that fit a message, by meaning: Needle 3
 * embeddings of each tool's "card" against the message, plus a nudge for tools named in it.
 */
object Registry {
    private val BUILT_IN: List<Tool> = listOf(SetTimer, GetWeather, SearchWeb, SendMessage, CreateNote, Remember, Recall)
    private val cache = HashMap<String, FloatArray>()

    fun all(c: Context): List<Tool> {
        val custom = try {
            val a = JSONArray(Settings.tools(c))
            (0 until a.length()).map { CustomTool(a.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
        val skills = Skills.all(c)
        val taken = mutableSetOf<String>()
        // built-ins win over a custom tool or skill with the same name
        return (BUILT_IN + skills + custom).filter { it.name.isNotBlank() && taken.add(it.name) }
    }

    fun find(c: Context, name: String): Tool? = all(c).firstOrNull { it.name == name }

    /** The [k] tools that fit [query] best. Brain thread (uses embeddings). */
    fun select(c: Context, query: String, k: Int = 4): List<Tool> {
        val tools = all(c)
        if (tools.size <= k) return tools
        val q = runCatching { Brain.embed(c, query) }.getOrNull()
        val words = query.lowercase(Locale.US).split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
        return tools.sortedByDescending { t ->
            val e = cache[t.card()] ?: runCatching { Brain.embed(c, t.card()) }.getOrNull()?.also { cache[t.card()] = it }
            val sim = if (q != null && e != null) Brain.cosine(q, e) else 0f
            val named = t.card().lowercase(Locale.US).split(Regex("[^a-z0-9]+")).count { it in words } * 0.08f
            sim + named
        }.take(k)
    }

    fun schemas(tools: List<Tool>): String = JSONArray().also { a -> tools.forEach { a.put(it.schema()) } }.toString()
}

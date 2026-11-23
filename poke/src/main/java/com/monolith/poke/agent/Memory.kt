package com.monolith.poke.agent

import android.content.Context
import android.util.Base64
import com.monolith.poke.brain.Brain
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Memory, the Needle way (Hermes keeps MEMORY.md and USER.md that the LLM reads; Needle can't
 * read long notes, so memory is structured):
 *  - facts: what you told it, each with a Needle 3 embedding, found again by meaning;
 *  - profile: a few fields about you (name, city, work, …) pulled out of facts with Needle's
 *    structured extraction. The profile goes into every request as facts ("user: Sam;
 *    location: Berlin") and fills missing arguments (weather with no city uses yours).
 */
object Memory {
    private fun p(c: Context) = c.getSharedPreferences("memory", Context.MODE_PRIVATE)

    /** Profile fields Needle extracts; the key names double as Needle's fact keys. */
    private val PROFILE = JSONArray().put(JSONObject()
        .put("name", "user_profile")
        .put("description", "Details the user states about themselves")
        .put("parameters", JSONObject().put("type", "object").put("required", JSONArray()).put("properties", JSONObject()
            .put("name", JSONObject().put("type", "string").put("description", "the user's own name"))
            .put("location", JSONObject().put("type", "string").put("description", "the city where the user lives"))
            .put("work", JSONObject().put("type", "string").put("description", "where or as what the user works"))
            .put("language", JSONObject().put("type", "string").put("description", "language the user speaks"))
            .put("birthday", JSONObject().put("type", "string").put("description", "the user's birthday"))))).toString()

    class Fact(val text: String, val at: Long, val emb: FloatArray?)

    fun facts(c: Context): List<Fact> = try {
        val a = JSONArray(p(c).getString("facts", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Fact(o.getString("text"), o.getLong("at"), o.optString("emb").takeIf { it.isNotBlank() }?.let { unpack(it) })
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun profile(c: Context): JSONObject = try { JSONObject(p(c).getString("profile", "{}")) } catch (_: Exception) { JSONObject() }

    private val queued = mutableListOf<String>()

    /** Saves a fact; its profile details are pulled out when the turn ends ([digest]). Brain thread. */
    fun remember(c: Context, fact: String) {
        val emb = runCatching { Brain.embed(c, fact) }.getOrNull()
        val a = JSONArray(p(c).getString("facts", "[]"))
        // the same fact again replaces the old one
        val keep = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val e = o.optString("emb").takeIf { it.isNotBlank() }?.let { unpack(it) }
            val same = o.getString("text").equals(fact, true) || (emb != null && e != null && Brain.cosine(emb, e) > 0.95f)
            if (!same) keep.put(o)
        }
        keep.put(JSONObject().put("text", fact).put("at", System.currentTimeMillis()).put("emb", emb?.let { pack(it) } ?: ""))
        p(c).edit().putString("facts", keep.toString()).apply()
        synchronized(queued) { queued.add(fact) }
    }

    /**
     * Runs after the agent's turn (extraction needs its own Needle session): updates the
     * profile from facts told during the turn. Returns what it learned. Brain thread.
     */
    fun digest(c: Context): String {
        val facts = synchronized(queued) { queued.toList().also { queued.clear() } }
        return facts.map { extractProfile(c, it) }.filter { it.isNotBlank() }.joinToString(", ")
    }

    /** Needle's structured extraction: only fields actually stated (grounded) are kept. */
    private fun extractProfile(c: Context, fact: String): String {
        val t = runCatching {
            Brain.begin(c, "", PROFILE)
            val r = Brain.step(c, fact)
            Brain.end()
            r
        }.getOrNull() ?: return ""
        val call = t.firstCall ?: return ""
        val args = call.optJSONObject("arguments") ?: return ""
        val prof = profile(c)
        val learned = mutableListOf<String>()
        args.keys().forEach { k ->
            val v = args.optString(k).trim()
            if (v.isNotBlank() && fact.lowercase(Locale.US).contains(v.lowercase(Locale.US).split(" ").first())) {
                prof.put(k, v)
                learned.add("$k: $v")
            }
        }
        if (learned.isNotEmpty()) p(c).edit().putString("profile", prof.toString()).apply()
        return learned.joinToString(", ")
    }

    /** The fact closest in meaning to [query], or null. Brain thread. */
    fun recall(c: Context, query: String): String? {
        val all = facts(c)
        if (all.isEmpty()) return null
        val q = words(query)
        all.maxByOrNull { f -> words(f.text).count { it in q } }?.let { f ->
            if (q.isNotEmpty() && words(f.text).count { it in q }.toFloat() / q.size >= 0.5f) return f.text
        }
        val qe = runCatching { Brain.embed(c, query) }.getOrNull() ?: return null
        return all.filter { it.emb != null }.maxByOrNull { Brain.cosine(qe, it.emb!!) }
            ?.takeIf { Brain.cosine(qe, it.emb!!) > 0.6f }?.text
    }

    private val STOP = setOf("what", "whats", "is", "are", "my", "the", "a", "an", "do", "you", "remember", "i", "me", "of", "to", "did", "tell")
    private fun words(s: String) = s.lowercase(Locale.US).replace("'s", "").split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in STOP }.toSet()

    /** The profile as Needle facts: "user: Sam; location: Berlin". */
    fun asFacts(c: Context): String {
        val p = profile(c)
        return p.keys().asSequence().map { k -> (if (k == "name") "user" else k) + ": " + p.optString(k) }.joinToString("; ")
    }

    /** A value for a missing argument, from the profile (location, city, place, …). */
    fun fill(c: Context, arg: String): String? {
        val p = profile(c)
        val a = arg.lowercase(Locale.US)
        return when {
            a in setOf("location", "city", "place", "where") -> p.optString("location").takeIf { it.isNotBlank() }
            a in setOf("name", "user", "from") -> p.optString("name").takeIf { it.isNotBlank() }
            else -> p.optString(a).takeIf { it.isNotBlank() }
        }
    }

    fun forget(c: Context) = p(c).edit().clear().apply()

    private fun pack(v: FloatArray): String {
        val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { b.putFloat(it) }
        return Base64.encodeToString(b.array(), Base64.NO_WRAP)
    }

    private fun unpack(s: String): FloatArray? = try {
        val bytes = Base64.decode(s, Base64.NO_WRAP)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        FloatArray(bytes.size / 4) { b.getFloat() }
    } catch (_: Exception) {
        null
    }
}

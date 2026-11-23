package com.monolith.poke.brain

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.monolith.poke.Mind
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * What Poke can do with what it understands (stage 0 toolset), its small memory of places
 * and facts, and how it answers: caveman talk built from templates. Needle 3 only ever
 * returns a structured tool call, never free text, so the words come from here, picked by
 * mood.
 */
object Skills {
    /** Five tools or fewer, plain names, no shared prefixes (as Needle 3 prefers). */
    val TOOLS: String = JSONArray()
        .put(tool("store_place", "Remember where something was parked or left",
            JSONObject()
                .put("item", prop("string", "the thing, like car, bike, keys or wallet"))
                .put("place", prop("string", "where it is, in the user's words")),
            listOf("item", "place")))
        .put(tool("where_is", "Say where something was parked or left",
            JSONObject().put("item", prop("string", "the thing being looked for")),
            listOf("item")))
        .put(tool("learn_fact", "Learn a fact the user teaches about themselves or their world",
            JSONObject()
                .put("subject", prop("string", "who or what the fact is about"))
                .put("fact", prop("string", "the fact, in the user's words")),
            listOf("subject", "fact")))
        .put(tool("react", "React to how the user is treating Poke",
            JSONObject().put("feeling", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("praise", "scold", "greeting", "goodnight", "love", "play")))),
            listOf("feeling")))
        .put(tool("set_timer", "Start a countdown timer",
            JSONObject().put("minutes", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 600)),
            listOf("minutes")))
        .toString()

    fun system(): String {
        val date = SimpleDateFormat("yyyy-MM-dd EEE HH:mm", Locale.US).format(Date())
        return "date: $date; device: phone; assistant: Poke, a small pet that lives on the phone"
    }

    private fun tool(name: String, desc: String, props: JSONObject, required: List<String>) = JSONObject()
        .put("name", name)
        .put("description", desc)
        .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)))

    private fun prop(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)

    // ------------------------------------------------------------------ acting

    class Reply(val words: String, val detail: String)

    /** Mind lives on the main thread; the brain thread hands it events. */
    private fun feel(e: Mind.Event) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { Mind.onEvent(e) }
    }

    /** Carries out a Needle result and returns what Poke says back. Runs on the brain thread. */
    fun act(c: Context, thought: Brain.Thought): Reply {
        val call = thought.firstCall
        val conf = thought.confidence
        if (call == null || (!conf.isNaN() && conf < 0.35)) {
            feel(Mind.Event.CONFUSED)
            return Reply(Caveman.confused(), "no confident call")
        }
        val args = call.optJSONObject("arguments") ?: JSONObject()
        return when (call.optString("name")) {
            "store_place" -> {
                val item = args.optString("item").ifBlank { "thing" }
                val place = args.optString("place")
                Memory.storePlace(c, item, place)
                feel(Mind.Event.TAUGHT)
                Reply(Caveman.stored(item, place), "remembered $item → $place")
            }
            "where_is" -> {
                val item = args.optString("item").ifBlank { "thing" }
                val found = Memory.findPlace(c, item)
                if (found != null) {
                    feel(Mind.Event.TAUGHT)
                    Reply(Caveman.recalled(found.first, found.second), "recalled ${found.first} → ${found.second}")
                } else {
                    feel(Mind.Event.CONFUSED)
                    Reply(Caveman.notFound(item), "nothing stored for $item")
                }
            }
            "learn_fact" -> {
                val subject = args.optString("subject")
                val fact = args.optString("fact")
                Memory.learnFact(c, subject, fact)
                feel(Mind.Event.TAUGHT)
                Reply(Caveman.learned(subject, fact), "learned: $subject — $fact")
            }
            "react" -> {
                val f = args.optString("feeling")
                feel(
                    when (f) {
                        "praise" -> Mind.Event.PRAISE
                        "scold" -> Mind.Event.SCOLD
                        "greeting" -> Mind.Event.GREETING
                        "goodnight" -> Mind.Event.GOODNIGHT
                        "love" -> Mind.Event.LOVE
                        else -> Mind.Event.PLAY
                    },
                )
                Reply(Caveman.feeling(f), "feeling: $f")
            }
            "set_timer" -> {
                val m = args.optInt("minutes", 0)
                if (m > 0) {
                    try {
                        c.startActivity(
                            Intent(AlarmClock.ACTION_SET_TIMER)
                                .putExtra(AlarmClock.EXTRA_LENGTH, m * 60)
                                .putExtra(AlarmClock.EXTRA_MESSAGE, "Poke timer")
                                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (_: Exception) {
                    }
                }
                Reply(Caveman.timer(m), "timer: $m min")
            }
            else -> {
                feel(Mind.Event.CONFUSED)
                Reply(Caveman.confused(), "unknown tool")
            }
        }
    }
}

/** Poke's first memories: where things are, and facts you teach it (stage 3 grows this). */
object Memory {
    private fun prefs(c: Context) = c.getSharedPreferences("poke_memory", Context.MODE_PRIVATE)

    private fun norm(s: String) = s.lowercase(Locale.US)
        .replace(Regex("\\b(my|the|a|an|our|your)\\b"), " ")
        .replace(Regex("\\s+"), " ").trim()

    fun storePlace(c: Context, item: String, place: String) {
        val o = JSONObject(prefs(c).getString("places", "{}") ?: "{}")
        o.put(norm(item), JSONObject().put("item", item).put("place", place).put("at", System.currentTimeMillis()))
        prefs(c).edit().putString("places", o.toString()).apply()
    }

    /** Exact match first, then a word match, then the closest by meaning (Needle embeddings). */
    fun findPlace(c: Context, item: String): Pair<String, String>? {
        val o = JSONObject(prefs(c).getString("places", "{}") ?: "{}")
        val key = norm(item)
        o.optJSONObject(key)?.let { return it.optString("item") to it.optString("place") }
        val keys = o.keys().asSequence().toList()
        keys.firstOrNull { k -> k.split(" ").any { w -> w.length > 2 && key.contains(w) } }?.let {
            val e = o.getJSONObject(it)
            return e.optString("item") to e.optString("place")
        }
        val q = runCatching { Brain.embed(c, key) }.getOrNull() ?: return null
        var best: String? = null
        var bestScore = 0.6f
        for (k in keys) {
            val v = runCatching { Brain.embed(c, k) }.getOrNull() ?: continue
            val s = Brain.cosine(q, v)
            if (s > bestScore) {
                bestScore = s
                best = k
            }
        }
        return best?.let { val e = o.getJSONObject(it); e.optString("item") to e.optString("place") }
    }

    fun learnFact(c: Context, subject: String, fact: String) {
        val a = JSONArray(prefs(c).getString("facts", "[]") ?: "[]")
        a.put(JSONObject().put("subject", subject).put("fact", fact).put("at", System.currentTimeMillis()))
        prefs(c).edit().putString("facts", a.toString()).apply()
    }

    fun summary(c: Context): String {
        val places = JSONObject(prefs(c).getString("places", "{}") ?: "{}")
        val facts = JSONArray(prefs(c).getString("facts", "[]") ?: "[]")
        val sb = StringBuilder()
        places.keys().forEach { k ->
            val e = places.getJSONObject(k)
            sb.append("• ").append(e.optString("item")).append(" → ").append(e.optString("place")).append('\n')
        }
        for (i in 0 until facts.length()) {
            val f = facts.getJSONObject(i)
            sb.append("• ").append(f.optString("subject")).append(": ").append(f.optString("fact")).append('\n')
        }
        return sb.toString().ifBlank { "(nothing yet)" }
    }
}

/**
 * Caveman talk: short, no articles, Poke talks about itself in the third person, and the
 * wording follows its mood (happy and bouncy, flat, grumpy, or sleepy).
 */
object Caveman {
    private fun pick(vararg options: String) = options[Random.nextInt(options.size)]

    private enum class Tone { HAPPY, PLAIN, GRUMPY, SLEEPY }

    private fun tone(): Tone = when {
        Mind.asleep || Mind.energy < 0.25f -> Tone.SLEEPY
        Mind.valence < -0.2f || Mind.traits[Mind.GRUMPY] > 0.4f && Random.nextFloat() < 0.5f -> Tone.GRUMPY
        Mind.valence > 0.3f -> Tone.HAPPY
        else -> Tone.PLAIN
    }

    /** "my car" -> "Car"; "on level 3 near the blue pillar" -> "level 3, near blue pillar". */
    fun thing(s: String): String {
        val t = s.trim().replace(Regex("(?i)\\b(my|the|a|an|our)\\b"), "").replace(Regex("\\s+"), " ").trim()
        return t.replaceFirstChar { it.uppercase() }.ifBlank { "Thing" }
    }

    fun place(s: String): String = s.trim()
        .replace(Regex("(?i)^(it'?s |it is |is )"), "")
        .replace(Regex("(?i)\\b(the|a|an|my)\\b"), "")
        .replace(Regex("(?i)^(on|in|at)\\s+"), "")
        .replace(Regex("(?i)\\s+(near|next to|by|beside)\\s+"), ", $1 ")
        .replace(Regex("\\s+"), " ").trim().trimEnd('.')

    fun stored(item: String, where: String): String {
        val i = thing(item)
        val p = place(where)
        return when (tone()) {
            Tone.HAPPY -> pick("Me remember! $i: $p.", "Ooh! $i... $p. Me keep in head!")
            Tone.GRUMPY -> pick("Hmph. $i. $p. Me remember.", "Fine. $i, $p. Me keep.")
            Tone.SLEEPY -> pick("Mmm... $i... $p... Me keep.", "$i... $p... zzz... Me remember.")
            Tone.PLAIN -> pick("$i: $p. Me remember.", "Me keep. $i... $p.")
        }
    }

    fun recalled(item: String, where: String): String {
        val i = thing(item)
        val p = place(where)
        return when (tone()) {
            Tone.HAPPY -> pick("Me know! $i — $p!", "$i? $p! Me good memory!")
            Tone.GRUMPY -> pick("Hmph. $i. $p.", "Again? $i... $p.")
            Tone.SLEEPY -> pick("Mmm... $i... $p...", "$i... $p... zzz.")
            Tone.PLAIN -> pick("$i... $p.", "$i there. $p.")
        }
    }

    fun notFound(item: String): String {
        val i = thing(item)
        return when (tone()) {
            Tone.GRUMPY -> pick("$i? Me no know. You no tell me.", "Hmph. No $i in head.")
            else -> pick("$i? Me no know...", "Me not see $i. You tell me?")
        }
    }

    fun learned(subject: String, fact: String): String {
        val s = thing(subject)
        val f = place(fact)
        return when (tone()) {
            Tone.HAPPY -> pick("Me learn! $s... $f!", "Ooh. $s: $f. Me smart now!")
            Tone.GRUMPY -> pick("Hmph. $s, $f. Okay.", "Me learn. $s... $f.")
            else -> pick("$s... $f. Me learn.", "Me keep. $s: $f.")
        }
    }

    fun feeling(f: String): String = when (f) {
        "praise" -> pick("Me good! Hehe!", "Hehe. Me best.")
        "scold" -> if (tone() == Tone.GRUMPY) pick("Hmph.", "Me no like.") else pick("Me sorry...", "Po... sorry.")
        "greeting" -> pick("Hi hi!", "Hi! Me here!")
        "goodnight" -> pick("Night night. Me sleep too.", "Mmm. Sleep. Me dream.")
        "love" -> pick("Me love too...", "Me happy. Me stay.")
        else -> pick("Play! Me play!", "Again! Again!")
    }

    fun timer(m: Int): String = if (m <= 0) confused() else pick("Me count! $m minute.", "Okay okay. $m minute. Me watch.")

    fun confused(): String = pick("Hmm?", "Huh? Me no understand.", "Uhh… what?")
}

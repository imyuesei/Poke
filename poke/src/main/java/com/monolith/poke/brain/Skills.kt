package com.monolith.poke.brain

import android.content.Context
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
        // "item" is optional: "I parked on level 3" never names the car, and a required slot
        // the words don't contain makes Needle hold the whole call back.
        .put(tool("store_place", "Remember where something was parked or left",
            JSONObject()
                .put("item", prop("string", "the thing, like car, bike, keys or wallet; car if parked"))
                .put("place", prop("string", "where it is, in the user's words")),
            listOf("place")))
        .put(tool("where_is", "Say where something was parked or left",
            JSONObject().put("item", prop("string", "the thing being looked for; car if parked")),
            listOf()))
        .put(tool("learn_fact", "Learn a fact the user teaches about themselves or their world",
            JSONObject()
                .put("subject", prop("string", "who or what the fact is about"))
                .put("fact", prop("string", "the fact, in the user's words")),
            listOf("fact")))
        .put(tool("react", "React to how the user is treating Poke",
            JSONObject().put("feeling", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("praise", "scold", "greeting", "goodnight", "love", "play")))),
            listOf("feeling")))
        .put(tool("set_timer", "Start a countdown timer",
            JSONObject()
                .put("minutes", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 600))
                .put("seconds", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 3600)),
            listOf()))
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

    /**
     * Carries out what Poke understood from [text] and returns what it says back.
     * Runs on the brain thread. Needle's call is used when it made one (including a call it
     * held back but got right); when it made none, simple rules catch the everyday phrasings
     * (parking, "where is", timers).
     */
    fun act(c: Context, thought: Brain.Thought, text: String): Reply {
        var call = thought.firstCall
        var how = "needle"
        val conf = thought.confidence
        if (call == null) {
            val held = thought.raw.optJSONArray("suppressed_calls")
            if (held != null && held.length() > 0) {
                call = held.getJSONObject(0)
                how = "needle (held back)"
            }
        } else if (!conf.isNaN() && conf < 0.15) {
            how = "needle (unsure)"
        }
        val rule = Rules.parse(text)
        if (call == null || (how != "needle" && rule != null)) {
            if (rule != null) {
                call = rule
                how = "rules"
            }
        }
        if (call == null) {
            feel(Mind.Event.CONFUSED)
            return Reply(Caveman.confused(), "no call")
        }
        val r = run(c, call, text)
        return Reply(r.words, "[$how] ${r.detail}")
    }

    private fun run(c: Context, call: JSONObject, text: String): Reply {
        val args = call.optJSONObject("arguments") ?: JSONObject()
        val parked = Regex("(?i)\\bpark").containsMatchIn(text)
        return when (call.optString("name")) {
            "store_place" -> {
                val item = args.optString("item").ifBlank { if (parked) "car" else "thing" }
                val place = args.optString("place").ifBlank { text }
                Memory.storePlace(c, item, place)
                feel(Mind.Event.TAUGHT)
                Reply(Caveman.stored(item, place), "remembered $item → $place")
            }
            "where_is" -> {
                val item = args.optString("item").ifBlank { if (parked) "car" else Memory.lastItem(c) ?: "thing" }
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
                val subject = args.optString("subject").ifBlank { "you" }
                val fact = args.optString("fact").ifBlank { text }
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
                var secs = args.optInt("minutes", 0) * 60 + args.optInt("seconds", 0)
                if (secs <= 0) secs = Rules.duration(text)
                if (secs <= 0) {
                    feel(Mind.Event.CONFUSED)
                    return Reply("How long? Me no hear time.", "timer without a length")
                }
                val label = com.monolith.poke.Timers.label(secs)
                android.os.Handler(android.os.Looper.getMainLooper()).post { com.monolith.poke.Timers.start(c, secs, label) }
                feel(Mind.Event.TAUGHT)
                Reply(Caveman.timer(label), "timer: $secs s")
            }
            else -> {
                feel(Mind.Event.CONFUSED)
                Reply(Caveman.confused(), "unknown tool")
            }
        }
    }
}

/** Plain rules for the everyday phrasings, used when Needle makes no call. */
object Rules {
    private val numbers = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30,
        "forty" to 40, "forty-five" to 45, "sixty" to 60, "half an" to 30,
    )

    /** Seconds named in [text] ("5 minutes", "a minute and 30 seconds", "half an hour"), or 0. */
    fun duration(text: String): Int {
        val t = text.lowercase(Locale.US)
        if (Regex("half an hour").containsMatchIn(t)) return 1800
        var total = 0
        for (m in Regex("(\\d+|[a-z-]+)\\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)\\b").findAll(t)) {
            val n = m.groupValues[1].toIntOrNull() ?: numbers[m.groupValues[1]] ?: continue
            val unit = m.groupValues[2]
            total += when {
                unit.startsWith("h") -> n * 3600
                unit.startsWith("m") -> n * 60
                else -> n
            }
        }
        return total
    }

    private fun call(name: String, args: JSONObject) = JSONObject().put("name", name).put("arguments", args)

    fun parse(text: String): JSONObject? {
        val t = text.trim().trimEnd('.', '!', '?')
        val low = t.lowercase(Locale.US)
        // timers
        if (Regex("\\b(timer|remind me in|countdown|count down)\\b").containsMatchIn(low)) {
            val s = duration(low)
            if (s > 0) return call("set_timer", JSONObject().put("seconds", s))
        }
        // questions: where is / where did I park
        Regex("\\bwhere(?:'s| is| are| did i (?:park|leave|put))\\s*(?:my|the)?\\s*([a-z ]*)").find(low)?.let { m ->
            val item = m.groupValues[1].replace(Regex("\\b(it|them|again|now)\\b"), "").trim()
            val args = JSONObject()
            if (item.isNotEmpty()) args.put("item", item) else if (low.contains("park")) args.put("item", "car")
            return call("where_is", args)
        }
        // "I parked on level 3 near the blue pillar"
        Regex("\\b(?:i\\s+)?parked\\s+(?:my\\s+(\\w+)\\s+)?(.+)").find(low)?.let { m ->
            val item = m.groupValues[1].ifBlank { "car" }
            return call("store_place", JSONObject().put("item", item).put("place", t.substring(m.groups[2]!!.range.first)))
        }
        // "my keys are in the kitchen drawer" / "I left my wallet on the desk"
        Regex("\\b(?:i\\s+(?:left|put)\\s+)?(?:my|the)\\s+([a-z]+(?:\\s[a-z]+)?)\\s+(?:is|are|on|in|at|under)?\\s*((?:in|on|at|under|behind|near|next to|inside)\\b.+)").find(low)?.let { m ->
            return call("store_place", JSONObject().put("item", m.groupValues[1]).put("place", t.substring(m.groups[2]!!.range.first)))
        }
        return null
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
        prefs(c).edit().putString("places", o.toString()).putString("last_item", item).commit()
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

    fun lastItem(c: Context): String? = prefs(c).getString("last_item", null)

    fun learnFact(c: Context, subject: String, fact: String) {
        val a = JSONArray(prefs(c).getString("facts", "[]") ?: "[]")
        a.put(JSONObject().put("subject", subject).put("fact", fact).put("at", System.currentTimeMillis()))
        prefs(c).edit().putString("facts", a.toString()).commit()
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

    fun timer(label: String): String = pick("Me count! $label.", "Okay okay. $label. Me watch.")

    fun confused(): String = pick("Hmm?", "Huh? Me no understand.", "Uhh… what?")
}

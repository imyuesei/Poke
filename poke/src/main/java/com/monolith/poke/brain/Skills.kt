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
 * What Poke does with what it hears, and how it answers (caveman talk from templates;
 * Needle 3 only ever returns a structured tool call, never free text).
 *
 * Understanding goes in three steps:
 *  1. Precise rules catch the everyday phrasings (questions about Poke, parking, "where is",
 *     timers, "my X is Y").
 *  2. Otherwise Needle 3 picks a tool.
 *  3. Guards check Needle's pick: a question is never stored as a memory (it is answered
 *     instead), and a memory must be in the user's own words.
 */
object Skills {
    /** Five tools, plain names, no shared prefixes (as Needle 3 prefers). */
    val TOOLS: String = JSONArray()
        .put(tool("remember", "Remember something the user tells Poke: where an item is, or a fact about them or their world",
            JSONObject()
                .put("item", prop("string", "the thing that was put or parked somewhere, like car, keys, wallet"))
                .put("place", prop("string", "where the item is, in the user's words"))
                .put("fact", prop("string", "a fact the user states, in their words")),
            listOf()))
        .put(tool("recall", "Answer a question about something the user told Poke before: where an item is, or a fact like a name",
            JSONObject().put("query", prop("string", "what the user wants to know")),
            listOf("query")))
        .put(tool("ask_poke", "Answer a question about Poke itself, like its name, age or how it feels",
            JSONObject().put("topic", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("name", "age", "feeling", "likes", "abilities")))),
            listOf("topic")))
        .put(tool("react", "React to how the user treats Poke: praise, scolding, greetings, goodnight, love or play",
            JSONObject().put("feeling", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("praise", "scold", "greeting", "goodnight", "love", "play")))),
            listOf("feeling")))
        .put(tool("phone_action", "Do something on the phone: timer, flashlight, do not disturb, open an app, music or volume, a reminder",
            JSONObject().put("request", prop("string", "what to do, in the user's words")),
            listOf("request")))
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

    class Reply(val words: String, val detail: String, val how: String = "", val call: String = "")

    /** Mind lives on the main thread; the brain thread hands it events. */
    private fun feel(e: Mind.Event) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { Mind.onEvent(e) }
    }

    /** Questions are answered, never stored. */
    fun isQuestion(text: String): Boolean {
        val t = text.trim().lowercase(Locale.US)
        return t.endsWith("?") || Regex("^(what|what's|whats|who|who's|where|where's|when|why|how|which|whose|" +
            "is|are|am|do|does|did|can|could|will|would|should|have|has|tell me)\\b").containsMatchIn(t)
    }

    /** True when most of [value]'s words appear in [text] (the user really said it). */
    private fun grounded(value: String, text: String): Boolean {
        val words = value.lowercase(Locale.US).split(Regex("[^a-z0-9]+")).filter { it.length > 1 }
        if (words.isEmpty()) return false
        val t = text.lowercase(Locale.US)
        return words.count { t.contains(it) }.toFloat() / words.size >= 0.6f
    }

    class Handled(val reply: Reply, val needleMs: Long?)

    /**
     * Understands [text] and acts. Brain thread. In order:
     *  teaching a trick -> a trick it knows -> precise rules -> a single phone action -> Needle.
     */
    fun handle(c: Context, text: String): Handled {
        val h = handleInner(c, text)
        Opinions.onHeard(c, text)
        return Handled(Reply(Caveman.flavor(h.reply.words), h.reply.detail, h.reply.how, h.reply.call), h.needleMs)
    }

    private fun handleInner(c: Context, text: String): Handled {
        Tricks.parseTeaching(text)?.let { (kind, trig, steps) ->
            val words = Tricks.teach(c, kind, trig, steps)
            return Handled(Reply(words, "trick: $kind \"$trig\" → $steps", "teaching"), null)
        }
        Tricks.match(c, text)?.let { t ->
            val words = Tricks.perform(c, t)
            feel(Mind.Event.PLAY)
            return Handled(Reply(words, "did trick \"${t.trig}\"", "trick"), null)
        }
        if (Rules.parse(text) != null) return Handled(act(c, null, text), null)
        Actions.parse(text)?.let { call ->
            val words = Actions.run(c, call)
            feel(Mind.Event.TAUGHT)
            return Handled(Reply(Caveman.didAction(words), "action", "rules: action", call.toString()), null)
        }
        val thought = Brain.think(c, text, TOOLS, system())
        return Handled(act(c, thought, text), thought.ms)
    }

    /** Carries out what Poke understood from [text]. Runs on the brain thread. */
    fun act(c: Context, thought: Brain.Thought?, text: String): Reply {
        // 1. precise rules first
        Rules.parse(text)?.let { rule -> return run(c, rule, text, "rules") }
        // 2. Needle's pick (a call it held back counts too)
        var call = thought?.firstCall
        var how = "needle"
        if (call == null) {
            val held = thought?.raw?.optJSONArray("suppressed_calls")
            if (held != null && held.length() > 0) {
                call = held.getJSONObject(0)
                how = "needle (held back)"
            }
        }
        val conf = thought?.confidence ?: Double.NaN
        if (call == null) {
            feel(Mind.Event.CONFUSED)
            return Reply(Caveman.confused(), "no call", "none")
        }
        // 3. guards
        val name = call.optString("name")
        val args = call.optJSONObject("arguments") ?: JSONObject()
        if (name == "remember") {
            val value = args.optString("place").ifBlank { args.optString("fact") }
            when {
                isQuestion(text) -> {
                    // a question: answer it instead of storing it
                    val about = if (Regex("(?i)\\b(you|your|yourself)\\b").containsMatchIn(text)) "ask_poke" else "recall"
                    call = if (about == "ask_poke") Rules.call("ask_poke", JSONObject().put("topic", Rules.topic(text)))
                    else Rules.call("recall", JSONObject().put("query", text))
                    how += " → guard: question"
                }
                value.isBlank() || !grounded(value, text) || (!conf.isNaN() && conf < 0.3) -> {
                    feel(Mind.Event.CONFUSED)
                    return Reply(Caveman.unsure(), "not stored: unsure or not in your words", "$how → guard", call.toString())
                }
            }
        }
        return run(c, call!!, text, how)
    }

    private fun run(c: Context, call: JSONObject, text: String, how: String): Reply {
        val args = call.optJSONObject("arguments") ?: JSONObject()
        val parked = Regex("(?i)\\bpark").containsMatchIn(text)
        val r: Pair<String, String> = when (call.optString("name")) {
            "remember" -> {
                val place = args.optString("place")
                val fact = args.optString("fact")
                if (place.isNotBlank()) {
                    val item = args.optString("item").ifBlank { if (parked) "car" else "thing" }
                    Memory.storePlace(c, item, place)
                    feel(Mind.Event.TAUGHT)
                    Caveman.stored(item, place) to "remembered $item → $place"
                } else {
                    Memory.learnFact(c, fact)
                    feel(Mind.Event.TAUGHT)
                    Caveman.learned(fact) to "learned: $fact"
                }
            }
            "recall" -> {
                val q = args.optString("query").ifBlank { text }
                val found = Memory.recall(c, q, parked)
                if (found != null) {
                    feel(Mind.Event.TAUGHT)
                    found.first to "recalled: ${found.second}"
                } else {
                    feel(Mind.Event.CONFUSED)
                    Caveman.notFound() to "nothing remembered for \"$q\""
                }
            }
            "ask_poke" -> {
                val topic = args.optString("topic").ifBlank { Rules.topic(text) }
                feel(Mind.Event.GREETING)
                Caveman.aboutMe(topic) to "about me: $topic"
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
                Caveman.feeling(f) to "feeling: $f"
            }
            "phone_action" -> {
                val req = args.optString("request").ifBlank { text }
                val action = Actions.understand(c, req)
                if (action == null) {
                    feel(Mind.Event.CONFUSED)
                    Caveman.confused() to "no action for \"$req\""
                } else {
                    feel(Mind.Event.TAUGHT)
                    Caveman.didAction(Actions.run(c, action)) to "action: $action"
                }
            }
            "opinion" -> {
                val about = args.optString("about")
                Opinions.answer(c, about) to "opinion about $about"
            }
            "set_timer" -> {
                var secs = args.optInt("minutes", 0) * 60 + args.optInt("seconds", 0)
                if (secs <= 0) secs = Rules.duration(text)
                if (secs <= 0) {
                    feel(Mind.Event.CONFUSED)
                    "How long? Me no hear time." to "timer without a length"
                } else {
                    val label = com.monolith.poke.Timers.label(secs)
                    android.os.Handler(android.os.Looper.getMainLooper()).post { com.monolith.poke.Timers.start(c, secs, label) }
                    feel(Mind.Event.TAUGHT)
                    Caveman.timer(label) to "timer: $secs s"
                }
            }
            else -> {
                feel(Mind.Event.CONFUSED)
                Caveman.confused() to "unknown tool"
            }
        }
        return Reply(r.first, r.second, how, call.toString())
    }
}

/** Precise rules for the everyday phrasings; they run before Needle. */
object Rules {
    private val numbers = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30,
        "forty" to 40, "forty-five" to 45, "sixty" to 60,
    )

    fun call(name: String, args: JSONObject): JSONObject = JSONObject().put("name", name).put("arguments", args)

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

    /** Which question about Poke itself [text] is. */
    fun topic(text: String): String {
        val t = text.lowercase(Locale.US)
        return when {
            Regex("\\bname\\b|\\bwho are you\\b|\\bcalled\\b").containsMatchIn(t) -> "name"
            Regex("\\bold\\b|\\bage\\b|\\bborn\\b|\\bbirthday\\b").containsMatchIn(t) -> "age"
            Regex("\\blike\\b|\\blove\\b|\\bfavou?rite\\b|\\benjoy\\b").containsMatchIn(t) -> "likes"
            Regex("\\bcan you\\b|\\bwhat do you do\\b|\\babilit|\\bhelp\\b").containsMatchIn(t) -> "abilities"
            else -> "feeling"
        }
    }

    fun parse(text: String): JSONObject? {
        val t = text.trim().trimEnd('.', '!', '?')
        val low = t.lowercase(Locale.US)
        // timers
        if (Regex("\\b(timer|remind me in|countdown|count down)\\b").containsMatchIn(low)) {
            val s = duration(low)
            if (s > 0) return call("set_timer", JSONObject().put("seconds", s))
        }
        // opinions: "do you like pizza?", "what do you think about TikTok?"
        Regex("^(?:do you (?:like|love|hate)|what do you think (?:about|of)|how do you feel about)\\s+(.+)$").find(low)?.let { m ->
            return call("opinion", JSONObject().put("about", m.groupValues[1].replace(Regex("^(the|a|an)\\s+"), "")))
        }
        // questions about Poke itself: "what is your name", "how are you", "how old are you"
        if (Skills.isQuestion(text) && Regex("\\b(you|your|yourself)\\b").containsMatchIn(low) &&
            !Regex("\\b(where|remember|did i|my)\\b").containsMatchIn(low)
        ) {
            return call("ask_poke", JSONObject().put("topic", topic(low)))
        }
        // "where did I park", "where are my keys"
        if (Regex("\\bwhere(?:'s| is| are| did i| do i| have i)\\b").containsMatchIn(low)) {
            return call("recall", JSONObject().put("query", t))
        }
        // other questions about things the user told Poke: "what is my sister's name"
        if (Skills.isQuestion(text) && Regex("\\b(my|mine|i)\\b").containsMatchIn(low)) {
            return call("recall", JSONObject().put("query", t))
        }
        if (Skills.isQuestion(text)) return null // other questions: let Needle try, guarded
        // "I parked on level 3 near the blue pillar"
        Regex("\\b(?:i\\s+)?parked\\s+(?:my\\s+(\\w+)\\s+)?(.+)").find(low)?.let { m ->
            val item = m.groupValues[1].ifBlank { "car" }
            return call("remember", JSONObject().put("item", item).put("place", t.substring(m.groups[2]!!.range.first)))
        }
        // "my keys are in the kitchen drawer" / "I left my wallet on the desk"
        Regex("\\b(?:i\\s+(?:left|put)\\s+)?(?:my|the)\\s+([a-z]+(?:\\s[a-z]+)?)\\s+(?:is|are)?\\s*((?:in|on|at|under|behind|near|next to|inside)\\b.+)").find(low)?.let { m ->
            return call("remember", JSONObject().put("item", m.groupValues[1]).put("place", t.substring(m.groups[2]!!.range.first)))
        }
        // "remember that ...", "my sister's name is Mia", "I like pizza"
        Regex("^(?:remember(?: that)?|note that)\\s+(.+)").find(low)?.let { m ->
            return call("remember", JSONObject().put("fact", t.substring(m.groups[1]!!.range.first)))
        }
        if (Regex("^(?:my\\s+\\w+(?:'s)?(?:\\s\\w+)?\\s+(?:is|are)\\s+.+|i\\s+(?:am|like|love|hate|work|live)\\s+.+)").containsMatchIn(low)) {
            return call("remember", JSONObject().put("fact", t))
        }
        return null
    }
}

/**
 * Poke's memories of places and facts (stage 3). Each memory has a strength: recalling it
 * makes it stronger, and every night the ones nobody asks about fade a little (see [Sleep]).
 * Embeddings are cached so finding things by meaning stays fast.
 */
object Memory {
    private val STOP = setOf("the", "a", "an", "my", "your", "is", "are", "was", "were", "i", "me", "you", "what", "whats",
        "where", "did", "do", "does", "of", "to", "in", "on", "at", "it", "s", "that", "tell", "remember", "please", "poke")

    private fun db(c: Context) = Db.get(c)

    fun keywords(s: String) = s.lowercase(Locale.US).replace("'s", "").split(Regex("[^a-z0-9]+"))
        .filter { it.length > 1 && it !in STOP }.toSet()

    private fun norm(s: String) = s.lowercase(Locale.US)
        .replace(Regex("\\b(my|the|a|an|our|your)\\b"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /** Moves memories saved by older versions (in preferences) into the database, once. */
    private fun migrate(c: Context) {
        val p = c.getSharedPreferences("poke_memory", Context.MODE_PRIVATE)
        if (p.getBoolean("migrated", false)) return
        try {
            val places = JSONObject(p.getString("places", "{}") ?: "{}")
            places.keys().forEach { k ->
                val e = places.getJSONObject(k)
                insert(c, "place", e.optString("item"), e.optString("place"), null)
            }
            val facts = JSONArray(p.getString("facts", "[]") ?: "[]")
            for (i in 0 until facts.length()) {
                val f = facts.getJSONObject(i)
                val text = f.optString("fact")
                if (text.isNotBlank() && !Skills.isQuestion(text)) insert(c, "fact", null, text, null)
            }
        } catch (_: Exception) {
        }
        p.edit().putBoolean("migrated", true).commit()
    }

    private fun insert(c: Context, kind: String, item: String?, text: String, emb: FloatArray?) {
        db(c).insert("facts", null, android.content.ContentValues().apply {
            put("kind", kind)
            put("item", item)
            put("text", text)
            put("at", System.currentTimeMillis())
            put("strength", 1.0)
            if (emb != null) put("emb", Db.pack(emb))
        })
    }

    fun storePlace(c: Context, item: String, place: String) {
        migrate(c)
        // a new place for the same item replaces the old one
        db(c).delete("facts", "kind = 'place' AND item = ?", arrayOf(norm(item)))
        insert(c, "place", norm(item), place, runCatching { Brain.embed(c, "$item $place") }.getOrNull())
    }

    fun learnFact(c: Context, fact: String) {
        migrate(c)
        val emb = runCatching { Brain.embed(c, fact) }.getOrNull()
        // the same fact told again makes the memory stronger instead of duplicating it
        val same = all(c).firstOrNull { it.kind == "fact" && (it.text.equals(fact, true) ||
            (emb != null && it.emb != null && Brain.cosine(emb, it.emb) > 0.95f)) }
        if (same != null) {
            db(c).execSQL("UPDATE facts SET strength = strength + 0.5, text = ?, at = ? WHERE id = ?",
                arrayOf<Any>(fact, System.currentTimeMillis(), same.id))
        } else {
            insert(c, "fact", null, fact, emb)
        }
    }

    class Mem(val id: Long, val kind: String, val item: String?, val text: String, val at: Long,
              val strength: Double, val recalls: Int, val emb: FloatArray?)

    fun all(c: Context): List<Mem> = try {
        migrate(c)
        db(c).rawQuery("SELECT id, kind, item, text, at, strength, recalls, emb FROM facts ORDER BY at DESC", null).use { cur ->
            val out = mutableListOf<Mem>()
            while (cur.moveToNext()) out.add(Mem(cur.getLong(0), cur.getString(1), cur.getString(2), cur.getString(3),
                cur.getLong(4), cur.getDouble(5), cur.getInt(6), Db.unpack(cur.getBlob(7))))
            out
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun recalled(c: Context, m: Mem) {
        db(c).execSQL("UPDATE facts SET recalls = recalls + 1, strength = MIN(strength + 0.3, 5), last = ? WHERE id = ?",
            arrayOf<Any>(System.currentTimeMillis(), m.id))
    }

    /**
     * Finds what the user asked about: shared keywords first, then closeness of meaning.
     * Returns (what Poke says, what it found).
     */
    fun recall(c: Context, query: String, parked: Boolean): Pair<String, String>? {
        val mems = all(c)
        if (mems.isEmpty()) return null
        val q = keywords(query + if (parked) " car park" else "")
        var best: Mem? = null
        var bestScore = 0f
        for (m in mems) {
            val k = keywords((m.item ?: "") + " " + m.text + if (m.item == "car") " park parked" else "")
            val s = if (k.isEmpty() || q.isEmpty()) 0f else q.count { it in k }.toFloat() / q.size
            if (s > bestScore) { bestScore = s; best = m }
        }
        if (best == null || bestScore < 0.34f) {
            val qe = runCatching { Brain.embed(c, query) }.getOrNull() ?: return null
            best = null
            var bestSim = 0.62f
            for (m in mems) {
                val e = m.emb ?: continue
                val sim = Brain.cosine(qe, e)
                if (sim > bestSim) { bestSim = sim; best = m }
            }
        }
        val m = best ?: return null
        recalled(c, m)
        return if (m.kind == "place") Caveman.recalled(m.item ?: "thing", m.text) to "${m.item} → ${m.text}"
        else Caveman.factRecall(m.text) to m.text
    }

    fun summary(c: Context): String {
        val mems = all(c)
        if (mems.isEmpty()) return "(nothing yet)"
        return mems.joinToString("\n") { m ->
            val bar = "▮".repeat(m.strength.coerceIn(0.0, 5.0).toInt().coerceAtLeast(1))
            if (m.kind == "place") "• ${m.item} → ${m.text}  $bar" else "• ${m.text}  $bar"
        }
    }

    /** Forgets everything Poke was told (places and facts). */
    fun clear(c: Context) {
        migrate(c)
        db(c).delete("facts", null, null)
    }

    /** Night-time consolidation: fade, merge near-duplicates, forget what faded away. */
    fun consolidate(c: Context, keepFactor: Double): Int {
        val now = System.currentTimeMillis()
        // things not recalled for a day fade a little (adults forget slower)
        db(c).execSQL("UPDATE facts SET strength = strength * ? WHERE last IS NULL OR last < ?",
            arrayOf<Any>(keepFactor, now - 86_400_000L))
        // merge near-duplicate facts into the newest
        val mems = all(c).filter { it.kind == "fact" && it.emb != null }
        val gone = mutableSetOf<Long>()
        for (i in mems.indices) for (j in i + 1 until mems.size) {
            val a = mems[i]
            val b = mems[j]
            if (a.id in gone || b.id in gone) continue
            if (Brain.cosine(a.emb!!, b.emb!!) > 0.93f) {
                db(c).execSQL("UPDATE facts SET strength = strength + ? WHERE id = ?", arrayOf<Any>(b.strength, a.id))
                db(c).delete("facts", "id = ?", arrayOf(b.id.toString()))
                gone.add(b.id)
            }
        }
        // memories that faded away and are older than a month are forgotten
        val forgotten = db(c).delete("facts", "strength < 0.15 AND at < ?", arrayOf((now - 30L * 86_400_000L).toString()))
        return forgotten + gone.size
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

    fun notFound(): String = when (tone()) {
        Tone.GRUMPY -> pick("Me no know. You no tell me.", "Hmph. Not in head.")
        else -> pick("Hmm... me no know.", "You no tell me that. Tell me?")
    }

    /** "my sister's name is Mia" -> "you sister name Mia". */
    fun caveFact(fact: String): String = fact.trim().trimEnd('.', '!')
        .replace(Regex("(?i)\\bmy\\b|\\bi am\\b|\\bi'm\\b|\\bi\\b"), "you")
        .replace(Regex("(?i)'s\\b"), "")
        .replace(Regex("(?i)\\b(is|are|am|the|a|an)\\b"), "")
        .replace(Regex("\\s+"), " ").trim()

    fun learned(fact: String): String {
        val f = caveFact(fact)
        return when (tone()) {
            Tone.HAPPY -> pick("Me learn! $f!", "Ooh. $f. Me smart now!")
            Tone.GRUMPY -> pick("Hmph. $f. Okay.", "Me learn. $f.")
            else -> pick("$f. Me learn.", "Me keep: $f.")
        }
    }

    fun factRecall(fact: String): String {
        val f = caveFact(fact)
        return when (tone()) {
            Tone.HAPPY -> pick("Me know! $f!", "$f! Me good memory!")
            Tone.GRUMPY -> pick("Hmph. $f.", "Again? $f.")
            else -> pick("$f.", "Me remember: $f.")
        }
    }

    fun trickFailed(): String = pick("Hmm? Me no get trick.", "Me not know how do that.")

    fun trickLearned(kind: String, trig: String, steps: Int): String {
        val w = when (kind) {
            "phrase" -> "You say \"$trig\", me do it."
            "time" -> "Every day $trig, me do it."
            else -> "When ${trig.replace('_', ' ')}, me do it."
        }
        return pick("New trick! $w", "Ooh! Me learn trick. $w")
    }

    fun trickDone(reports: List<String>): String = pick("Me do trick! ", "Trick time! ") + reports.joinToString(" ")

    fun didAction(report: String): String = report

    fun likeApp(name: String): String = pick("Ooh, $name! Me like.", "$name! Yay!", "Hehe. $name. Good.")
    fun dislikeApp(name: String): String = when (tone()) {
        Tone.GRUMPY -> pick("Hmph. $name again.", "$name... me no like.", "Ugh. $name.")
        else -> pick("$name again...", "Mmh. $name. Me no like much.")
    }

    fun like(what: String, score: Double): String = if (score > 0.5) pick("Me love $what!", "$what! Best!") else pick("Me like $what.", "$what good.")
    fun dislike(what: String, score: Double): String = if (score < -0.5) pick("Me hate $what.", "$what? No no no.") else pick("Me no like $what.", "$what... meh.")
    fun noOpinion(what: String): String = pick("$what? Me no know $what yet.", "Hmm. $what? Me think later.")

    /** Life stage colours the words: babies babble short, teens get a little sulky. */
    fun flavor(words: String): String = when (Mind.stage()) {
        Mind.Stage.BABY -> {
            val short = words.split(Regex("(?<=[.!?])\\s+")).joinToString(" ") { sentence ->
                val ws = sentence.split(" ")
                if (ws.size > 5) ws.take(5).joinToString(" ").trimEnd(',', '.') + "." else sentence
            }
            if (Random.nextFloat() < 0.3f) "$short Goo!" else short
        }
        Mind.Stage.TEEN -> if (Mind.valence < -0.1f && Random.nextFloat() < 0.4f) "$words Whatever." else words
        else -> words
    }

    fun unsure(): String = pick("Hmm? Say again?", "Me not sure. Again?", "Huh? Me no get it.")

    /** Questions about Poke itself. */
    fun aboutMe(topic: String): String {
        val days = Mind.ageDays()
        return when (topic) {
            "name" -> pick("Me Poke!", "Poke! Me Poke.", "Me name Poke. Hehe.")
            "age" -> if (days < 1) pick("Me baby! Born today.", "Me new. Today first day!") else pick("Me $days day old.", "$days day! Me growing.")
            "likes" -> pick("Me like pokes. And you.", "Me like when you hold me.", "Me like play!")
            "abilities" -> pick("Me remember things. Me count time. Me learn tricks.", "Me keep where car is. Me learn facts. Me do tricks.")
            else -> when (tone()) {
                Tone.HAPPY -> pick("Me happy!", "Me good! Happy happy.")
                Tone.GRUMPY -> pick("Me grumpy.", "Hmph. Not good.")
                Tone.SLEEPY -> pick("Me sleepy...", "Tired. Zzz.")
                Tone.PLAIN -> if (Mind.attention > 0.6f) "Me miss you." else pick("Me okay.", "Me fine. You?")
            }
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

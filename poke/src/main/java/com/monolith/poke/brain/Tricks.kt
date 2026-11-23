package com.monolith.poke.brain

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.monolith.poke.Mind
import com.monolith.poke.Voice
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Tricks: skills you teach Poke (stage 4). A trick is a trigger plus a list of phone actions:
 *  - phrase: "when I say good night, turn on do not disturb and turn off the flashlight"
 *  - time:   "every day at 7 am, play music"
 *  - event:  "when I plug in, turn on quiet mode"   (plug in / unplug / unlock / battery low / headphones)
 * Praise right after a trick ("good Poke") makes it stronger; scolding ("no Poke") weakens it,
 * and a trick that gets too weak is forgotten.
 */
object Tricks {
    class Trick(val id: Long, val kind: String, val trig: String, val steps: JSONArray, val said: String,
                val strength: Double, val uses: Int, val emb: FloatArray?)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastRun: Pair<Long, Long>? = null // trick id, time
    private val ranToday = mutableMapOf<Long, String>()

    private fun db(c: Context) = Db.get(c)

    fun all(c: Context): List<Trick> = try {
        db(c).rawQuery("SELECT id, kind, trig, steps, said, strength, uses, emb FROM tricks ORDER BY created DESC", null).use { cur ->
            val out = mutableListOf<Trick>()
            while (cur.moveToNext()) out.add(Trick(cur.getLong(0), cur.getString(1), cur.getString(2),
                JSONArray(cur.getString(3)), cur.getString(4).orEmpty(), cur.getDouble(5), cur.getInt(6), Db.unpack(cur.getBlob(7))))
            out
        }
    } catch (_: Exception) {
        emptyList()
    }

    // ------------------------------------------------------------------ teaching

    private val EVENTS = listOf(
        Regex("\\b(plug (?:it |me )?in|start charging|charging|charge)\\b") to "charging",
        Regex("\\b(unplug|stop charging)\\b") to "unplugged",
        Regex("\\b(unlock)\\b") to "unlock",
        Regex("\\b(battery (?:is |gets |goes )?low|low battery)\\b") to "battery_low",
        Regex("\\b(headphones?|earphones?|earbuds?)\\b") to "headset",
    )

    /** Splits an instruction into a trigger and the steps; null if it isn't one. */
    fun parseTeaching(text: String): Triple<String, String, String>? {
        val t = text.trim().trimEnd('.', '!')
        val low = t.lowercase(Locale.US)
        // phrase: "when I say good night, turn on ..."
        Regex("^(?:poke,?\\s+)?(?:when(?:ever)?|if)\\s+i\\s+say\\s+[\"“']?(.+)$").find(low)?.let { m ->
            val rest = t.substring(m.groups[1]!!.range.first)
            val split = splitTriggerAndSteps(rest) ?: return null
            return Triple("phrase", split.first, split.second)
        }
        // time: "every day at 7 am, play music" / "at 10 pm every night turn on quiet mode"
        if (Regex("\\b(every day|everyday|every morning|every night|every evening|daily|each day|each night)\\b").containsMatchIn(low)) {
            val tod = Actions.timeOfDay(low)
            if (tod >= 0) {
                val stepsStart = Regex("\\b(turn|switch|set|open|launch|start|play|pause|stop|mute|remind|put|skip|make)\\b").find(low)
                if (stepsStart != null) {
                    return Triple("time", "%d:%02d".format(tod / 60, tod % 60), t.substring(stepsStart.range.first))
                }
            }
        }
        // event: "when I plug in, turn on quiet mode"
        Regex("^(?:poke,?\\s+)?(?:when(?:ever)?|if)\\s+(?:i\\s+)?(.+?)[,]\\s*(.+)$").find(low)?.let { m ->
            val cond = m.groupValues[1]
            val ev = EVENTS.firstOrNull { it.first.containsMatchIn(cond) }?.second ?: return@let
            return Triple("event", ev, t.substring(m.groups[2]!!.range.first))
        }
        return null
    }

    /** "good night, turn on dnd" / "good night turn on dnd" -> ("good night", "turn on dnd"). */
    private fun splitTriggerAndSteps(rest: String): Pair<String, String>? {
        val comma = Regex("[\"”',]\\s*(?:you\\s+(?:should\\s+)?|please\\s+|then\\s+)?").find(rest)
        if (comma != null && comma.range.first > 0) {
            val trig = rest.substring(0, comma.range.first).trim()
            val steps = rest.substring(comma.range.last + 1).trim()
            if (trig.isNotEmpty() && steps.isNotEmpty()) return trig to steps
        }
        val verb = Regex("\\b(turn|switch|set|open|launch|start|play|pause|stop|mute|remind|put|skip|make|you should|please)\\b")
            .find(rest.lowercase(Locale.US)) ?: return null
        if (verb.range.first == 0) return null
        return rest.substring(0, verb.range.first).trim() to rest.substring(verb.range.first).trim()
    }

    /** Learns a trick; returns what Poke says. Brain thread. */
    fun teach(c: Context, kind: String, trig: String, stepsText: String): String {
        val parts = stepsText.split(Regex("\\s*(?:,|;|\\band then\\b|\\bthen\\b|\\band\\b)\\s*")).map { it.trim() }.filter { it.length > 2 }
        val steps = JSONArray()
        for (p in parts) Actions.understand(c, p)?.let { steps.put(it) }
        if (steps.length() == 0) return Caveman.trickFailed()
        val emb = if (kind == "phrase") runCatching { Brain.embed(c, trig) }.getOrNull() else null
        // teaching the same trigger again replaces the old trick
        db(c).delete("tricks", "kind = ? AND trig = ?", arrayOf(kind, trig.lowercase(Locale.US)))
        db(c).insert("tricks", null, ContentValues().apply {
            put("kind", kind)
            put("trig", trig.lowercase(Locale.US))
            put("steps", steps.toString())
            put("said", stepsText)
            put("strength", 1.0)
            put("created", System.currentTimeMillis())
            if (emb != null) put("emb", Db.pack(emb))
        })
        main.post { Mind.onEvent(Mind.Event.TAUGHT) }
        return Caveman.trickLearned(kind, trig, steps.length())
    }

    // ------------------------------------------------------------------ doing

    /** A phrase trick that [text] calls for, if any. Brain thread. */
    fun match(c: Context, text: String): Trick? {
        val phrase = all(c).filter { it.kind == "phrase" }
        if (phrase.isEmpty()) return null
        val low = text.lowercase(Locale.US).trim().trimEnd('.', '!', '?').replace(Regex("^(?:hey\\s+)?poke,?\\s*"), "")
        phrase.firstOrNull { low == it.trig || Regex("\\b" + Regex.escape(it.trig) + "\\b").containsMatchIn(low) }?.let { return it }
        // close in meaning, for short sentences only (so ordinary talk isn't mistaken for a trick)
        if (low.split(" ").size > 6) return null
        val e = runCatching { Brain.embed(c, low) }.getOrNull() ?: return null
        return phrase.filter { it.emb != null }.maxByOrNull { Brain.cosine(e, it.emb!!) }
            ?.takeIf { Brain.cosine(e, it.emb!!) > 0.88f }
    }

    /** Performs a trick's steps; returns what Poke says. */
    fun perform(c: Context, t: Trick): String {
        val reports = mutableListOf<String>()
        for (i in 0 until t.steps.length()) reports.add(Actions.run(c, t.steps.getJSONObject(i)))
        db(c).execSQL("UPDATE tricks SET uses = uses + 1, last = ? WHERE id = ?", arrayOf<Any>(System.currentTimeMillis(), t.id))
        lastRun = t.id to System.currentTimeMillis()
        return Caveman.trickDone(reports)
    }

    /** Praise or scolding right after a trick changes how sure Poke is about it. */
    fun feedback(c: Context, good: Boolean): String? {
        val (id, at) = lastRun ?: return null
        if (System.currentTimeMillis() - at > 120_000L) return null
        lastRun = null
        Db.writer.execute {
            if (good) {
                db(c).execSQL("UPDATE tricks SET strength = MIN(strength + 0.3, 5) WHERE id = ?", arrayOf<Any>(id))
            } else {
                db(c).execSQL("UPDATE tricks SET strength = strength - 0.6 WHERE id = ?", arrayOf<Any>(id))
                db(c).delete("tricks", "id = ? AND strength <= 0", arrayOf(id.toString()))
            }
        }
        return if (good) null else "Okay... me do less."
    }

    /** Time tricks: checked every minute by the mind. */
    fun tick(c: Context) {
        val cal = Calendar.getInstance()
        val now = "%d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val day = Db.today()
        Db.writer.execute {
            for (t in all(c).filter { it.kind == "time" && it.trig == now && ranToday[it.id] != day }) {
                ranToday[t.id] = day
                runProactively(c, t)
            }
        }
    }

    /** Event tricks: plug in, unplug, unlock, battery low, headphones. */
    fun onEvent(c: Context, event: String) {
        Db.writer.execute {
            for (t in all(c).filter { it.kind == "event" && it.trig == event }) runProactively(c, t)
        }
    }

    private fun runProactively(c: Context, t: Trick) {
        val words = perform(c, t)
        main.post {
            Mind.onEvent(Mind.Event.PLAY)
            Mind.say(words)
            Voice.say(c, words, Mind)
        }
    }

    fun forget(c: Context, id: Long) {
        db(c).delete("tricks", "id = ?", arrayOf(id.toString()))
    }

    fun forgetAll(c: Context) {
        db(c).delete("tricks", null, null)
    }

    fun summary(c: Context): String {
        val ts = all(c)
        if (ts.isEmpty()) return "(no tricks yet — try \"when I say good night, turn on do not disturb\")"
        return ts.joinToString("\n") { t ->
            val trig = when (t.kind) {
                "phrase" -> "say \"${t.trig}\""
                "time" -> "every day at ${t.trig}"
                else -> "when ${t.trig.replace('_', ' ')}"
            }
            val steps = (0 until t.steps.length()).joinToString(", ") { i ->
                val s = t.steps.getJSONObject(i)
                s.optString("name") + (s.optJSONObject("arguments")?.toString() ?: "")
            }
            "• $trig → $steps  ${"▮".repeat(t.strength.coerceIn(0.0, 5.0).toInt().coerceAtLeast(1))} used ${t.uses}×"
        }
    }
}

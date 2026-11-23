package com.monolith.poke.agent

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/** What a tool did: a line for the chat, and data Needle reads on its next step. */
class Result(val ok: Boolean, val say: String, val data: JSONObject = JSONObject()) {
    /** What Needle is fed back after the call. */
    fun forNeedle(tool: String): String = JSONObject(data.toString()).put("tool", tool).put("ok", ok).put("result", say).toString()
}

/**
 * A tool Needle can call. [confirm] tools (they send something on your behalf) always ask
 * first. [run] reports back through [done] on the main thread.
 */
abstract class Tool(
    val name: String,
    val description: String,
    val properties: JSONObject,
    val required: List<String> = emptyList(),
    val confirm: Boolean = false,
) {
    open val kind = "built-in"

    fun schema(): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("parameters", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required)))

    /** Text used to find this tool by meaning (tool retrieval). */
    fun card(): String = name.replace('_', ' ') + ": " + description

    abstract fun run(a: Activity, args: JSONObject, done: (Result) -> Unit)
}

private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
private fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc).put("minimum", 0)
private val main = Handler(Looper.getMainLooper())

// ---------------------------------------------------------------------- built-in tools

object SetTimer : Tool("set_timer", "Start a countdown timer",
    JSONObject().put("minutes", int("minutes")).put("seconds", int("seconds"))) {
    override fun run(a: Activity, args: JSONObject, done: (Result) -> Unit) {
        val secs = args.optInt("minutes", 0) * 60 + args.optInt("seconds", 0)
        if (secs <= 0) return done(Result(false, "No length for the timer."))
        done(try {
            a.startActivity(Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Needle Chat")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            Result(true, "Timer started in Clock: ${human(secs)}.", JSONObject().put("seconds", secs))
        } catch (_: ActivityNotFoundException) {
            Result(false, "No Clock app that takes timers.")
        } catch (e: SecurityException) {
            Result(false, "Clock refused: ${e.message}")
        })
    }

    private fun human(secs: Int): String = buildList {
        if (secs >= 3600) add("${secs / 3600} h")
        if (secs % 3600 >= 60) add("${secs % 3600 / 60} min")
        if (secs % 60 > 0) add("${secs % 60} s")
    }.joinToString(" ")
}

object SearchWeb : Tool("search_web", "Search the web", JSONObject().put("query", str("what to search for")), listOf("query")) {
    override fun run(a: Activity, args: JSONObject, done: (Result) -> Unit) {
        val q = args.optString("query")
        if (q.isBlank()) return done(Result(false, "Nothing to search for."))
        done(try {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8"))))
            Result(true, "Searching the web for “$q”.")
        } catch (_: ActivityNotFoundException) {
            Result(false, "No browser found.")
        })
    }
}

object CreateNote : Tool("create_note", "Save a note", JSONObject().put("text", str("the note")), listOf("text")) {
    override fun run(a: Activity, args: JSONObject, done: (Result) -> Unit) {
        val t = args.optString("text")
        if (t.isBlank()) return done(Result(false, "Empty note."))
        Notes.add(a, t)
        done(Result(true, "Note saved (Settings → Notes)."))
    }
}

object GetWeather : Tool("get_weather", "Get the current weather for a place",
    JSONObject().put("location", str("city or place")), listOf("location")) {
    override fun run(a: Activity, args: JSONObject, done: (Result) -> Unit) {
        val place = args.optString("location")
        if (place.isBlank()) return done(Result(false, "Which place? Tell me your city once and I'll remember it."))
        thread {
            val r = try {
                val geo = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(place, "UTF-8")))
                val hit = geo.optJSONArray("results")?.optJSONObject(0)
                if (hit == null) Result(false, "Couldn't find “$place”.") else {
                    val name = listOf(hit.optString("name"), hit.optString("country")).filter { it.isNotBlank() }.joinToString(", ")
                    val w = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=${hit.getDouble("latitude")}&longitude=${hit.getDouble("longitude")}" +
                        "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m")).getJSONObject("current")
                    val sky = sky(w.optInt("weather_code"))
                    val temp = w.optDouble("temperature_2m").toInt()
                    Result(true, "$name: $sky, ${temp}°C (feels ${w.optDouble("apparent_temperature").toInt()}°C), " +
                        "humidity ${w.optInt("relative_humidity_2m")}%, wind ${w.optDouble("wind_speed_10m").toInt()} km/h.",
                        JSONObject().put("place", name).put("sky", sky).put("temp_c", temp))
                }
            } catch (e: Exception) {
                Result(false, "Weather unavailable (${e.message ?: "no connection"}).")
            }
            main.post { done(r) }
        }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "NeedleChat")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun sky(code: Int) = when (code) {
        0 -> "clear sky"; 1 -> "mainly clear"; 2 -> "partly cloudy"; 3 -> "overcast"; 45, 48 -> "fog"
        51, 53, 55 -> "drizzle"; 56, 57 -> "freezing drizzle"; 61, 63, 65 -> "rain"; 66, 67 -> "freezing rain"
        71, 73, 75, 77 -> "snow"; 80, 81, 82 -> "rain showers"; 85, 86 -> "snow showers"; 95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"; else -> "weather code $code"
    }
}

object SendMessage : Tool("send_message", "Send a text message to a contact",
    JSONObject().put("to", str("contact name or number")).put("text", str("the message")), listOf("to", "text"), confirm = true) {
    const val CONTACTS_REQUEST = 7
    private var pending: Triple<String, String, (Result) -> Unit>? = null

    override fun run(a: Activity, args: JSONObject, done: (Result) -> Unit) {
        val to = args.optString("to")
        val text = args.optString("text")
        val digits = to.filter { it.isDigit() || it == '+' }
        if (digits.length < 5 && to.isNotBlank() &&
            a.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
        ) {
            pending = Triple(to, text, done)
            a.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), CONTACTS_REQUEST)
            return
        }
        done(compose(a, to, text))
    }

    /** Called by the activity after the contacts permission dialog. */
    fun onContactsAnswered(a: Activity) {
        val (to, text, done) = pending ?: return
        pending = null
        done(compose(a, to, text))
    }

    private fun compose(a: Activity, to: String, text: String): Result {
        val digits = to.filter { it.isDigit() || it == '+' }
        val number = if (digits.length >= 5) digits else lookup(a, to)
        return try {
            a.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + (number ?: ""))).putExtra("sms_body", text))
            if (number != null) Result(true, "Message to ${to.ifBlank { number }} ready — press send.")
            else Result(true, "Message ready — pick “$to” and press send (not in contacts).")
        } catch (_: ActivityNotFoundException) {
            Result(false, "No messaging app found.")
        }
    }

    private fun lookup(c: Context, name: String): String? {
        if (name.isBlank() || c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            c.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }
}

/** Saves a fact about the user or their world (memory, Hermes' MEMORY.md / USER.md). */
object Remember : Tool("remember", "Remember a fact the user tells about themselves or their world",
    JSONObject().put("fact", str("the fact, in the user's words")), listOf("fact")) {
    override fun run(a: Activity, args: JSONObject, done: (Result) -> Unit) {
        val fact = args.optString("fact")
        if (fact.isBlank()) return done(Result(false, "Nothing to remember."))
        com.monolith.poke.brain.Brain.async({ Memory.remember(a, fact) }) {
            done(Result(true, "Remembered: “$fact”."))
        }
    }
}

/** Finds something the user told before. */
object Recall : Tool("recall", "Answer a question about something the user told before",
    JSONObject().put("query", str("what the user wants to know")), listOf("query")) {
    override fun run(a: Activity, args: JSONObject, done: (Result) -> Unit) {
        val q = args.optString("query")
        com.monolith.poke.brain.Brain.async({ Memory.recall(a, q) }) { r ->
            val hit = r.getOrNull()
            done(if (hit != null) Result(true, "You told me: “$hit”.", JSONObject().put("fact", hit))
            else Result(false, "I don't remember anything about that."))
        }
    }
}

/** A tool added in settings: Needle can call it, but it isn't connected to an action. */
class CustomTool(schema: JSONObject) : Tool(
    schema.optString("name"),
    schema.optString("description"),
    schema.optJSONObject("parameters")?.optJSONObject("properties") ?: JSONObject(),
    schema.optJSONObject("parameters")?.optJSONArray("required")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
) {
    override val kind = "custom"
    override fun run(a: Activity, args: JSONObject, done: (Result) -> Unit) =
        done(Result(true, "“$name” called with $args (custom tool: not connected to an action)."))
}

/** Notes saved by create_note. */
object Notes {
    private fun p(c: Context) = c.getSharedPreferences("notes", Context.MODE_PRIVATE)

    fun all(c: Context): List<Pair<Long, String>> = try {
        val a = JSONArray(p(c).getString("notes", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getLong("at") to o.getString("text") } }.reversed()
    } catch (_: Exception) {
        emptyList()
    }

    fun add(c: Context, text: String) {
        val a = JSONArray(p(c).getString("notes", "[]"))
        a.put(JSONObject().put("at", System.currentTimeMillis()).put("text", text))
        p(c).edit().putString("notes", a.toString()).apply()
    }

    fun clear(c: Context) = p(c).edit().remove("notes").apply()
}

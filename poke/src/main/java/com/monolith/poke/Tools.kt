package com.monolith.poke

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

/**
 * Carries out the built-in tools when Needle calls them:
 *  set_timer    -> a timer in the phone's Clock app
 *  get_weather  -> the current weather from Open-Meteo (free, no key), shown in the chat
 *  search_web   -> the browser, with the search
 *  send_message -> the messaging app with the contact and text filled in (you press send)
 *  create_note  -> a note saved in the app (listed in settings)
 * Tools you add yourself in settings only show the call.
 */
object Tools {
    val BUILT_IN = setOf("set_timer", "get_weather", "search_web", "send_message", "create_note")
    private val main = Handler(Looper.getMainLooper())

    /** Runs [call] and reports a short result line through [done] (on the main thread). */
    fun run(a: Activity, call: JSONObject, done: (String) -> Unit) {
        val args = call.optJSONObject("arguments") ?: JSONObject()
        when (call.optString("name")) {
            "set_timer" -> done(timer(a, args))
            "search_web" -> done(search(a, args.optString("query")))
            "create_note" -> done(note(a, args.optString("text")))
            "send_message" -> message(a, args.optString("to"), args.optString("text"), done)
            "get_weather" -> weather(args.optString("location"), done)
            else -> done("“${call.optString("name")}” isn't connected to an action (it's a custom tool).")
        }
    }

    // ------------------------------------------------------------------ timer

    private fun timer(a: Activity, args: JSONObject): String {
        val secs = args.optInt("minutes", 0) * 60 + args.optInt("seconds", 0)
        if (secs <= 0) return "✗ No length for the timer."
        return try {
            a.startActivity(
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "Needle Chat")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            )
            "✓ Timer started in Clock: ${human(secs)}."
        } catch (_: ActivityNotFoundException) {
            "✗ No Clock app that takes timers."
        } catch (e: SecurityException) {
            "✗ Clock refused: ${e.message}"
        }
    }

    private fun human(secs: Int): String = buildList {
        if (secs >= 3600) add("${secs / 3600} h")
        if (secs % 3600 >= 60) add("${secs % 3600 / 60} min")
        if (secs % 60 > 0) add("${secs % 60} s")
    }.joinToString(" ")

    // ------------------------------------------------------------------ web search

    private fun search(a: Activity, query: String): String {
        if (query.isBlank()) return "✗ Nothing to search for."
        return try {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8"))))
            "✓ Searching the web for “$query”."
        } catch (_: ActivityNotFoundException) {
            "✗ No browser found."
        }
    }

    // ------------------------------------------------------------------ notes

    private fun note(c: Context, text: String): String {
        if (text.isBlank()) return "✗ Empty note."
        Notes.add(c, text)
        return "✓ Note saved (see Settings → Notes)."
    }

    // ------------------------------------------------------------------ messages

    private var pendingMessage: Triple<String, String, (String) -> Unit>? = null

    private fun message(a: Activity, to: String, text: String, done: (String) -> Unit) {
        val digits = to.filter { it.isDigit() || it == '+' }
        if (digits.length < 5 && to.isNotBlank() &&
            a.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
        ) {
            // ask once to look the name up in contacts; carry on when answered
            pendingMessage = Triple(to, text, done)
            a.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), CONTACTS_REQUEST)
            return
        }
        done(compose(a, to, text))
    }

    const val CONTACTS_REQUEST = 7

    /** Called by the activity after the contacts permission dialog. */
    fun onContactsAnswered(a: Activity) {
        val (to, text, done) = pendingMessage ?: return
        pendingMessage = null
        done(compose(a, to, text))
    }

    private fun compose(a: Activity, to: String, text: String): String {
        val digits = to.filter { it.isDigit() || it == '+' }
        val number = if (digits.length >= 5) digits else lookup(a, to)
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + (number ?: ""))).putExtra("sms_body", text)
        return try {
            a.startActivity(intent)
            if (number != null) "✓ Message to ${to.ifBlank { number }} ready — press send."
            else "✓ Message ready — pick “$to” and press send (not found in contacts)."
        } catch (_: ActivityNotFoundException) {
            "✗ No messaging app found."
        }
    }

    private fun lookup(c: Context, name: String): String? {
        if (name.isBlank() || c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            c.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"), null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ weather

    private fun weather(place: String, done: (String) -> Unit) {
        if (place.isBlank()) {
            done("✗ Which place? Try “weather in Berlin”.")
            return
        }
        thread {
            val result = try {
                val geo = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(place, "UTF-8")))
                val hit = geo.optJSONArray("results")?.optJSONObject(0)
                if (hit == null) "✗ Couldn't find “$place”." else {
                    val lat = hit.getDouble("latitude")
                    val lon = hit.getDouble("longitude")
                    val name = listOfNotNull(hit.optString("name").takeIf { it.isNotBlank() }, hit.optString("country").takeIf { it.isNotBlank() }).joinToString(", ")
                    val w = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m"))
                        .getJSONObject("current")
                    "✓ $name: ${describe(w.optInt("weather_code"))}, ${w.optDouble("temperature_2m").toInt()}°C " +
                        "(feels ${w.optDouble("apparent_temperature").toInt()}°C), humidity ${w.optInt("relative_humidity_2m")}%, " +
                        "wind ${w.optDouble("wind_speed_10m").toInt()} km/h."
                }
            } catch (e: Exception) {
                "✗ Weather unavailable (${e.message ?: "no connection"})."
            }
            main.post { done(result) }
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

    /** WMO weather codes. */
    private fun describe(code: Int) = when (code) {
        0 -> "clear sky"
        1 -> "mainly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61, 63, 65 -> "rain"
        66, 67 -> "freezing rain"
        71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "rain showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> "weather code $code"
    }
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

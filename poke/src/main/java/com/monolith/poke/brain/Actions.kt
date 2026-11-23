package com.monolith.poke.brain

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.monolith.poke.Mind
import com.monolith.poke.Timers
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Poke's toolbox of phone actions (stage 4). Tricks are made of these, and Poke can also do
 * one on request ("turn on the flashlight"). Needle 3 maps a request onto one of these tools.
 */
object Actions {
    /** Six tools, plain names (Needle 3 prefers small toolsets). */
    val TOOLS: String = JSONArray()
        .put(tool("set_timer", "Start a countdown timer",
            JSONObject()
                .put("minutes", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 600))
                .put("seconds", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 3600)),
            listOf()))
        .put(tool("flashlight", "Turn the flashlight (torch) on or off",
            JSONObject().put("on", JSONObject().put("type", "boolean")), listOf("on")))
        .put(tool("quiet_mode", "Turn do not disturb (silent mode) on or off",
            JSONObject().put("on", JSONObject().put("type", "boolean")), listOf("on")))
        .put(tool("open_app", "Open an app on the phone",
            JSONObject().put("name", JSONObject().put("type", "string").put("description", "the app's name")), listOf("name")))
        .put(tool("media_control", "Control music and volume",
            JSONObject().put("command", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("play", "pause", "next", "previous", "louder", "quieter", "mute"))))
                .let { it }, listOf("command")))
        .put(tool("reminder", "Remind the user of something at a time of day or after some minutes",
            JSONObject()
                .put("text", JSONObject().put("type", "string").put("description", "what to remind about"))
                .put("time", JSONObject().put("type", "string").put("description", "time of day like 7:30 or 19:30"))
                .put("minutes", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 1440)),
            listOf("text")))
        .toString()

    private fun tool(name: String, desc: String, props: JSONObject, required: List<String>) = JSONObject()
        .put("name", name)
        .put("description", desc)
        .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)))

    private val main = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ rules for actions

    private val numbers = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "noon" to 12, "midnight" to 0)

    /** "7", "7:30", "7 am", "half past 7", "19:45", "seven pm" -> minutes after midnight, or -1. */
    fun timeOfDay(text: String): Int {
        val t = text.lowercase(Locale.US)
        val m = Regex("\\b(\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|noon|midnight)(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.|in the morning|in the evening|at night|tonight)?\\b")
            .findAll(t).firstOrNull { it.groupValues[1].toIntOrNull()?.let { n -> n in 0..23 } ?: true } ?: return -1
        var h = m.groupValues[1].toIntOrNull() ?: numbers[m.groupValues[1]] ?: return -1
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val mer = m.groupValues[3]
        if ((mer.startsWith("p") || mer.contains("evening") || mer.contains("night")) && h < 12) h += 12
        if (mer.startsWith("a") && h == 12) h = 0
        return h * 60 + min
    }

    private fun call(name: String, args: JSONObject) = JSONObject().put("name", name).put("arguments", args)

    /** Precise phrasings of single actions; null if none fits. */
    fun parse(text: String): JSONObject? {
        val low = text.lowercase(Locale.US).trim().trimEnd('.', '!', '?')
        val on = !Regex("\\b(off|disable|stop|end|deactivate)\\b").containsMatchIn(low)
        if (Regex("\\b(flash ?light|torch|light)\\b").containsMatchIn(low) && Regex("\\b(turn|switch|put|on|off)\\b").containsMatchIn(low)) {
            return call("flashlight", JSONObject().put("on", on))
        }
        if (Regex("\\b(do not disturb|dnd|silent mode|quiet mode|focus mode)\\b").containsMatchIn(low)) {
            return call("quiet_mode", JSONObject().put("on", on))
        }
        Regex("\\b(?:remind me|reminder)(?: to)?\\s+(.+?)\\s+(?:at|in)\\s+(.+)$").find(low)?.let { m ->
            val what = m.groupValues[1]
            val whenText = m.groupValues[2]
            val mins = Rules.duration(whenText) / 60
            val args = JSONObject().put("text", what)
            if (low.contains(" in ") && mins > 0) args.put("minutes", mins) else {
                val tod = timeOfDay(whenText)
                if (tod >= 0) args.put("time", "%d:%02d".format(tod / 60, tod % 60))
            }
            return call("reminder", args)
        }
        if (Regex("\\b(timer|countdown|count down)\\b").containsMatchIn(low)) {
            val s = Rules.duration(low)
            if (s > 0) return call("set_timer", JSONObject().put("seconds", s))
        }
        val media = when {
            Regex("\\b(next song|next track|skip)\\b").containsMatchIn(low) -> "next"
            Regex("\\b(previous song|previous track|go back a song)\\b").containsMatchIn(low) -> "previous"
            Regex("\\b(louder|volume up|turn it up|turn up)\\b").containsMatchIn(low) -> "louder"
            Regex("\\b(quieter|volume down|turn it down|turn down)\\b").containsMatchIn(low) -> "quieter"
            Regex("\\b(mute)\\b").containsMatchIn(low) -> "mute"
            Regex("\\b(pause|stop the music|stop music)\\b").containsMatchIn(low) -> "pause"
            Regex("\\b(play|resume)\\b.*\\b(music|song|it)\\b|^play$|^resume").containsMatchIn(low) -> "play"
            else -> null
        }
        if (media != null) return call("media_control", JSONObject().put("command", media))
        Regex("^(?:please\\s+)?(?:open|launch|start)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$").find(low)?.let { m ->
            return call("open_app", JSONObject().put("name", m.groupValues[1]))
        }
        return null
    }

    /** One request -> one action call (rules first, then Needle). Brain thread. */
    fun understand(c: Context, request: String): JSONObject? {
        parse(request)?.let { return it }
        val t = runCatching { Brain.think(c, request, TOOLS, Skills.system()) }.getOrNull() ?: return null
        return t.firstCall ?: t.raw.optJSONArray("suppressed_calls")?.optJSONObject(0)
    }

    // ------------------------------------------------------------------ doing

    /** Carries out one action call; returns a short caveman report. */
    fun run(c: Context, call: JSONObject): String {
        val a = call.optJSONObject("arguments") ?: JSONObject()
        return try {
            when (call.optString("name")) {
                "set_timer" -> {
                    val secs = a.optInt("minutes", 0) * 60 + a.optInt("seconds", 0)
                    if (secs <= 0) return "How long?"
                    val label = Timers.label(secs)
                    main.post { Timers.start(c, secs, label) }
                    "Count $label."
                }
                "flashlight" -> flashlight(c, a.optBoolean("on", true))
                "quiet_mode" -> quietMode(c, a.optBoolean("on", true))
                "open_app" -> openApp(c, a.optString("name"))
                "media_control" -> media(c, a.optString("command"))
                "reminder" -> reminder(c, a)
                else -> "Me no know that."
            }
        } catch (e: Exception) {
            "Me try... no work."
        }
    }

    private fun flashlight(c: Context, on: Boolean): String {
        val cm = c.getSystemService(CameraManager::class.java) ?: return "No light."
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "No light."
        cm.setTorchMode(id, on)
        return if (on) "Light on!" else "Light off."
    }

    private fun quietMode(c: Context, on: Boolean): String {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return "No can."
        if (!nm.isNotificationPolicyAccessGranted) return "Me need permission. Open Poke lab."
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return if (on) "Shh. Quiet now." else "Quiet off."
    }

    /** Finds the installed app whose name matches best and opens it. */
    private fun openApp(c: Context, name: String): String {
        val pm = c.packageManager
        val wanted = name.lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), "").trim()
        if (wanted.isEmpty()) return "Open what?"
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        var best: Pair<String, String>? = null
        var bestScore = 0
        for (ri in apps) {
            val label = ri.loadLabel(pm).toString()
            val l = label.lowercase(Locale.US)
            val score = when {
                l == wanted -> 100
                l.startsWith(wanted) -> 80
                l.contains(wanted) -> 60
                wanted.contains(l) && l.length > 2 -> 50
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                best = ri.activityInfo.packageName to label
            }
        }
        val (pkg, label) = best ?: return "Me no find $name."
        val launch = pm.getLaunchIntentForPackage(pkg) ?: return "Me no find $name."
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(launch)
        return "Open $label!"
    }

    private fun media(c: Context, command: String): String {
        val am = c.getSystemService(AudioManager::class.java) ?: return "No can."
        fun key(code: Int) {
            val t = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        }
        return when (command) {
            "play" -> { key(KeyEvent.KEYCODE_MEDIA_PLAY); "Music!" }
            "pause" -> { key(KeyEvent.KEYCODE_MEDIA_PAUSE); "Pause." }
            "next" -> { key(KeyEvent.KEYCODE_MEDIA_NEXT); "Next!" }
            "previous" -> { key(KeyEvent.KEYCODE_MEDIA_PREVIOUS); "Back one." }
            "louder" -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); "Louder!" }
            "quieter" -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); "Quieter." }
            "mute" -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI); "Shh." }
            else -> "Hmm?"
        }
    }

    private fun reminder(c: Context, a: JSONObject): String {
        val text = a.optString("text").ifBlank { "something" }
        val minutes = a.optInt("minutes", 0)
        val at: Long
        if (minutes > 0) {
            at = System.currentTimeMillis() + minutes * 60_000L
        } else {
            val tod = timeOfDay(a.optString("time"))
            if (tod < 0) return "When?"
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, tod / 60)
                set(Calendar.MINUTE, tod % 60)
                set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            at = cal.timeInMillis
        }
        val am = c.getSystemService(AlarmManager::class.java) ?: return "No can."
        val pi = PendingIntent.getBroadcast(c, (at / 1000).toInt(),
            Intent(c, ReminderReceiver::class.java).putExtra("text", text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        return "Me remind: ${Caveman.caveFact(text)}, at %d:%02d.".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }

    fun needsQuietPermission(c: Context): Boolean =
        c.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted != true

    fun isInstalled(c: Context, pkg: String) = try {
        c.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

/** A reminder is due: Poke calls out, shows it in the bubble and in a silent notification. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: "something"
        Mind.onEvent(Mind.Event.TIMER)
        Mind.say("Hey! ${Caveman.caveFact(text)}!")
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(android.app.NotificationChannel("poke_timers_quiet", "Poke timers",
            NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        val n = android.app.Notification.Builder(c, "poke_timers_quiet")
            .setSmallIcon(com.monolith.poke.R.drawable.ic_poke)
            .setContentTitle("Poke reminds you")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify((System.currentTimeMillis() / 1000).toInt(), n)
        } catch (_: SecurityException) {
        }
    }
}

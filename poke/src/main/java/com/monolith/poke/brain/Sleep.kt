package com.monolith.poke.brain

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.monolith.poke.Mind
import java.util.Calendar

/**
 * What Poke does in its sleep (stage 3), once a night:
 *  - tidies memory: memories nobody asked about fade a little, near-duplicates merge, faded
 *    old ones are forgotten (adults forget slower than babies);
 *  - clears out old everyday episodes;
 *  - writes yesterday's diary entry, in its own words;
 * and, once a day whatever happens, snapshots its personality for the journal chart.
 */
object Sleep {
    private fun prefs(c: Context) = c.getSharedPreferences("poke_sleep", Context.MODE_PRIVATE)

    /** Called by the mind every minute. */
    fun tick(c: Context) {
        val today = Db.today()
        val p = prefs(c)
        if (p.getString("snapshot", "") != today) {
            p.edit().putString("snapshot", today).apply()
            snapshot(c, today)
        }
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (Mind.asleep && hour in 1..6 && p.getString("slept", "") != today) {
            p.edit().putString("slept", today).apply()
            Db.writer.execute { dream(c) }
        }
    }

    private fun snapshot(c: Context, day: String) {
        val t = Mind.traits.copyOf()
        val v = Mind.valence
        val stage = Mind.stage().name.lowercase()
        Db.writer.execute {
            try {
                Db.get(c).insertWithOnConflict("traits", null, ContentValues().apply {
                    put("day", day)
                    put("playful", t[Mind.PLAYFUL].toDouble())
                    put("bold", t[Mind.BOLD].toDouble())
                    put("affectionate", t[Mind.AFFECTIONATE].toDouble())
                    put("grumpy", t[Mind.GRUMPY].toDouble())
                    put("energetic", t[Mind.ENERGETIC].toDouble())
                    put("valence", v.toDouble())
                    put("stage", stage)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            } catch (_: Exception) {
            }
        }
    }

    /** The night's work. Runs on the database thread. */
    fun dream(c: Context) {
        try {
            val keep = when (Mind.stage()) {
                Mind.Stage.BABY -> 0.85
                Mind.Stage.KID -> 0.9
                Mind.Stage.TEEN -> 0.93
                Mind.Stage.ADULT -> 0.96
            }
            val forgotten = Memory.consolidate(c, keep)
            Episodes.prune(c)
            writeDiary(c, forgotten)
        } catch (_: Exception) {
        }
    }

    /** Yesterday in Poke's words. */
    private fun writeDiary(c: Context, forgotten: Int) {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val from = cal.timeInMillis
        val to = from + 86_400_000L
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(from))
        val eps = Episodes.ofDay(c, from, to)
        val pokes = eps.count { it.first == "event" && it.second == "poke" }
        val holds = eps.count { it.first == "event" && it.second == "hold_start" }
        val talks = eps.count { it.first == "heard" }
        val praise = eps.count { it.first == "event" && it.second == "praise" }
        val scold = eps.count { it.first == "event" && it.second == "scold" }
        val taught = eps.count { it.first == "event" && it.second == "taught" }
        val mood = Episodes.moodOfDay(c, from, to)
        val parts = mutableListOf<String>()
        if (pokes + holds + talks == 0) parts.add("You no play with me. Me lonely.")
        if (pokes > 0) parts.add("You poke me $pokes time.")
        if (holds > 0) parts.add("You hold me $holds time.")
        if (talks > 0) parts.add("We talk $talks time.")
        if (taught > 0) parts.add("Me learn $taught thing.")
        if (praise > 0) parts.add("You say me good $praise time!")
        if (scold > 0) parts.add("You say me bad $scold time.")
        if (forgotten > 0) parts.add("Me forget $forgotten old thing.")
        parts.add(when {
            mood > 0.35 -> "Me very happy day."
            mood > 0.1 -> "Good day."
            mood < -0.2 -> "Not good day."
            else -> "Okay day."
        })
        Db.get(c).insertWithOnConflict("diary", null, ContentValues().apply {
            put("day", day)
            put("text", parts.joinToString(" "))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    class Snapshot(val day: String, val traits: FloatArray, val stage: String)

    fun history(c: Context, days: Int): List<Snapshot> = try {
        Db.get(c).rawQuery("SELECT day, playful, bold, affectionate, grumpy, energetic, stage FROM traits ORDER BY day DESC LIMIT $days", null).use { cur ->
            val out = mutableListOf<Snapshot>()
            while (cur.moveToNext()) out.add(Snapshot(cur.getString(0),
                floatArrayOf(cur.getFloat(1), cur.getFloat(2), cur.getFloat(3), cur.getFloat(4), cur.getFloat(5)), cur.getString(6).orEmpty()))
            out.reversed()
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun diary(c: Context, n: Int): List<Pair<String, String>> = try {
        Db.get(c).rawQuery("SELECT day, text FROM diary ORDER BY day DESC LIMIT $n", null).use { cur ->
            val out = mutableListOf<Pair<String, String>>()
            while (cur.moveToNext()) out.add(cur.getString(0) to cur.getString(1))
            out
        }
    } catch (_: Exception) {
        emptyList()
    }
}

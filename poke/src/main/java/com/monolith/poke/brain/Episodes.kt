package com.monolith.poke.brain

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.monolith.poke.Mind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Poke's episodic memory: everything that happens to it, in order. Each episode keeps when it
 * happened, what kind it was (heard, event), the words, what Poke made of them, how Poke felt
 * (mood and face) and, for things it heard, a Needle 3 embedding so stage 3 can find related
 * memories by meaning and tidy them up at night.
 */
object Episodes {
    private val writer get() = Db.writer
    private fun db(c: Context): SQLiteDatabase = Db.get(c)

    /** Records an episode (written in the background). */
    fun log(c: Context, kind: String, text: String, detail: String, emb: FloatArray? = null) {
        val app = c.applicationContext
        val v = Mind.valence
        val a = Mind.arousal
        val face = Mind.face.name.lowercase()
        writer.execute {
            try {
                db(app).insert(
                    "episodes", null,
                    ContentValues().apply {
                        put("at", System.currentTimeMillis())
                        put("kind", kind)
                        put("text", text)
                        put("detail", detail)
                        put("valence", v.toDouble())
                        put("arousal", a.toDouble())
                        put("face", face)
                        if (emb != null) put("emb", pack(emb))
                    },
                )
            } catch (_: Exception) {
            }
        }
    }

    /** The latest [n] episodes as readable lines, newest first. */
    fun recent(c: Context, n: Int): List<String> = try {
        val fmt = SimpleDateFormat("MMM d HH:mm", Locale.US)
        db(c).rawQuery("SELECT at, kind, text, detail, valence, emb IS NOT NULL FROM episodes ORDER BY at DESC LIMIT ${n.coerceIn(1, 500)}", null).use { cur ->
            val out = mutableListOf<String>()
            while (cur.moveToNext()) {
                val mood = cur.getDouble(4)
                val feel = when {
                    mood > 0.3 -> "☺"
                    mood < -0.2 -> "☹"
                    else -> "·"
                }
                val emb = if (cur.getInt(5) == 1) " ◆" else ""
                val detail = cur.getString(3).orEmpty().let { if (it.isBlank()) "" else " → $it" }
                out.add("${fmt.format(Date(cur.getLong(0)))} $feel ${cur.getString(1)}: ${cur.getString(2)}$detail$emb")
            }
            out
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun count(c: Context): Int = try {
        db(c).rawQuery("SELECT COUNT(*) FROM episodes", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } catch (_: Exception) {
        0
    }

    private fun pack(v: FloatArray): ByteArray = Db.pack(v)

    /** Episodes of one day (for the diary): kind and text. */
    fun ofDay(c: Context, from: Long, to: Long): List<Pair<String, String>> = try {
        db(c).rawQuery("SELECT kind, text FROM episodes WHERE at >= $from AND at < $to", null).use { cur ->
            val out = mutableListOf<Pair<String, String>>()
            while (cur.moveToNext()) out.add(cur.getString(0) to cur.getString(1).orEmpty())
            out
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** Average mood of one day's episodes. */
    fun moodOfDay(c: Context, from: Long, to: Long): Double = try {
        db(c).rawQuery("SELECT AVG(valence) FROM episodes WHERE at >= $from AND at < $to", null).use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }
    } catch (_: Exception) {
        0.0
    }

    /** Night-time tidy: old everyday events go; what was heard is kept longer. */
    fun prune(c: Context) {
        val now = System.currentTimeMillis()
        try {
            db(c).execSQL("DELETE FROM episodes WHERE kind = 'event' AND at < ${now - 21L * 86_400_000L}")
            db(c).execSQL("DELETE FROM episodes WHERE at < ${now - 120L * 86_400_000L}")
        } catch (_: Exception) {
        }
    }
}

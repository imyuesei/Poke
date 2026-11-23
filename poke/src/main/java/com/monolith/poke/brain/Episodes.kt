package com.monolith.poke.brain

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.monolith.poke.Mind
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Poke's episodic memory: everything that happens to it, in order. Each episode keeps when it
 * happened, what kind it was (heard, event), the words, what Poke made of them, how Poke felt
 * (mood and face) and, for things it heard, a Needle 3 embedding so stage 3 can find related
 * memories by meaning and tidy them up at night.
 */
object Episodes {
    private val writer = Executors.newSingleThreadExecutor()
    private var helper: Helper? = null

    private class Helper(c: Context) : SQLiteOpenHelper(c, "poke_memory.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE episodes (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, kind TEXT NOT NULL, " +
                    "text TEXT, detail TEXT, valence REAL, arousal REAL, face TEXT, strength REAL DEFAULT 1, emb BLOB)",
            )
            db.execSQL("CREATE INDEX episodes_at ON episodes(at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private fun db(c: Context): SQLiteDatabase {
        val h = helper ?: Helper(c.applicationContext).also { helper = it }
        return h.writableDatabase
    }

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
        db(c).rawQuery("SELECT at, kind, text, detail, valence, emb IS NOT NULL FROM episodes ORDER BY at DESC LIMIT ?", arrayOf(n.toString())).use { cur ->
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

    private fun pack(v: FloatArray): ByteArray {
        val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { b.putFloat(it) }
        return b.array()
    }

    fun unpack(bytes: ByteArray): FloatArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { b.getFloat() }
    }
}

package com.monolith.poke.brain

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Poke's long-term memory database:
 *  - episodes: everything that happened (stage 2)
 *  - facts:    places and facts it was told, with a strength that grows when recalled and
 *              fades at night, and a cached Needle embedding (stage 3)
 *  - tricks:   skills it was taught: a trigger and a list of phone actions (stage 4)
 *  - opinions: how it feels about apps and words (stage 5)
 *  - traits:   one snapshot of its personality per day, for the journal chart
 *  - diary:    one short caveman entry per day, written while it sleeps
 */
object Db {
    val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "poke-db") }
    private var helper: Helper? = null

    private class Helper(c: Context) : SQLiteOpenHelper(c, "poke_memory.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS episodes (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, kind TEXT NOT NULL, " +
                    "text TEXT, detail TEXT, valence REAL, arousal REAL, face TEXT, strength REAL DEFAULT 1, emb BLOB)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS episodes_at ON episodes(at)")
            onUpgrade(db, 1, 2)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS facts (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, item TEXT, " +
                        "text TEXT NOT NULL, at INTEGER NOT NULL, strength REAL DEFAULT 1, recalls INTEGER DEFAULT 0, last INTEGER, emb BLOB)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS tricks (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, trig TEXT NOT NULL, " +
                        "steps TEXT NOT NULL, said TEXT, strength REAL DEFAULT 1, uses INTEGER DEFAULT 0, created INTEGER, last INTEGER, emb BLOB)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS opinions (entity TEXT PRIMARY KEY, kind TEXT NOT NULL, label TEXT, " +
                        "score REAL DEFAULT 0, count INTEGER DEFAULT 0, last INTEGER)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS traits (day TEXT PRIMARY KEY, playful REAL, bold REAL, affectionate REAL, " +
                        "grumpy REAL, energetic REAL, valence REAL, stage TEXT)",
                )
                db.execSQL("CREATE TABLE IF NOT EXISTS diary (day TEXT PRIMARY KEY, text TEXT)")
            }
        }
    }

    fun get(c: Context): SQLiteDatabase {
        val h = helper ?: synchronized(this) { helper ?: Helper(c.applicationContext).also { helper = it } }
        return h.writableDatabase
    }

    fun pack(v: FloatArray): ByteArray {
        val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { b.putFloat(it) }
        return b.array()
    }

    fun unpack(bytes: ByteArray?): FloatArray? {
        if (bytes == null || bytes.isEmpty()) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { b.getFloat() }
    }

    fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
}

package com.monolith.poke.brain

import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import com.monolith.poke.Mind
import com.monolith.poke.Voice
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/**
 * Poke's opinions (stage 5). It forms them from what it lives through with you:
 *  - Apps: when you open one, the app picks up a little of Poke's mood at that moment, plus
 *    Poke's own taste (a playful Poke likes games and music; a grumpy or affectionate one gets
 *    jealous of long social-media sessions, especially late at night).
 *  - Words: things you talk about pick up the mood Poke was in when you said them.
 * Strong opinions come out on their own: Poke brightens or grumbles when you open an app it
 * has feelings about, and answers "do you like …?".
 * App opinions need Accessibility on (that is how Poke sees which app is in front).
 */
object Opinions {
    private val main = Handler(Looper.getMainLooper())
    private var currentApp: String? = null
    private var since = 0L
    private val lastReaction = mutableMapOf<String, Long>()
    private val IGNORE = setOf("com.android.systemui", "com.monolith.poke", "android", "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard", "com.android.launcher3", "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher", "com.miui.home", "com.oneplus.launcher")

    private fun db(c: Context) = Db.get(c)

    private fun bump(c: Context, entity: String, kind: String, label: String, delta: Double) {
        if (abs(delta) < 1e-4) return
        val v = ContentValues().apply {
            put("entity", entity)
            put("kind", kind)
            put("label", label)
            put("score", 0.0)
            put("count", 0)
            put("last", System.currentTimeMillis())
        }
        db(c).insertWithOnConflict("opinions", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
        db(c).execSQL("UPDATE opinions SET score = MAX(-1, MIN(1, score + ?)), count = count + 1, last = ?, label = ? WHERE entity = ?",
            arrayOf<Any>(delta, System.currentTimeMillis(), label, entity))
    }

    fun score(c: Context, entity: String): Double = try {
        db(c).rawQuery("SELECT score FROM opinions WHERE entity = ?", arrayOf(entity)).use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }
    } catch (_: Exception) {
        0.0
    }

    private fun night(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= 23 || h < 6
    }

    /** The app in front changed (from the accessibility service). */
    fun onApp(c: Context, pkg: String?) {
        if (pkg == null || pkg == currentApp || pkg in IGNORE || pkg.contains("launcher")) return
        val app = c.applicationContext
        val prev = currentApp
        val prevSince = since
        currentApp = pkg
        since = System.currentTimeMillis()
        val valence = Mind.valence.toDouble()
        val traits = Mind.traits.copyOf()
        Db.writer.execute {
            try {
                // the session that just ended: long late-night scrolling makes Poke jealous
                if (prev != null) {
                    val minutes = (System.currentTimeMillis() - prevSince) / 60_000.0
                    if (minutes > 20) {
                        val social = category(app, prev) == ApplicationInfo.CATEGORY_SOCIAL
                        val jealous = 0.03 * (minutes / 30).coerceAtMost(3.0) * (if (night()) 2 else 1) *
                            (if (social) 1.5 else 0.6) * (0.6 + 0.4 * (traits[Mind.AFFECTIONATE] + 1) / 2 + 0.4 * (traits[Mind.GRUMPY] + 1) / 2)
                        bump(app, prev, "app", label(app, prev), -jealous)
                        if (night() && minutes > 30) main.post { Mind.nudgeTrait(Mind.GRUMPY, 0.004f) }
                    }
                }
                // the app just opened: a little of Poke's mood, plus its taste
                val cat = category(app, pkg)
                val taste = when (cat) {
                    ApplicationInfo.CATEGORY_GAME -> 0.04 * traits[Mind.PLAYFUL]
                    ApplicationInfo.CATEGORY_AUDIO -> 0.03 * (traits[Mind.ENERGETIC] + traits[Mind.PLAYFUL]) / 2
                    ApplicationInfo.CATEGORY_VIDEO -> 0.02 * traits[Mind.ENERGETIC]
                    ApplicationInfo.CATEGORY_SOCIAL -> -0.03 * traits[Mind.GRUMPY]
                    ApplicationInfo.CATEGORY_NEWS -> -0.03 * traits[Mind.GRUMPY]
                    else -> 0.0
                }
                val name = label(app, pkg)
                bump(app, pkg, "app", name, 0.04 * valence + taste - (if (night()) 0.02 else 0.0))
                // life with you shapes Poke: games make it more playful, music more energetic
                when (cat) {
                    ApplicationInfo.CATEGORY_GAME -> main.post { Mind.nudgeTrait(Mind.PLAYFUL, 0.002f) }
                    ApplicationInfo.CATEGORY_AUDIO -> main.post { Mind.nudgeTrait(Mind.ENERGETIC, 0.002f) }
                    else -> {}
                }
                // a strong opinion comes out
                val s = score(app, pkg)
                val last = lastReaction[pkg] ?: 0L
                if (abs(s) > 0.35 && System.currentTimeMillis() - last > 45 * 60_000L) {
                    lastReaction[pkg] = System.currentTimeMillis()
                    val words = if (s > 0) Caveman.likeApp(name) else Caveman.dislikeApp(name)
                    main.post {
                        Mind.react(s > 0)
                        Mind.say(words)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Words in what you said pick up Poke's mood. Brain thread. */
    fun onHeard(c: Context, text: String) {
        val v = Mind.valence.toDouble()
        val words = Memory.keywords(text).filter { it.length > 3 }
        if (words.isEmpty()) return
        Db.writer.execute {
            for (w in words.take(6)) bump(c, "w:$w", "word", w, 0.05 * v)
        }
    }

    /** "do you like pizza?" -> Poke's opinion, if it has one. */
    fun answer(c: Context, about: String): String {
        val q = about.lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ").trim()
        if (q.isEmpty()) return Caveman.noOpinion(about)
        val found = try {
            db(c).rawQuery("SELECT label, score FROM opinions WHERE lower(label) = ? OR lower(label) LIKE ? OR entity = ? ORDER BY count DESC LIMIT 1",
                arrayOf(q, "%$q%", "w:$q")).use { if (it.moveToFirst()) it.getString(0) to it.getDouble(1) else null }
        } catch (_: Exception) {
            null
        }
        return when {
            found == null || abs(found.second) < 0.1 -> Caveman.noOpinion(about)
            found.second > 0 -> Caveman.like(found.first, found.second)
            else -> Caveman.dislike(found.first, found.second)
        }
    }

    /** Top likes and dislikes, for the journal. */
    fun summary(c: Context): String = try {
        val likes = mutableListOf<String>()
        val dislikes = mutableListOf<String>()
        db(c).rawQuery("SELECT label, score FROM opinions WHERE count >= 2 ORDER BY score DESC", null).use { cur ->
            while (cur.moveToNext()) {
                val s = cur.getDouble(1)
                if (s > 0.1 && likes.size < 6) likes.add("${cur.getString(0)} (${"%+.2f".format(s)})")
            }
        }
        db(c).rawQuery("SELECT label, score FROM opinions WHERE count >= 2 ORDER BY score ASC", null).use { cur ->
            while (cur.moveToNext()) {
                val s = cur.getDouble(1)
                if (s < -0.1 && dislikes.size < 6) dislikes.add("${cur.getString(0)} (${"%+.2f".format(s)})")
            }
        }
        "likes: " + (likes.joinToString(", ").ifBlank { "nothing yet" }) + "\ndislikes: " + (dislikes.joinToString(", ").ifBlank { "nothing yet" })
    } catch (_: Exception) {
        "(no opinions yet)"
    }

    private fun label(c: Context, pkg: String): String = try {
        val pm = c.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg.substringAfterLast('.')
    }

    private fun category(c: Context, pkg: String): Int = try {
        c.packageManager.getApplicationInfo(pkg, 0).category
    } catch (_: Exception) {
        ApplicationInfo.CATEGORY_UNDEFINED
    }

    fun clear(c: Context) {
        db(c).delete("opinions", null, null)
    }
}

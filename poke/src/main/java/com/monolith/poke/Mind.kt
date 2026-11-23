package com.monolith.poke

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Poke's inner life (stage 1). No AI here: a small simulation that runs all the time.
 *
 * - Personality: five traits in [-1, 1] that drift slowly with how you treat Poke.
 * - Mood: valence (unhappy..happy) and arousal (calm..excited); events push it, and it
 *   relaxes back to a baseline set by the personality.
 * - Drives: needs that build up (attention, curiosity, tiredness). A drive crossing a
 *   threshold is what makes Poke act on its own instead of waiting for you.
 * - Behaviour picker: every few seconds it scores what Poke could do from drives x mood x
 *   personality and picks one (weighted random), so Poke is never a vending machine.
 *
 * The eyes ([Pet]) follow [face] and [act]; [Voice] makes the sounds.
 */
object Mind {
    enum class Face { ROUND, HAPPY, CONTENT, SHY, GRUMPY, SAD, ASLEEP, STARTLED }
    enum class Act { NONE, GLANCE, TILT, BOUNCE, WINK, BLINK, DOUBLE_BLINK, HOP, LOOK }
    enum class Event {
        POKE, DOUBLE_POKE, HOLD_START, HOLD_END,
        UNLOCK, SCREEN_ON, SCREEN_OFF, CHARGING, UNPLUGGED, BATTERY_LOW, HEADSET,
        PRAISE, SCOLD, GREETING, GOODNIGHT, LOVE, PLAY, TAUGHT, CONFUSED,
        WAKE, THINKING, LISTEN_ON, LISTEN_OFF, TIMER,
    }

    /** Trait order in [traits]. */
    const val PLAYFUL = 0
    const val BOLD = 1
    const val AFFECTIONATE = 2
    const val GRUMPY = 3
    const val ENERGETIC = 4
    val TRAIT_NAMES = arrayOf("playful", "bold", "affectionate", "grumpy", "energetic")

    // ---------------------------------------------------------------- state

    val traits = FloatArray(5)
    var valence = 0.2f
        private set
    var arousal = 0.4f
        private set
    var attention = 0f   // 0 = had plenty, 1 = desperate for you
        private set
    var curiosity = 0.3f
        private set
    var energy = 1f      // 1 = rested, 0 = exhausted
        private set
    var asleep = false
        private set

    /** What the eyes show right now. */
    @Volatile var face = Face.ROUND
        private set
    /** The latest one-off act; [actSerial] increments each time so viewers notice. */
    @Volatile var act = Act.NONE
        private set
    @Volatile var actSerial = 0
        private set
    @Volatile var actX = 0f
        private set
    @Volatile var actY = 0f
        private set
    /** True while Poke's ears are open (swiped down); the eyes look attentive. */
    @Volatile var listening = false
        private set
    /** The last thing Poke said (for the speech bubble); [saidSerial] increments each time. */
    @Volatile var said = ""
        private set
    @Volatile var saidSerial = 0
        private set

    private var faceUntil = 0L
    private var nextBehaviour = 0L
    private var lastInteraction = System.currentTimeMillis()
    private var lastCall = 0L // last time Poke called for you on its own
    private var born = System.currentTimeMillis()
    private val pokes = ArrayDeque<Long>()
    private var screenOn = true

    private var ctx: Context? = null
    private var hosts = 0
    private var lastTick = 0L
    private var lastSave = 0L
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() = listeners.toList().forEach { it() }

    private fun now() = SystemClock.uptimeMillis()
    private fun t(i: Int) = traits[i]
    /** A trait mapped to 0..1. */
    private fun t01(i: Int) = (traits[i] + 1f) / 2f

    // ---------------------------------------------------------------- life cycle

    /** Called by everything that shows Poke (the island, the app scene). */
    fun attach(c: Context) {
        hosts++
        if (ctx == null) {
            ctx = c.applicationContext
            load()
            registerEvents()
            lastTick = now()
            main.post(tick)
        }
    }

    fun detach() {
        hosts = max(0, hosts - 1)
        save()
    }

    private val tick = object : Runnable {
        override fun run() {
            val n = now()
            val dt = min(5f, (n - lastTick) / 1000f)
            lastTick = n
            live(dt, n)
            if (System.currentTimeMillis() - lastSave > 60_000) save()
            // keep living while anything shows Poke; otherwise breathe slowly
            main.postDelayed(this, if (hosts > 0) 1000L else 15_000L)
        }
    }

    // ---------------------------------------------------------------- living

    private fun night(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= 23 || h < 7
    }

    private fun idleMs() = System.currentTimeMillis() - lastInteraction

    private fun live(dt: Float, n: Long) {
        // drives
        val lonelyRate = 0.00010f + 0.00020f * t01(AFFECTIONATE) // ~1.5-3 h to "desperate"
        attention = clamp01(attention + dt * lonelyRate)
        curiosity = clamp01(curiosity + dt * (0.0015f + 0.001f * t01(PLAYFUL)))
        energy = if (asleep) clamp01(energy + dt * 0.0004f) else clamp01(energy - dt * (0.00003f + 0.00002f * (1 - t01(ENERGETIC))))

        // mood relaxes to a personality baseline (and sinks when lonely)
        val baseV = 0.15f + 0.1f * t(AFFECTIONATE) + 0.15f * t(PLAYFUL) - 0.25f * t(GRUMPY) - 0.4f * max(0f, attention - 0.6f)
        val baseA = (if (asleep) 0.05f else 0.3f + 0.2f * t(ENERGETIC)) - 0.2f * (1 - energy)
        val k = 1f - exp(-dt / 90f)
        valence += (baseV - valence) * k
        arousal += (baseA - arousal) * k

        // personality: very slow drift from being ignored, and slow return toward centre
        if (attention > 0.85f) {
            nudge(GRUMPY, dt * 0.0000025f)
            nudge(AFFECTIONATE, -dt * 0.0000015f)
        }
        for (i in traits.indices) traits[i] -= traits[i] * dt / (60f * 86400f) // ~2-month memory

        // sleep: at night when left alone or the screen is off; naps when exhausted
        val shouldSleep = (night() && (idleMs() > 90_000 || !screenOn)) || energy < 0.08f
        if (!asleep && shouldSleep && n > faceUntil) fallAsleep()
        if (asleep && !night() && energy > 0.6f) wake(false)

        if (asleep) {
            setFace(Face.ASLEEP)
        } else {
            if (n > faceUntil) setFace(Face.ROUND)
            if (n >= nextBehaviour && n > faceUntil) {
                behave(n)
                val pace = 1f - 0.5f * arousal - 0.2f * t01(ENERGETIC)
                nextBehaviour = n + (2500 + Random.nextLong(4500) * pace).toLong()
            }
        }
    }

    private fun fallAsleep() {
        asleep = true
        setFace(Face.ASLEEP)
        doAct(Act.NONE)
    }

    private fun wake(startled: Boolean) {
        asleep = false
        if (startled) show(Face.STARTLED, 900) else show(Face.ROUND, 0)
        doAct(Act.DOUBLE_BLINK)
    }

    // ---------------------------------------------------------------- behaviours

    private class Option(val weight: Float, val run: () -> Unit)

    private fun behave(n: Long) {
        val v = valence
        val pos = max(0f, v)
        val neg = max(0f, -v)
        val canCall = screenOn && !night() && System.currentTimeMillis() - lastCall > 20 * 60_000L
        val options = listOf(
            Option(0.4f + curiosity) { glance() },
            Option(0.45f) { doAct(Act.BLINK) },
            Option(0.12f) { doAct(Act.DOUBLE_BLINK) },
            Option(0.1f + 0.35f * curiosity * t01(PLAYFUL)) { doAct(Act.TILT); curiosity *= 0.7f },
            Option(0.05f + 0.15f * t01(PLAYFUL) * pos) { doAct(Act.WINK) },
            Option(pos * t01(PLAYFUL) * arousal * 1.2f) { show(Face.HAPPY, 900); doAct(Act.BOUNCE) },
            Option(max(0f, v - 0.1f) * t01(AFFECTIONATE) * 0.8f) { show(Face.CONTENT, 1800) },
            // proactive: Poke wants you
            Option(if (canCall && attention > 0.6f) (attention - 0.6f) * 3f * (0.5f + t01(AFFECTIONATE)) else 0f) {
                lastCall = System.currentTimeMillis()
                doAct(Act.LOOK)
                show(Face.ROUND, 1200)
                Voice.chirp(ctx, Voice.Kind.CALL, this, proactive = true)
            },
            Option(if (attention > 0.8f) (attention - 0.8f) * 4f * (0.5f + t01(GRUMPY)) else neg * 0.6f) {
                show(Face.SAD, 2600)
                if (canCall) {
                    lastCall = System.currentTimeMillis()
                    Voice.chirp(ctx, Voice.Kind.SULK, this, proactive = true)
                }
            },
            Option(max(0f, t(GRUMPY)) * (neg + 0.1f) * 1.2f) { show(Face.GRUMPY, 1400) },
            Option(if (energy < 0.3f) (0.3f - energy) * 3f else 0f) { show(Face.ASLEEP, 6000 + Random.nextLong(8000)) },
        )
        // energetic Poke picks more decisively; calm Poke wanders
        val sharp = 1f + t01(ENERGETIC)
        val weights = options.map { it.weight.coerceAtLeast(0f).pow(sharp) }
        val total = weights.sum()
        if (total <= 0f) return
        var r = Random.nextFloat() * total
        for ((i, w) in weights.withIndex()) {
            r -= w
            if (r <= 0f) {
                options[i].run()
                return
            }
        }
    }

    private fun glance() {
        val x = (Random.nextFloat() * 2f - 1f).let { if (kotlin.math.abs(it) < 0.4f) it * 2f else it }.coerceIn(-1f, 1f)
        val y = (Random.nextFloat() * 2f - 1f) * 0.7f
        doAct(Act.GLANCE, x, y)
        curiosity *= 0.75f
    }

    // ---------------------------------------------------------------- events

    fun onEvent(e: Event) {
        val n = now()
        val social = e in setOf(Event.POKE, Event.DOUBLE_POKE, Event.HOLD_START, Event.PRAISE, Event.SCOLD,
            Event.GREETING, Event.GOODNIGHT, Event.LOVE, Event.PLAY, Event.TAUGHT)
        if (social) {
            lastInteraction = System.currentTimeMillis()
            attention = clamp01(attention - 0.35f)
        }
        when (e) {
            Event.POKE -> {
                pokes.addLast(n)
                while (pokes.isNotEmpty() && n - pokes.first() > 2500) pokes.removeFirst()
                when {
                    asleep -> {
                        wake(true)
                        mood(-0.15f, 0.5f)
                        nudge(GRUMPY, 0.01f)
                        Voice.chirp(ctx, Voice.Kind.STARTLED, this)
                    }
                    pokes.size >= 4 -> {
                        show(Face.GRUMPY, 1400)
                        mood(-0.15f, 0.2f)
                        nudge(GRUMPY, 0.006f)
                        nudge(PLAYFUL, -0.002f)
                        Voice.chirp(ctx, Voice.Kind.GRUMBLE, this)
                    }
                    else -> {
                        show(Face.HAPPY, 1300)
                        doAct(Act.HOP)
                        mood(0.12f * (0.6f + t01(PLAYFUL)), 0.15f)
                        nudge(PLAYFUL, 0.004f)
                        nudge(ENERGETIC, 0.002f)
                        Voice.chirp(ctx, Voice.Kind.HAPPY, this)
                    }
                }
            }
            Event.DOUBLE_POKE -> {
                if (asleep) wake(true)
                show(Face.STARTLED, 1000)
                mood(-0.02f, 0.35f)
                nudge(BOLD, -0.002f)
                Voice.chirp(ctx, Voice.Kind.STARTLED, this)
            }
            Event.HOLD_START -> {
                if (asleep) wake(false)
                show(Face.SHY, 60_000)
                mood(0.2f, -0.05f)
                nudge(AFFECTIONATE, 0.006f)
                nudge(GRUMPY, -0.004f)
                // quiet while held: Poke is listening
            }
            Event.HOLD_END -> {
                show(Face.CONTENT, 1600)
                Voice.chirp(ctx, Voice.Kind.PURR, this)
            }
            Event.UNLOCK -> if (!asleep) {
                if (attention > 0.5f) {
                    show(Face.HAPPY, 1200)
                    doAct(Act.BOUNCE)
                    Voice.chirp(ctx, Voice.Kind.HAPPY, this, proactive = true)
                } else {
                    doAct(Act.LOOK)
                }
            }
            Event.SCREEN_ON -> { screenOn = true; if (!asleep) doAct(Act.BLINK) }
            Event.SCREEN_OFF -> screenOn = false
            Event.CHARGING -> {
                if (!asleep) show(Face.CONTENT, 2200)
                mood(0.1f, 0.05f)
                energy = clamp01(energy + 0.1f)
            }
            Event.UNPLUGGED -> if (!asleep) show(Face.SAD, 1200)
            Event.BATTERY_LOW -> {
                energy = min(energy, 0.25f)
                if (!asleep) {
                    show(Face.SAD, 2000)
                    Voice.chirp(ctx, Voice.Kind.SULK, this, proactive = true)
                }
            }
            Event.HEADSET -> if (!asleep) glance()
            Event.PRAISE -> {
                if (asleep) wake(false)
                show(Face.HAPPY, 1800)
                doAct(Act.BOUNCE)
                mood(0.25f, 0.2f)
                nudge(AFFECTIONATE, 0.008f)
                nudge(BOLD, 0.004f)
                nudge(GRUMPY, -0.004f)
                Voice.chirp(ctx, Voice.Kind.HAPPY, this)
            }
            Event.SCOLD -> {
                if (asleep) wake(false)
                show(if (t(GRUMPY) > 0.2f) Face.GRUMPY else Face.SAD, 2200)
                mood(-0.3f, 0.1f)
                nudge(BOLD, -0.006f)
                nudge(GRUMPY, 0.006f)
                Voice.chirp(ctx, Voice.Kind.SULK, this)
            }
            Event.GREETING -> {
                if (asleep) wake(false)
                show(Face.HAPPY, 1200)
                doAct(Act.HOP)
                mood(0.12f, 0.15f)
                Voice.chirp(ctx, Voice.Kind.HAPPY, this)
            }
            Event.GOODNIGHT -> {
                show(Face.CONTENT, 1500)
                energy = min(energy, 0.5f)
                Voice.chirp(ctx, Voice.Kind.PURR, this)
            }
            Event.LOVE -> {
                if (asleep) wake(false)
                show(Face.SHY, 2500)
                mood(0.3f, 0f)
                nudge(AFFECTIONATE, 0.01f)
                Voice.chirp(ctx, Voice.Kind.PURR, this)
            }
            Event.PLAY -> {
                if (asleep) wake(false)
                show(Face.HAPPY, 1500)
                doAct(Act.BOUNCE)
                mood(0.15f, 0.3f)
                nudge(PLAYFUL, 0.008f)
                Voice.chirp(ctx, Voice.Kind.HAPPY, this)
            }
            Event.TAUGHT -> {
                if (asleep) wake(false)
                show(Face.CONTENT, 1500)
                doAct(Act.BLINK)
                Voice.chirp(ctx, Voice.Kind.PROUD, this)
                mood(0.08f, 0.1f)
                curiosity = clamp01(curiosity - 0.3f)
            }
            Event.CONFUSED -> {
                doAct(Act.TILT)
                Voice.chirp(ctx, Voice.Kind.CONFUSED, this)
            }
            Event.WAKE -> {
                if (asleep) wake(false)
                doAct(Act.LOOK)
                show(Face.STARTLED, 500)
                Voice.chirp(ctx, Voice.Kind.LISTEN, this)
            }
            Event.LISTEN_ON -> {
                if (asleep) wake(false)
                listening = true
                doAct(Act.LOOK)
                Voice.chirp(ctx, Voice.Kind.LISTEN, this)
            }
            Event.LISTEN_OFF -> {
                listening = false
                doAct(Act.BLINK)
            }
            Event.TIMER -> {
                if (asleep) wake(false)
                show(Face.STARTLED, 1200)
                doAct(Act.HOP)
                Voice.chirp(ctx, Voice.Kind.STARTLED, this)
            }
            Event.THINKING -> {
                if (asleep) wake(false)
                doAct(Act.GLANCE, 0.3f, -0.8f) // looks up while it thinks
            }
        }
        logEpisode(e)
        nextBehaviour = max(nextBehaviour, n + 2500)
        changed()
    }

    // ---------------------------------------------------------------- helpers

    /** Poke says [text]: it shows in the bubble under the island. */
    fun say(text: String) {
        said = text
        saidSerial++
        changed()
    }

    private val lastLogged = mutableMapOf<Event, Long>()

    /** Social moments and phone life go into the episodic memory (pokes at most every 15 s). */
    private fun logEpisode(e: Event) {
        val c = ctx ?: return
        if (e == Event.THINKING || e == Event.LISTEN_ON || e == Event.LISTEN_OFF || e == Event.SCREEN_ON || e == Event.SCREEN_OFF || e == Event.HOLD_END) return
        val now = System.currentTimeMillis()
        if (now - (lastLogged[e] ?: 0L) < 15_000L) return
        lastLogged[e] = now
        com.monolith.poke.brain.Episodes.log(c, "event", e.name.lowercase(), "")
    }

    fun show(f: Face, ms: Long) {
        face = f
        faceUntil = now() + ms
        changed()
    }

    private fun setFace(f: Face) {
        if (face != f) {
            face = f
            changed()
        }
    }

    private fun doAct(a: Act, x: Float = 0f, y: Float = 0f) {
        act = a
        actX = x
        actY = y
        actSerial++
        changed()
    }

    private fun mood(dv: Float, da: Float) {
        valence = (valence + dv).coerceIn(-1f, 1f)
        arousal = (arousal + da).coerceIn(0f, 1f)
    }

    private fun nudge(i: Int, d: Float) {
        traits[i] = (traits[i] + d).coerceIn(-1f, 1f)
    }

    private fun clamp01(v: Float) = v.coerceIn(0f, 1f)

    /** Whether sounds are welcome right now (ringer on; proactive ones also need the screen on). */
    fun soundAllowed(proactive: Boolean): Boolean {
        val c = ctx ?: return false
        val am = c.getSystemService(AudioManager::class.java) ?: return false
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
        if (proactive) {
            val pm = c.getSystemService(PowerManager::class.java)
            if (pm != null && !pm.isInteractive) return false
            if (night()) return false
        }
        return true
    }

    /** One line describing Poke's state, for the brain lab. */
    fun describe(): String {
        val days = (System.currentTimeMillis() - born) / 86_400_000L
        val tr = TRAIT_NAMES.indices.joinToString("  ") { "${TRAIT_NAMES[it]} ${"%+.2f".format(traits[it])}" }
        return "age ${days}d · mood ${"%+.2f".format(valence)} / ${"%.2f".format(arousal)} · " +
            "needs you ${"%.2f".format(attention)} · curious ${"%.2f".format(curiosity)} · energy ${"%.2f".format(energy)}" +
            (if (asleep) " · asleep" else "") + "\n" + tr
    }

    // ---------------------------------------------------------------- phone events

    private fun registerEvents() {
        val c = ctx ?: return
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_HEADSET_PLUG)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_USER_PRESENT -> onEvent(Event.UNLOCK)
                    Intent.ACTION_SCREEN_ON -> onEvent(Event.SCREEN_ON)
                    Intent.ACTION_SCREEN_OFF -> onEvent(Event.SCREEN_OFF)
                    Intent.ACTION_POWER_CONNECTED -> onEvent(Event.CHARGING)
                    Intent.ACTION_POWER_DISCONNECTED -> onEvent(Event.UNPLUGGED)
                    Intent.ACTION_BATTERY_LOW -> onEvent(Event.BATTERY_LOW)
                    Intent.ACTION_HEADSET_PLUG -> if (i.getIntExtra("state", 0) == 1) onEvent(Event.HEADSET)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else c.registerReceiver(r, f)
    }

    // ---------------------------------------------------------------- memory of self

    private fun prefs() = ctx?.getSharedPreferences("poke_mind", Context.MODE_PRIVATE)

    private fun load() {
        val p = prefs() ?: return
        val s = p.getString("state", null)
        if (s == null) {
            // a newborn: a little random so every Poke starts slightly different
            for (i in traits.indices) traits[i] = (Random.nextFloat() - 0.5f) * 0.3f
            born = System.currentTimeMillis()
            save()
            return
        }
        try {
            val o = JSONObject(s)
            val tr = o.getJSONArray("traits")
            for (i in traits.indices) traits[i] = tr.getDouble(i).toFloat()
            valence = o.optDouble("valence", 0.2).toFloat()
            arousal = o.optDouble("arousal", 0.4).toFloat()
            attention = o.optDouble("attention", 0.0).toFloat()
            curiosity = o.optDouble("curiosity", 0.3).toFloat()
            energy = o.optDouble("energy", 1.0).toFloat()
            lastInteraction = o.optLong("lastInteraction", System.currentTimeMillis())
            lastCall = o.optLong("lastCall", 0L)
            born = o.optLong("born", System.currentTimeMillis())
            // time passed while Poke wasn't running still counts (it missed you)
            val away = ((System.currentTimeMillis() - o.optLong("saved", System.currentTimeMillis())) / 1000f).coerceIn(0f, 86400f)
            attention = clamp01(attention + away * 0.00015f)
            energy = clamp01(energy + away * 0.0002f)
        } catch (_: Exception) {
        }
    }

    private fun save() {
        val p = prefs() ?: return
        lastSave = System.currentTimeMillis()
        val o = JSONObject()
            .put("traits", org.json.JSONArray(traits.map { it.toDouble() }))
            .put("valence", valence.toDouble())
            .put("arousal", arousal.toDouble())
            .put("attention", attention.toDouble())
            .put("curiosity", curiosity.toDouble())
            .put("energy", energy.toDouble())
            .put("lastInteraction", lastInteraction)
            .put("lastCall", lastCall)
            .put("born", born)
            .put("saved", System.currentTimeMillis())
        p.edit().putString("state", o.toString()).apply()
    }
}

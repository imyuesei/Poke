package com.monolith.poke

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Poke's little mind. Every expression lives in its two eyes: thick white rings that never
 * change shape. Faces are made by straight black bars that slide in and tilt over each ring,
 * and blinks by two bars closing like eyelids. Everything moves on time-based springs, so it
 * is equally smooth at 60 or 120 frames a second.
 *
 * Faces: round (curious), happy ∩ ∩, content (bars tilted in from the top), shy (tilted out),
 * startled (wide), grumpy (steep brows), sad ∪ ∪, a wink, a head-tilt, and asleep (a thin
 * sliver that breathes).
 */
class Pet(private val c: Context) {
    /** Which bar sits over each eye. */
    enum class Shape { RING, HAPPY, CONTENT, SHY, GRUMPY, SAD, CLOSED }

    /**
     * A value that springs towards its target. [ratio] < 1 overshoots (bounce).
     * Velocities are in units per second.
     */
    class Spring(var x: Float, stiffness: Float, ratio: Float) {
        var v = 0f
        var t = x
        private val k = stiffness
        private val damp = 2f * ratio * sqrt(stiffness)
        fun step(dt: Float) {
            v += (k * (t - x) - damp * v) * dt
            x += v * dt
        }
        fun stepAngle(dt: Float) {
            var d = (t - x) % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            v += (k * d - damp * v) * dt
            x += v * dt
        }
        fun snap(value: Float) {
            x = value
            t = value
            v = 0f
        }
        val moving get() = abs(v) > 0.02f || abs(t - x) > 0.002f
    }

    /** One eye: its bar (angle + distance from center, in ring radii), size and offset. */
    class Eye {
        var shape = Shape.RING
        val barAngle = Spring(90f, 140f, 0.9f)
        val barDist = Spring(BAR_OFF, 170f, 0.85f)
        val scale = Spring(1f, 380f, 0.5f)
        val dx = Spring(0f, 260f, 0.62f)
        val dy = Spring(0f, 260f, 0.62f)
        var lid = 0f // 0 open, 1 shut
        val springs get() = listOf(barDist, scale, dx, dy)
    }

    val eyes = arrayOf(Eye(), Eye())
    /** Overall brightness (dims and breathes while asleep). */
    var glow = 1f
        private set

    private enum class Mood { IDLE, HAPPY, LOVE, CONTENT, STARTLED, GRUMPY, SAD, ASLEEP }
    private var mood = Mood.IDLE
    private var moodUntil = 0L
    private val pokes = mutableListOf<Long>()

    // Idle life: little things Poke does on its own.
    private var nextAct = now() + 1800
    private var actEnd = 0L
    private var act = ""
    private var blinkAt = -1L
    private var blinkEye = -1 // -1 both, 0 / 1 one eye
    private var gazeX = 0f
    private var gazeY = 0f
    private var last = 0L

    private fun now() = SystemClock.uptimeMillis()
    private val prefs get() = c.getSharedPreferences("poke", Context.MODE_PRIVATE)
    private fun remember() = prefs.edit().putLong("last_poke", System.currentTimeMillis()).apply()
    private fun idleFor() = System.currentTimeMillis() - prefs.getLong("last_poke", System.currentTimeMillis())

    private fun night(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= 23 || h < 7
    }

    // ------------------------------------------------------------- poking

    /** A poke: happy (grumpy if poked over and over; startled awake if asleep). */
    fun poke() {
        remember()
        val t = now()
        pokes.add(t)
        pokes.removeAll { t - it > 2500 }
        when {
            mood == Mood.ASLEEP -> become(Mood.STARTLED, 900)
            pokes.size >= 4 -> become(Mood.GRUMPY, 1300)
            else -> become(Mood.HAPPY, 1300)
        }
        // A little hop.
        for (e in eyes) e.dy.v -= 9f
    }

    /** Two quick pokes: startled. */
    fun doublePoke() {
        remember()
        become(Mood.STARTLED, 1000)
    }

    /** Held: shy and pleased while held, then content. */
    fun pet(on: Boolean) {
        if (on) {
            remember()
            become(Mood.LOVE, Long.MAX_VALUE / 4)
        } else if (mood == Mood.LOVE) {
            become(Mood.CONTENT, 1400)
        }
    }

    private fun become(m: Mood, ms: Long) {
        mood = m
        moodUntil = now() + ms
        act = ""
        gazeX = 0f
        gazeY = 0f
        val s = when (m) {
            Mood.HAPPY -> Shape.HAPPY
            Mood.LOVE -> Shape.SHY
            Mood.CONTENT -> Shape.CONTENT
            Mood.GRUMPY -> Shape.GRUMPY
            Mood.SAD -> Shape.SAD
            Mood.ASLEEP -> Shape.CLOSED
            else -> Shape.RING
        }
        setShape(0, s)
        setShape(1, s)
        if (m == Mood.STARTLED) for (e in eyes) e.scale.v += 6f
        if (m == Mood.GRUMPY) for ((i, e) in eyes.withIndex()) e.dx.v += if (i == 0) 6f else -6f
    }

    /** Points an eye's bar at a new face; it slides in, or turns from the last face. */
    private fun setShape(i: Int, s: Shape) {
        val e = eyes[i]
        e.shape = s
        val (angle, dist) = when (s) {
            Shape.RING -> Float.NaN to BAR_OFF
            Shape.HAPPY -> 90f to 0.02f      // ∩  bar along the bottom
            Shape.CONTENT -> -45f to 0.48f   // bar tilted in from the upper inner corner
            Shape.SHY -> 225f to 0.48f       // bar tilted in from the upper outer corner
            Shape.GRUMPY -> -68f to 0.06f    // steep brow sloping down to the camera
            Shape.SAD -> 270f to 0.05f       // ∪  bar along the top
            Shape.CLOSED -> 270f to -0.42f   // only a thin sliver left at the bottom
        }
        if (!angle.isNaN()) {
            val a = if (i == 0) angle else 180f - angle
            // a bar that's out of sight jumps to its angle and slides in from there
            if (e.barDist.x > 1.1f) e.barAngle.snap(a) else e.barAngle.t = a
        }
        e.barDist.t = dist
    }

    // ------------------------------------------------------------- living

    /** Moves everything a step; returns true while something is still changing. */
    fun frame(): Boolean {
        val t = now()
        val dt = if (last == 0L) 0.016f else min(0.05f, (t - last) / 1000f)
        last = t

        // The second blink of a double blink, and the end of a happy bounce.
        if (blinkLater in 1..t) {
            blinkLater = -1
            blink(-1)
        }
        if (afterBounce && act.isEmpty()) {
            afterBounce = false
            setShape(0, Shape.RING)
            setShape(1, Shape.RING)
        }
        // Moods wear off.
        if (mood != Mood.IDLE && mood != Mood.ASLEEP && t > moodUntil) become(Mood.IDLE, 0)
        // Late at night a quiet Poke falls asleep; morning (or a poke) wakes it.
        if (mood == Mood.IDLE && night() && idleFor() > 90_000) become(Mood.ASLEEP, 0)
        if (mood == Mood.ASLEEP && !night()) become(Mood.IDLE, 0)
        if (mood == Mood.IDLE && idleFor() > 6 * 3600_000L && t > nextAct && Random.nextFloat() < 0.3f) become(Mood.SAD, 2600)

        if (mood == Mood.IDLE) idle(t)

        // Targets for this frame.
        for ((i, e) in eyes.withIndex()) {
            var scale = 1f
            var dx = gazeX * 0.5f
            var dy = gazeY * 0.32f
            when (mood) {
                Mood.STARTLED -> scale = 1.22f
                Mood.HAPPY -> dy -= 0.14f
                Mood.LOVE -> { scale = 1f + 0.06f * sin(t / 260.0 * PI).toFloat(); dy += 0.1f }
                Mood.CONTENT -> dx += 0.12f * sin(t / 900.0 * PI).toFloat()
                Mood.SAD -> { dy += 0.22f; scale = 0.92f }
                Mood.GRUMPY -> { scale = 0.95f; dx += 0.06f * sin(t / 45.0).toFloat() }
                Mood.ASLEEP -> dy += 0.12f
                else -> {}
            }
            if (act == "tilt") { dy += if (i == 0) -0.2f else 0.2f; scale *= if (i == 0) 1.08f else 0.94f }
            if (act == "bounce") dy += -0.16f * abs(sin((t - actEnd + 900) / 150.0 * PI).toFloat())
            e.scale.t = scale
            e.dx.t = dx
            e.dy.t = dy

            // Blink: two eyelid bars close fast, hold, and open a little slower.
            e.lid = 0f
            if (blinkAt >= 0 && (blinkEye < 0 || blinkEye == i) && e.shape != Shape.CLOSED) {
                val p = (t - blinkAt).toFloat()
                e.lid = when {
                    p < 80f -> smooth(p / 80f)
                    p < 120f -> 1f
                    p < 250f -> 1f - smooth((p - 120f) / 130f)
                    else -> 0f
                }
            }
        }
        if (blinkAt >= 0 && t - blinkAt > 250) blinkAt = -1

        // Springs, in small steps so they stay stable on slow frames.
        val steps = max(1, ceil(dt / 0.008f).toInt())
        val h = dt / steps
        repeat(steps) {
            for (e in eyes) {
                e.barAngle.stepAngle(h)
                e.barDist.step(h)
                e.scale.step(h)
                e.dx.step(h)
                e.dy.step(h)
            }
        }

        var moving = false
        for (e in eyes) if (e.springs.any { it.moving } || e.barAngle.moving) moving = true
        glow = if (mood == Mood.ASLEEP) 0.55f + 0.3f * sin(t / 1100.0 * PI).toFloat() else min(1f, glow + dt * 3f)
        return moving || mood == Mood.ASLEEP || mood == Mood.LOVE || mood == Mood.GRUMPY ||
            mood == Mood.CONTENT || act.isNotEmpty() || blinkAt >= 0
    }

    /** Little things Poke does when left alone. */
    private fun idle(t: Long) {
        if (act.isNotEmpty() && t > actEnd) {
            if (act == "wink") setShape(winkEye, Shape.RING)
            act = ""
            gazeX = 0f
            gazeY = 0f
            nextAct = t + 1500 + Random.nextLong(3500)
        }
        if (act.isNotEmpty() || t < nextAct) return
        val r = Random.nextFloat()
        when {
            r < 0.36f -> { blink(-1); nextAct = t + 1800 + Random.nextLong(3200) }
            r < 0.44f -> { blink(-1); blinkLater = t + 330; nextAct = t + 2200 } // double blink
            r < 0.72f -> { // a glance somewhere, held, then back
                act = "glance"
                gazeX = (Random.nextFloat() * 2f - 1f).let { if (abs(it) < 0.4f) it * 2f else it }.coerceIn(-1f, 1f)
                gazeY = (Random.nextFloat() * 2f - 1f) * 0.7f
                actEnd = t + 700 + Random.nextLong(1300)
            }
            r < 0.8f -> { act = "tilt"; gazeX = if (Random.nextBoolean()) 0.3f else -0.3f; actEnd = t + 1300 + Random.nextLong(900) }
            r < 0.86f -> { act = "wink"; winkEye = Random.nextInt(2); setShape(winkEye, Shape.HAPPY); actEnd = t + 700 }
            r < 0.93f -> { act = "pleased"; setShape(0, Shape.CONTENT); setShape(1, Shape.CONTENT); actEnd = t + 1600; afterBounce = true }
            else -> { act = "bounce"; setShape(0, Shape.HAPPY); setShape(1, Shape.HAPPY); actEnd = t + 900; afterBounce = true }
        }
    }

    private var winkEye = 0
    private var blinkLater = -1L
    private var afterBounce = false

    private fun blink(eye: Int) {
        blinkAt = now()
        blinkEye = eye
    }

    /** Milliseconds until Poke next does something on its own (so idle frames can rest). */
    fun quietFor(): Long {
        val t = now()
        val until = if (blinkLater > 0) blinkLater else nextAct
        return max(16L, until - t)
    }

    companion object {
        /** Bar distance at which it is fully off the ring. */
        const val BAR_OFF = 1.35f

        private fun smooth(x: Float): Float {
            val v = x.coerceIn(0f, 1f)
            return v * v * (3 - 2 * v)
        }
    }
}

/**
 * Draws Poke's two ring eyes. Each ring is cut by its bar and eyelids with a path
 * difference, so the cut edges are crisp and anti-aliased on any background.
 */
class Eyes(@Suppress("UNUSED_PARAMETER") context: Context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val ring = Path()
    private val mask = Path()
    private val bar = Path()
    private val out = Path()
    private val m = Matrix()

    /** Both eyes, centred at ([lx], [cy]) and ([rx], [cy]), radius [r]. */
    fun draw(canvas: Canvas, pet: Pet, lx: Float, rx: Float, cy: Float, r: Float) {
        fill.alpha = (255 * pet.glow).toInt().coerceIn(0, 255)
        eye(canvas, pet.eyes[0], lx, cy, r)
        eye(canvas, pet.eyes[1], rx, cy, r)
    }

    private fun eye(canvas: Canvas, e: Pet.Eye, x0: Float, y0: Float, r: Float) {
        val x = x0 + e.dx.x * r
        val y = y0 + e.dy.x * r
        val outer = r * 1.15f * e.scale.x.coerceIn(0.5f, 1.6f)
        val stroke = outer * 0.44f

        ring.reset()
        ring.addCircle(x, y, outer, Path.Direction.CW)
        ring.addCircle(x, y, outer - stroke, Path.Direction.CCW)

        mask.reset()
        val d = e.barDist.x
        if (d < Pet.BAR_OFF - 0.01f) {
            bar.reset()
            bar.addRect(x + d * outer, y - 2 * outer, x + 3 * outer, y + 2 * outer, Path.Direction.CW)
            m.setRotate(e.barAngle.x, x, y)
            bar.transform(m)
            mask.addPath(bar)
        }
        if (e.lid > 0.001f) {
            val box = outer + 2f
            val edge = box * (1f - e.lid)
            mask.addRect(x - box, y - box - 2f, x + box, y - edge, Path.Direction.CW)
            mask.addRect(x - box, y + edge, x + box, y + box + 2f, Path.Direction.CW)
        }

        if (mask.isEmpty) {
            canvas.drawPath(ring, fill)
        } else if (out.op(ring, mask, Path.Op.DIFFERENCE)) {
            canvas.drawPath(out, fill)
        }
    }
}

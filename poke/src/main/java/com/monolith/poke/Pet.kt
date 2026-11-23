package com.monolith.poke

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Poke's eyes. Mood and decisions live in [Mind]; this follows them. The eyes are thick white rings that never
 * change shape. Faces are made by straight black bars that slide in and tilt over each ring,
 * and blinks by two bars closing like eyelids. Everything moves on time-based springs, so it
 * is equally smooth at 60 or 120 frames a second.
 *
 * Faces: round (curious), happy ∩ ∩, content (bars tilted in from the top), shy (tilted out),
 * startled (wide), grumpy (steep brows), sad ∪ ∪, a wink, a head-tilt, and asleep (a thin
 * sliver that breathes).
 */
class Pet(@Suppress("UNUSED_PARAMETER") c: Context) {
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

    // What this pair of eyes is currently acting out (from Mind).
    private var seenSerial = -1
    private var act = Mind.Act.NONE
    private var actStart = 0L
    private var actEnd = 0L
    private var gazeX = 0f
    private var gazeY = 0f
    private var winkEye = 0
    private var blinkAt = -1L
    private var blinkEye = -1
    private var blinkLater = -1L
    private var nextOwnBlink = now() + 2000
    private var last = 0L

    private fun now() = SystemClock.uptimeMillis()

    // ------------------------------------------------------------- touch (forwarded to the mind)

    fun poke() = Mind.onEvent(Mind.Event.POKE)

    fun doublePoke() = Mind.onEvent(Mind.Event.DOUBLE_POKE)

    fun pet(on: Boolean) = Mind.onEvent(if (on) Mind.Event.HOLD_START else Mind.Event.HOLD_END)

    /** Points an eye's bar at a new face; it slides in, or turns from the last face. */
    private fun setShape(i: Int, s: Shape) {
        val e = eyes[i]
        if (e.shape == s) return
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

    private fun faceShape(f: Mind.Face) = when (f) {
        Mind.Face.HAPPY -> Shape.HAPPY
        Mind.Face.CONTENT -> Shape.CONTENT
        Mind.Face.SHY -> Shape.SHY
        Mind.Face.GRUMPY -> Shape.GRUMPY
        Mind.Face.SAD -> Shape.SAD
        Mind.Face.ASLEEP -> Shape.CLOSED
        else -> Shape.RING
    }

    // ------------------------------------------------------------- living

    /** Moves everything a step; returns true while something is still changing. */
    fun frame(): Boolean {
        val t = now()
        val dt = if (last == 0L) 0.016f else min(0.05f, (t - last) / 1000f)
        last = t
        val face = Mind.face

        // A new act from the mind.
        if (Mind.actSerial != seenSerial) {
            seenSerial = Mind.actSerial
            startAct(Mind.act, Mind.actX, Mind.actY, t)
        }
        if (act != Mind.Act.NONE && t > actEnd) {
            act = Mind.Act.NONE
            gazeX = 0f
            gazeY = 0f
        }
        // Little blinks of its own, between the mind's decisions.
        if (face != Mind.Face.ASLEEP && t > nextOwnBlink) {
            blink(-1)
            nextOwnBlink = t + 2600 + Random.nextLong(3800)
        }
        if (blinkLater in 1..t) {
            blinkLater = -1
            blink(-1)
        }

        // Shapes: the face, or the act's own (bounce = happy, wink = one happy eye).
        for (i in 0..1) {
            var s = faceShape(face)
            if (act == Mind.Act.BOUNCE && face == Mind.Face.ROUND) s = Shape.HAPPY
            if (act == Mind.Act.WINK && i == winkEye && face == Mind.Face.ROUND) s = Shape.HAPPY
            setShape(i, s)
        }

        for ((i, e) in eyes.withIndex()) {
            var scale = 1f
            var dx = gazeX * 0.5f
            var dy = gazeY * 0.32f
            when (face) {
                Mind.Face.STARTLED -> scale = 1.22f
                Mind.Face.HAPPY -> dy -= 0.14f
                Mind.Face.SHY -> { scale = 1f + 0.06f * sin(t / 260.0 * PI).toFloat(); dy += 0.1f }
                Mind.Face.CONTENT -> dx += 0.12f * sin(t / 900.0 * PI).toFloat()
                Mind.Face.SAD -> { dy += 0.22f; scale = 0.92f }
                Mind.Face.GRUMPY -> { scale = 0.95f; dx += 0.06f * sin(t / 45.0).toFloat() }
                Mind.Face.ASLEEP -> dy += 0.12f
                else -> {}
            }
            // ears open: eyes a little wider, looking at you
            if (Mind.listening && face == Mind.Face.ROUND) {
                scale *= 1.1f
                dx *= 0.3f
                dy *= 0.3f
            }
            when (act) {
                Mind.Act.TILT -> { dy += if (i == 0) -0.2f else 0.2f; scale *= if (i == 0) 1.08f else 0.94f }
                Mind.Act.BOUNCE -> dy += -0.16f * abs(sin((t - actStart) / 150.0 * PI).toFloat())
                Mind.Act.LOOK -> scale *= 1.1f
                else -> {}
            }
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
        glow = if (face == Mind.Face.ASLEEP) 0.55f + 0.3f * sin(t / 1100.0 * PI).toFloat() else min(1f, glow + dt * 3f)
        return moving || Mind.listening || face == Mind.Face.ASLEEP || face == Mind.Face.SHY || face == Mind.Face.GRUMPY ||
            face == Mind.Face.CONTENT || act != Mind.Act.NONE || blinkAt >= 0
    }

    private fun startAct(a: Mind.Act, x: Float, y: Float, t: Long) {
        actStart = t
        when (a) {
            Mind.Act.GLANCE -> { act = a; gazeX = x; gazeY = y; actEnd = t + 700 + Random.nextLong(1300) }
            Mind.Act.TILT -> { act = a; gazeX = if (Random.nextBoolean()) 0.3f else -0.3f; actEnd = t + 1300 + Random.nextLong(900) }
            Mind.Act.BOUNCE -> { act = a; actEnd = t + 900 }
            Mind.Act.WINK -> { act = a; winkEye = Random.nextInt(2); actEnd = t + 700 }
            Mind.Act.LOOK -> { act = a; gazeX = 0f; gazeY = 0f; actEnd = t + 1000 }
            Mind.Act.HOP -> for (e in eyes) e.dy.v -= 9f
            Mind.Act.BLINK -> blink(-1)
            Mind.Act.DOUBLE_BLINK -> { blink(-1); blinkLater = t + 330 }
            Mind.Act.NONE -> { act = Mind.Act.NONE; gazeX = 0f; gazeY = 0f }
        }
    }

    private fun blink(eye: Int) {
        blinkAt = now()
        blinkEye = eye
    }

    /** Milliseconds until these eyes next do something on their own (so idle frames can rest). */
    fun quietFor(): Long {
        val t = now()
        val until = if (blinkLater > 0) min(blinkLater, nextOwnBlink) else nextOwnBlink
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

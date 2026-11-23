package com.monolith.poke

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Poke's home: a soft blue sky over a dark floor, with Poke sitting on it (poke it here too).
 * The button lets Poke into your island (over every app) or sends it home.
 */
class PokeActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var button: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = SKY_TOP
        window.navigationBarColor = FLOOR
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = FrameLayout(this)
        root.addView(Scene(this), FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        root.addView(TextView(this).apply {
            text = "poke"
            textSize = 44f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            letterSpacing = 0.02f
            setShadowLayer(18f, 0f, 2f, 0x33000000)
            // hidden: hold the title to open Poke's brain lab
            setOnLongClickListener {
                startActivity(Intent(this@PokeActivity, com.monolith.poke.brain.BrainActivity::class.java))
                true
            }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(72)
        })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(0x99FFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), dp(16))
        }
        button = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFF0E0F11.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(14), dp(28), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFEFF6FA.toInt())
                cornerRadius = 30 * d
            }
            setOnClickListener { toggle() }
        }
        bottom.addView(status)
        bottom.addView(button)
        root.addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = dp(64)
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // Back from the "display over other apps" page with it allowed: let Poke in.
        if (PokeService.enabled(this) && Settings.canDrawOverlays(this)) PokeService.start(this)
        refresh()
        Updater.check(this, this)
        // Poke's ears: ask for the microphone once, and wake the ears service while we're in front
        val prefs = getSharedPreferences("poke", MODE_PRIVATE)
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!prefs.getBoolean("asked_mic", false)) {
                prefs.edit().putBoolean("asked_mic", true).apply()
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 4)
            }
        } else {
            com.monolith.poke.ears.EarsService.ensure(this)
        }
    }

    private fun isOn() = PokeService.enabled(this) &&
        (PokeAccessibilityService.running || Settings.canDrawOverlays(this))

    private fun refresh() {
        val on = isOn()
        status.text = if (on) "Poke lives in your island now.  Tap it, tap twice, or hold it."
        else "Poke can live in the island around your camera, over every app."
        button.text = if (on) "bring poke home" else "let poke into your island"
    }

    private var askedTapping = false

    private fun toggle() {
        if (isOn()) {
            PokeService.setEnabled(this, false)
        } else if (!PokeAccessibilityService.running && !askedTapping) {
            // The status bar sits over normal overlays and catches taps at the top of the screen.
            // The accessibility layer sits above it, so that is what makes Poke pokeable.
            askedTapping = true
            android.app.AlertDialog.Builder(this)
                .setTitle("Make Poke pokeable")
                .setMessage(
                    "Android's status bar catches taps at the top of the screen. Turn on Poke in " +
                        "Accessibility so you can tap, double-tap and hold it.\n\n" +
                        "If it says \"Restricted setting\": App info → ⋮ → Allow restricted settings.",
                )
                .setPositiveButton("Open settings") { _, _ ->
                    getSharedPreferences("poke", MODE_PRIVATE).edit().putBoolean("on", true).apply()
                    try {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } catch (_: Exception) {
                    }
                }
                .setNegativeButton("Not now") { _, _ -> toggle() }
                .show()
            return
        } else {
            PokeService.setEnabled(this, true)
            if (!Settings.canDrawOverlays(this)) {
                // Poke needs "display over other apps" to sit on top of everything.
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                }
            }
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        refresh()
    }

    companion object {
        const val SKY_TOP = 0xFF5D84A1.toInt()
        const val SKY_MID = 0xFF9CC9E2.toInt()
        const val HORIZON = 0xFFDDF4FC.toInt()
        const val FLOOR = 0xFF0F1012.toInt()
    }
}

/** The scene: sky, horizon glow, floor, and Poke's glossy black face with its eyes. */
@SuppressLint("ViewConstructor")
class Scene(c: Context) : View(c) {
    private val pet = Pet(c)
    private val eyes = Eyes(c)
    private val d = resources.displayMetrics.density
    private val sky = Paint()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val floor = Paint().apply { color = PokeActivity.FLOOR }
    private val faceShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private var squish = 0f
    private var holding = false

    private val gestures = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            pet.poke()
            bump()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            pet.doublePoke()
            bump()
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            holding = true
            pet.pet(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    })

    private val loop = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            pet.frame()
            squish *= 0.85f
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Mind.attach(context)
        removeCallbacks(loop)
        post(loop)
    }

    override fun onDetachedFromWindow() {
        Mind.detach()
        super.onDetachedFromWindow()
    }

    private fun bump() {
        squish = 1f
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Only Poke itself is pokeable.
        if (e.actionMasked == MotionEvent.ACTION_DOWN && !box.contains(e.x, e.y)) return false
        gestures.onTouchEvent(e)
        if ((e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) && holding) {
            holding = false
            pet.pet(false)
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val horizon = h * 0.7f
        sky.shader = LinearGradient(
            0f, 0f, 0f, horizon,
            intArrayOf(PokeActivity.SKY_TOP, PokeActivity.SKY_MID, PokeActivity.HORIZON),
            floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP,
        )
        // A soft light behind Poke, brightest just above the floor.
        glow.shader = RadialGradient(w / 2f, horizon, w * 0.6f, intArrayOf(0xCCFFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val horizon = h * 0.7f
        canvas.drawRect(0f, 0f, w, horizon, sky)
        canvas.drawRect(0f, horizon - w * 0.6f, w, horizon, glow)
        canvas.drawRect(0f, horizon, w, h, floor)

        // Poke: a rounded black glass face resting on the floor (it dips a little when poked).
        val fw = w * 0.62f
        val fh = fw * 0.56f
        val sink = squish * 6 * d
        box.set((w - fw) / 2f, horizon - fh + sink, (w + fw) / 2f, horizon + sink * 0.2f)
        val r = fh * 0.46f
        faceShadow.shader = RadialGradient(w / 2f, horizon + 4 * d, fw * 0.6f, intArrayOf(0x88000000.toInt(), 0x00000000), null, Shader.TileMode.CLAMP)
        canvas.drawOval(box.left - fw * 0.1f, horizon - 10 * d, box.right + fw * 0.1f, horizon + 18 * d, faceShadow)
        face.shader = LinearGradient(0f, box.top, 0f, box.bottom, intArrayOf(0xFF1A1C20.toInt(), 0xFF050506.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(box, r, r, face)
        // Glass shine along the top edge.
        shine.shader = LinearGradient(0f, box.top, 0f, box.top + fh * 0.45f, intArrayOf(0x40B8D4E8, 0x00B8D4E8), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(box.left + fw * 0.06f, box.top + fh * 0.05f, box.right - fw * 0.06f, box.top + fh * 0.5f, r * 0.8f, r * 0.8f, shine)

        val er = fh * 0.17f
        eyes.draw(canvas, pet, box.left + fw * 0.3f, box.right - fw * 0.3f, box.top + fh * 0.5f, er)
    }
}

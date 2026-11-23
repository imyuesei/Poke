package com.monolith.poke

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.max

/** Keeps Poke in its island, over every app. */
class PokeService : Service() {
    companion object {
        @Volatile var running = false
        private const val CHANNEL = "poke"

        fun enabled(c: Context) = c.getSharedPreferences("poke", Context.MODE_PRIVATE).getBoolean("on", false)

        fun setEnabled(c: Context, on: Boolean) {
            c.getSharedPreferences("poke", Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
            if (on) start(c) else c.stopService(Intent(c, PokeService::class.java))
        }

        fun start(c: Context) {
            // with Accessibility on, the accessibility service hosts the island instead
            if (PokeAccessibilityService.running) return
            if (!Settings.canDrawOverlays(c)) return
            try {
                c.startForegroundService(Intent(c, PokeService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private var wm: WindowManager? = null
    private var island: Island? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private val updates = Updater.Schedule()

    override fun onCreate() {
        super.onCreate()
        updates.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Poke", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_poke)
            .setContentTitle("Poke is in your island")
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        if (PokeAccessibilityService.running || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (island == null) addIsland()
        running = true
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Turning the phone: the island moves to (or hides from) the new top edge.
        island?.let { v ->
            try {
                wm?.removeView(v)
            } catch (_: Exception) {
            }
        }
        island = null
        if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) addIsland()
    }

    private fun addIsland() {
        val w = getSystemService(WindowManager::class.java) ?: return
        wm = w
        island = IslandWindow.add(this, w, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        if (island == null) stopSelf()
    }

    override fun onDestroy() {
        running = false
        updates.stop()
        island?.let {
            it.stop()
            try {
                wm?.removeView(it)
            } catch (_: Exception) {
            }
        }
        island = null
        super.onDestroy()
    }
}

/** Adds the island window; [type] is an app overlay, or an accessibility overlay (tappable). */
object IslandWindow {
    fun add(c: Context, w: WindowManager, type: Int): Island? {
        val geo = Geometry.measure(c, w)
        val v = Island(c, geo)
        val params = WindowManager.LayoutParams(
            geo.width, geo.height + geo.bounce, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = geo.left
            y = 0
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        return try {
            w.addView(v, params)
            v
        } catch (_: Exception) {
            null
        }
    }

    fun remove(w: WindowManager?, v: Island?) {
        if (v == null) return
        v.stop()
        try {
            w?.removeView(v)
        } catch (_: Exception) {
        }
    }
}

/** Where the island goes: around the front camera, as wide as the camera plus an eye each side. */
class Geometry(val left: Int, val width: Int, val height: Int, val lensLeft: Float, val lensRight: Float, val bounce: Int) {
    companion object {
        fun measure(c: Context, wm: WindowManager): Geometry {
            val d = c.resources.displayMetrics.density
            val screenW = c.resources.displayMetrics.widthPixels
            val id = c.resources.getIdentifier("status_bar_height", "dimen", "android")
            val bar = if (id > 0) c.resources.getDimensionPixelSize(id) else (24 * d).toInt()
            var cut: Rect? = null
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    cut = wm.currentWindowMetrics.windowInsets.displayCutout?.boundingRects?.firstOrNull { it.top <= 1 && it.width() > 0 }
                } else if (Build.VERSION.SDK_INT >= 29) {
                    @Suppress("DEPRECATION")
                    cut = wm.defaultDisplay.cutout?.boundingRects?.firstOrNull { it.top <= 1 && it.width() > 0 }
                }
            } catch (_: Exception) {
            }
            val cx = cut?.exactCenterX() ?: (screenW / 2f)
            val lens = (cut?.width()?.toFloat() ?: (18 * d)) + 10 * d
            val h = max(max((cut?.bottom ?: 0) + (4 * d).toInt(), bar), (30 * d).toInt())
            val slot = 30 * d
            val gap = 8 * d
            val w = (lens + 2 * (slot + gap)).toInt()
            val left = (cx - w / 2f).toInt()
            return Geometry(left, w, h, cx - left - lens / 2f, cx - left + lens / 2f, (6 * d).toInt())
        }
    }
}

/**
 * The island itself: a black shape flush with the top edge (flat top, rounded bottom), like
 * Monolith's, with Poke's eyes either side of the camera. Tap = happy, two taps = startled,
 * hold = petted.
 */
@SuppressLint("ViewConstructor")
class Island(c: Context, private val geo: Geometry) : View(c) {
    private val pet = Pet(c)
    private val eyes = Eyes(c)
    private val d = resources.displayMetrics.density
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    private val shape = Path()
    private val box = RectF()
    private var squish = 0f
    private var running = true
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
            wake()
        }
    })

    init {
        isHapticFeedbackEnabled = true
    }

    private val mindChanged: () -> Unit = { post { wake() } }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Mind.attach(context)
        Mind.addListener(mindChanged)
        wake()
    }

    override fun onDetachedFromWindow() {
        Mind.removeListener(mindChanged)
        Mind.detach()
        super.onDetachedFromWindow()
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            val moving = pet.frame()
            squish *= 0.82f
            invalidate()
            // Smooth while something moves; otherwise sleep until Poke's next blink / glance.
            if (moving || squish > 0.01f) postOnAnimation(this) else postDelayed(this, pet.quietFor())
        }
    }

    private fun wake() {
        removeCallbacks(loop)
        post(loop)
    }

    private fun bump() {
        squish = 1f
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        wake()
    }

    fun stop() {
        running = false
        removeCallbacks(loop)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        if ((e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) && holding) {
            holding = false
            pet.pet(false)
            wake()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = geo.height.toFloat() + geo.bounce * squish // a little drop when poked
        val r = minOf(24 * d, h / 2f)
        shape.reset()
        box.set(0f, 0f, w, h)
        shape.addRoundRect(box, floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r), Path.Direction.CW)
        canvas.drawPath(shape, body)
        val er = minOf(geo.height * 0.3f, 10.5f * d)
        val lx = geo.lensLeft / 2f + 2 * d
        val rx = geo.lensRight + (w - geo.lensRight) / 2f - 2 * d
        eyes.draw(canvas, pet, lx, rx, h / 2f, er)
    }
}

/** Poke comes back after a restart (if it was on). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (PokeService.enabled(c)) PokeService.start(c)
    }
}

package com.monolith.poke

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Poke's own timers. Opening the clock app from the background is blocked on modern Android,
 * so Poke counts itself (its island keeps it running). When time is up it calls out with its
 * own "po-ke! po-ke!" (no ringtone), shows a silent notification, a startled face and a bubble.
 */
object Timers {
    private val main = Handler(Looper.getMainLooper())
    // a silent channel: the sound is Poke's own alarm call, not the phone's ringtone
    private const val CHANNEL = "poke_timers_quiet"
    private var nextId = 100

    fun start(c: Context, seconds: Int, label: String) {
        val app = c.applicationContext
        val id = nextId++
        main.postDelayed({ ring(app, id, label) }, seconds * 1000L)
    }

    private fun ring(c: Context, id: Int, label: String) {
        Mind.onEvent(Mind.Event.TIMER)
        Mind.say("Ding ding! $label done!")
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.deleteNotificationChannel("poke_timers") // the old one rang the ringtone
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Poke timers", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_poke)
            .setContentTitle("Time's up!")
            .setContentText("$label done")
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .build()
        try {
            nm.notify(id, n)
        } catch (_: SecurityException) {
        }
        try {
            c.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 120, 120), -1))
        } catch (_: Exception) {
        }
    }

    /** "2 minutes", "90 seconds", "1 hour and 5 minutes" -> a friendly label. */
    fun label(seconds: Int): String = when {
        seconds % 3600 == 0 -> "${seconds / 3600} hour"
        seconds >= 60 && seconds % 60 == 0 -> "${seconds / 60} minute"
        seconds >= 60 -> "${seconds / 60} minute ${seconds % 60} second"
        else -> "$seconds second"
    }
}

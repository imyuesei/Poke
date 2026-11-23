package com.monolith.poke

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Poke's own timers. Opening the clock app from the background is blocked on modern Android,
 * so Poke counts itself (its island keeps it running), then rings: a notification with the
 * alarm sound, a buzz, a startled face and a bubble.
 */
object Timers {
    private val main = Handler(Looper.getMainLooper())
    private const val CHANNEL = "poke_timers"
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
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Poke timers", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(sound, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                enableVibration(true)
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
            c.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300, 150, 300), -1))
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

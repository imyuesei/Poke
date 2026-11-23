package com.monolith.poke.ears

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.monolith.poke.R
import kotlin.concurrent.thread

/**
 * Keeps Poke's ears allowed to use the microphone: Android only lets an app record in the
 * background from a foreground service of the microphone type. It runs the wake-word loop
 * while the screen is on (if the wake word is turned on), and otherwise just stays ready
 * for hold-to-talk. The microphone is only open while it is actually listening.
 */
class EarsService : Service() {
    companion object {
        private const val CHANNEL = "ears"
        private fun prefs(c: Context) = c.getSharedPreferences("poke_ears", Context.MODE_PRIVATE)

        fun wakeWordOn(c: Context) = prefs(c).getBoolean("wake_word", false)

        fun setWakeWord(c: Context, on: Boolean) {
            prefs(c).edit().putBoolean("wake_word", on).apply()
            if (on) ensure(c) else c.stopService(Intent(c, EarsService::class.java))
        }

        /** Starts the service if it isn't running (may be refused in the background). */
        fun ensure(c: Context): Boolean = try {
            c.startForegroundService(Intent(c, EarsService::class.java))
            true
        } catch (_: Exception) {
            false
        }
    }

    @Volatile private var alive = false
    private var loop: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Poke's ears", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_poke)
            .setContentTitle(if (wakeWordOn(this)) "Poke listens for its name" else "Poke can hear you")
            .setContentText("Only while the screen is on. Nothing leaves your phone.")
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(2, n)
        } catch (_: Exception) {
            // Android refused a microphone service right now (app in the background)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!alive) {
            alive = true
            loop = thread(name = "poke-ears-wake") {
                val pm = getSystemService(PowerManager::class.java)
                while (alive) {
                    if (wakeWordOn(this) && (pm == null || pm.isInteractive)) {
                        Ears.wakeLoop(this) { alive && wakeWordOn(this) && (pm == null || pm.isInteractive) }
                    }
                    Thread.sleep(500)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }
}

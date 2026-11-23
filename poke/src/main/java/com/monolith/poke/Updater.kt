package com.monolith.poke

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * The app keeps itself up to date from the public GitHub releases of imyuesei/Poke.
 * It checks when the app opens, downloads a newer poke.apk and installs it over itself.
 * Android asks you to confirm the first update;
 * after that (Android 12+) the app is the installer of record and updates quietly.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/imyuesei/Poke/releases/latest"
    private const val CHANNEL = "updates"
    private const val NOTIF_ID = 7

    @Volatile private var busy = false
    private val main = Handler(Looper.getMainLooper())

    /** Checks for an update in the background. [activity] lets it ask for permission directly. */
    fun check(c: Context, activity: Activity? = null) {
        if (busy) return
        busy = true
        val app = c.applicationContext
        thread(name = "poke-update") {
            try {
                run(app, activity)
            } catch (_: Exception) {
            } finally {
                busy = false
            }
        }
    }

    private fun run(c: Context, activity: Activity?) {
        val json = JSONObject(get(LATEST) ?: return)
        val code = json.optString("tag_name").filter { it.isDigit() }.toLongOrNull() ?: return
        if (code <= installedCode(c)) return
        val assets = json.optJSONArray("assets") ?: return
        var url: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == "poke.apk") url = a.optString("browser_download_url")
        }
        if (url.isNullOrEmpty()) return

        if (!c.packageManager.canRequestPackageInstalls()) {
            askForPermission(c, activity)
            return
        }
        val file = File(c.cacheDir, "update.apk")
        if (!download(url, file)) return
        install(c, file)
    }

    private fun installedCode(c: Context): Long {
        val info = c.packageManager.getPackageInfo(c.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "NeedleChat-Updater")
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, out: File): Boolean {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "NeedleChat-Updater")
            if (conn.responseCode != 200) return false
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            out.length() > 0
        } finally {
            conn.disconnect()
        }
    }

    private fun install(c: Context, apk: File) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(c.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("poke.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val done = PendingIntent.getBroadcast(c, id, Intent(c, UpdateReceiver::class.java), flags)
            session.commit(done.intentSender)
        }
    }

    private fun askForPermission(c: Context, activity: Activity?) {
        val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
        if (activity != null) {
            main.post {
                if (activity.isFinishing) return@post
                AlertDialog.Builder(activity)
                    .setTitle("An update is ready")
                    .setMessage("Allow installing updates, and the app will keep itself up to date.")
                    .setPositiveButton("Allow") { _, _ ->
                        try {
                            activity.startActivity(settings)
                        } catch (_: Exception) {
                        }
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        } else {
            notify(c, "An update is ready", "Tap to allow automatic updates.", settings)
        }
    }

    internal fun notify(c: Context, title: String, text: String, target: Intent) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(c, NOTIF_ID, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIF_ID, n)
        } catch (_: SecurityException) {
        }
    }
}

/** Hears back from the installer; if Android wants a confirmation, shows it. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        val confirm: Intent = (if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }) ?: return
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            c.startActivity(confirm)
        } catch (_: Exception) {
            Updater.notify(c, "Update ready", "Tap to install the update.", confirm)
        }
    }
}

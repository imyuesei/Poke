package com.monolith.poke

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Hosts Poke's island in an accessibility overlay. That layer sits above the status bar,
 * which is what lets the island be tapped, double-tapped and held at the top of the screen.
 * It reads nothing from the screen.
 */
class PokeAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var running = false
    }

    private var wm: WindowManager? = null
    private var island: Island? = null
    private val updates = Updater.Schedule()
    private val prefs get() = getSharedPreferences("poke", Context.MODE_PRIVATE)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "on") sync()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        wm = getSystemService(WindowManager::class.java)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        // the plain overlay isn't needed any more
        stopService(Intent(this, PokeService::class.java))
        prefs.edit().putBoolean("on", true).apply()
        sync()
        updates.start(this)
    }

    private fun sync() {
        val want = PokeService.enabled(this) &&
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        if (want && island == null) {
            val w = wm ?: return
            island = IslandWindow.add(this, w, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        } else if (!want && island != null) {
            IslandWindow.remove(wm, island)
            island = null
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Turning the phone: rebuild the island for the new top edge (or hide it).
        IslandWindow.remove(wm, island)
        island = null
        sync()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        updates.stop()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        IslandWindow.remove(wm, island)
        island = null
        // Accessibility turned off: fall back to the plain overlay if Poke is still on.
        if (PokeService.enabled(this)) PokeService.start(this)
        super.onDestroy()
    }
}

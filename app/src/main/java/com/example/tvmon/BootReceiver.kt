package com.example.tvmon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.example.tvmon.prefs.Prefs

/**
 * Re-shows the overlay after a reboot when both "start on boot" and "show
 * overlay" were on.
 *
 * Android 8+ drops plain background startService calls, so this starts
 * OverlayService as a foreground service; BOOT_COMPLETED (and holding the
 * overlay permission) exempts the app from the background-start limits.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        // Skip when the overlay permission is gone: the service would only stop again.
        if (prefs.startOnBoot && prefs.overlayOn && Settings.canDrawOverlays(context)) {
            runCatching { OverlayService.start(context) }
        }
    }
}

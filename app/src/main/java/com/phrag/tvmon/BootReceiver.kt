package com.phrag.tvmon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phrag.tvmon.prefs.Prefs

/**
 * Re-shows the overlay after a reboot when both "start on boot" and "show
 * overlay" were on.
 *
 * CAVEAT: on Android 8+ background service starts are limited. A plain
 * startService here can be dropped by the system; the robust fix is to make
 * OverlayService a foreground service (with a low-priority notification) and use
 * startForegroundService. Left as-is for the scaffold — see PROJECT.md TODO.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (prefs.startOnBoot && prefs.overlayOn) {
            runCatching {
                context.startService(Intent(context, OverlayService::class.java))
            }
        }
    }
}

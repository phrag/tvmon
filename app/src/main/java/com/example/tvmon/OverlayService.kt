package com.example.tvmon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.example.tvmon.metric.MetricId
import com.example.tvmon.metric.MetricSource
import com.example.tvmon.metric.NetMonitor
import com.example.tvmon.metric.allSources
import com.example.tvmon.nativebridge.SysCore
import com.example.tvmon.prefs.Prefs

/**
 * A TYPE_APPLICATION_OVERLAY window that stacks one tile per enabled metric and
 * refreshes once a second. Uses the "display over other apps" permission only —
 * no root, no Shizuku.
 *
 * Runs as a foreground service (low-importance notification) so the system
 * doesn't kill it in the background or refuse to start it at boot. One TextView
 * per tile is created up front; ticks only change text, colour and visibility.
 */
class OverlayService : Service() {

    companion object {
        @Volatile var running = false

        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 1000L

        /** Burn-in protection: shift the overlay a few dp this often. */
        private const val NUDGE_MS = 3 * 60 * 1000L
        /** Offsets (dp) cycled through; small enough not to wander off its corner. */
        private val NUDGE_STEPS = listOf(0 to 0, 4 to 2, 8 to 6, 4 to 8, 0 to 4, 6 to 0)

        private val WARN_COLOR = Color.parseColor("#FF5252")

        /** Start (or keep) the overlay. Safe from activities and receivers. */
        fun start(ctx: Context) {
            val i = Intent(ctx, OverlayService::class.java)
            ctx.startForegroundService(i)   // minSdk 26
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var net: NetMonitor
    private lateinit var sources: List<MetricSource>
    private val tiles = HashMap<MetricId, TextView>()
    private var container: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var lastBg = 0
    private var nudgeStep = 0
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val nudge = object : Runnable {
        override fun run() {
            nudgeStep = (nudgeStep + 1) % NUDGE_STEPS.size
            placeAt(nudgeStep)
            handler.postDelayed(this, NUDGE_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Must happen promptly after startForegroundService, before anything that can fail.
        goForeground()

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        net = NetMonitor(this).also { it.start() }
        sources = allSources(this, net)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
        }
        for (source in sources) {
            val tv = tileView()
            tiles[source.id] = tv
            root.addView(tv)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,   // API 26+
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        params = lp
        placeAt(0, apply = false)

        // Without the overlay permission (revoked, or a boot start before it was
        // granted) addView throws; there's nothing to show, so just stop.
        val added = runCatching { wm.addView(root, lp) }.isSuccess
        if (!added) {
            stopSelf()
            return
        }
        container = root
        running = true
        handler.post(tick)
        handler.postDelayed(nudge, NUDGE_MS)
    }

    // Sticky so the overlay comes back if the system kills it; not when it never got up.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        if (container != null) START_STICKY else START_NOT_STICKY

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_overlay), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_overlay)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun placeAt(step: Int, apply: Boolean = true) {
        val lp = params ?: return
        val (dx, dy) = NUDGE_STEPS[step]
        lp.x = dp(24 + dx)
        lp.y = dp(24 + dy)
        if (apply) container?.let { runCatching { wm.updateViewLayout(it, lp) } }
    }

    private fun render() {
        val root = container ?: return
        val native = SysCore.snapshot()
        val enabled = prefs.enabledIds()

        // Restyle from prefs (lets settings changes show live), but only touch
        // the drawable when the colour actually changed.
        val bg = (prefs.bgColor and 0x00FFFFFF) or (prefs.bgAlpha shl 24)
        if (bg != lastBg) {
            (root.background as? GradientDrawable)?.setColor(bg)
            lastBg = bg
        }
        val textColor = prefs.textColor

        for (source in sources) {
            val tv = tiles[source.id] ?: continue
            val lines = if (source.id in enabled) runCatching { source.sample(native) }.getOrNull() else null
            if (lines == null) {   // disabled or unavailable on this device
                if (tv.visibility != View.GONE) tv.visibility = View.GONE
                continue
            }
            val text = buildString {
                append(source.id.label)
                for (l in lines) { append('\n'); append("  "); append(l) }
            }
            if (tv.text.toString() != text) tv.text = text
            val color = if (source.warn) WARN_COLOR else textColor
            if (tv.currentTextColor != color) tv.setTextColor(color)
            if (tv.visibility != View.VISIBLE) tv.visibility = View.VISIBLE
        }
    }

    private fun tileView(): TextView = TextView(this).apply {
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setPadding(0, dp(2), 0, dp(4))
        visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(nudge)
        if (::net.isInitialized) net.stop()
        container?.let { runCatching { wm.removeView(it) } }
        container = null
        tiles.clear()
        running = false
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

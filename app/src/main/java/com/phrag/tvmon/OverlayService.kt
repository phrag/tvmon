package com.phrag.tvmon

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.phrag.tvmon.metric.MetricSource
import com.phrag.tvmon.metric.allSources
import com.phrag.tvmon.nativebridge.SysCore
import com.phrag.tvmon.prefs.Prefs

/**
 * A TYPE_APPLICATION_OVERLAY window that stacks one tile per enabled metric and
 * refreshes once a second. Uses the "display over other apps" permission only —
 * no root, no Shizuku.
 */
class OverlayService : Service() {

    companion object {
        @Volatile var running = false
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var sources: List<MetricSource>
    private var container: LinearLayout? = null
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        sources = allSources(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
        }
        container = root

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,   // API 26+
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(24); y = dp(24)
        }

        wm.addView(root, lp)
        running = true
        handler.post(tick)
    }

    private fun render() {
        val root = container ?: return
        val native = SysCore.snapshot()
        val enabled = prefs.enabledIds()

        // Restyle background from prefs each tick (cheap; lets settings changes show live).
        (root.background as? GradientDrawable)?.setColor(
            (prefs.bgColor and 0x00FFFFFF) or (prefs.bgAlpha shl 24)
        )

        root.removeAllViews()
        for (source in sources) {
            if (source.id !in enabled) continue
            val lines = source.sample(native) ?: continue   // skip unavailable metrics
            root.addView(tileView(source.id.label, lines))
        }
        Log.d("OverlayService", "render: enabled=$enabled views=${root.childCount}")
    }

    private fun tileView(title: String, lines: List<String>): TextView {
        val text = buildString {
            append(title)
            for (l in lines) { append('\n'); append("  "); append(l) }
        }
        return TextView(this).apply {
            setText(text)
            setTextColor(prefs.textColor)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), 0, dp(4))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tick)
        container?.let { runCatching { wm.removeView(it) } }
        container = null
        running = false
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

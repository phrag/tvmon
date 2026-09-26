package com.phrag.tvmon

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.phrag.tvmon.metric.MetricId
import com.phrag.tvmon.prefs.Prefs

/**
 * The launcher screen. Plain Views on purpose: D-pad focus traversal works out
 * of the box, no Compose/leanback dependency needed for a list of switches.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(24))
        }

        col.addView(header("tvmon"))

        // Master overlay toggle — requests the overlay permission on first enable.
        col.addView(switchRow("Show overlay", prefs.overlayOn) { on ->
            if (on && !canOverlay()) {
                requestOverlayPermission()
                return@switchRow false   // keep off until permission granted
            }
            prefs.overlayOn = on
            val svc = Intent(this, OverlayService::class.java)
            if (on) startService(svc) else stopService(svc)
            true
        })

        col.addView(sectionLabel("Metrics"))
        for (id in MetricId.entries) {
            col.addView(switchRow(id.label, prefs.isEnabled(id)) { on ->
                prefs.setEnabled(id, on); true
            })
        }

        col.addView(sectionLabel("Appearance"))
        col.addView(TextView(this).apply {
            text = "Background transparency"
            setTextColor(Color.LTGRAY)
        })
        col.addView(SeekBar(this).apply {
            max = 255
            progress = prefs.bgAlpha
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs.bgAlpha = p
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        col.addView(sectionLabel("System"))
        col.addView(switchRow("Start overlay on boot", prefs.startOnBoot) { on ->
            prefs.startOnBoot = on; true   // see BootReceiver + PROJECT.md caveat
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#111318"))
            addView(col)
        })
    }

    // ---- tiny view helpers ----

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 28f; setTextColor(Color.WHITE)
        setPadding(0, 0, 0, dp(16))
    }

    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t.uppercase(); textSize = 13f
        setTextColor(Color.parseColor("#4DB6AC"))
        setPadding(0, dp(20), 0, dp(8))
    }

    /**
     * A focusable row with a switch. `onToggle` returns the value the switch
     * should actually settle on (so a rejected enable can snap back to off).
     */
    private fun switchRow(label: String, initial: Boolean, onToggle: (Boolean) -> Boolean): Switch {
        return Switch(this).apply {
            text = label
            textSize = 18f
            setTextColor(Color.WHITE)
            isChecked = initial
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6); bottomMargin = dp(6) }
            setPadding(dp(8), dp(10), dp(8), dp(10))
            gravity = Gravity.CENTER_VERTICAL
            setOnCheckedChangeListener { _, checked ->
                val settled = onToggle(checked)
                if (settled != checked) isChecked = settled
            }
        }
    }

    private fun canOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

package com.example.tvmon.prefs

import android.content.Context
import android.graphics.Color
import com.example.tvmon.metric.MetricId

/** Simple SharedPreferences-backed settings. No external deps. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("tvmon", Context.MODE_PRIVATE)

    /** Whether the floating overlay should be showing. */
    var overlayOn: Boolean
        get() = sp.getBoolean(KEY_OVERLAY, false)
        set(v) = sp.edit().putBoolean(KEY_OVERLAY, v).apply()

    var startOnBoot: Boolean
        get() = sp.getBoolean(KEY_BOOT, false)
        set(v) = sp.edit().putBoolean(KEY_BOOT, v).apply()

    /** Overlay background alpha, 0..255. */
    var bgAlpha: Int
        get() = sp.getInt(KEY_ALPHA, 176)
        set(v) = sp.edit().putInt(KEY_ALPHA, v.coerceIn(0, 255)).apply()

    var textColor: Int
        get() = sp.getInt(KEY_TEXT, Color.WHITE)
        set(v) = sp.edit().putInt(KEY_TEXT, v).apply()

    var bgColor: Int
        get() = sp.getInt(KEY_BG, Color.BLACK)
        set(v) = sp.edit().putInt(KEY_BG, v).apply()

    fun isEnabled(id: MetricId): Boolean =
        sp.getBoolean(enabledKey(id), id in DEFAULT_ON)

    fun setEnabled(id: MetricId, on: Boolean) =
        sp.edit().putBoolean(enabledKey(id), on).apply()

    fun enabledIds(): Set<MetricId> = MetricId.entries.filter { isEnabled(it) }.toSet()

    private fun enabledKey(id: MetricId) = "metric_${id.name}"

    companion object {
        private const val KEY_OVERLAY = "overlay_on"
        private const val KEY_BOOT = "start_on_boot"
        private const val KEY_ALPHA = "bg_alpha"
        private const val KEY_TEXT = "text_color"
        private const val KEY_BG = "bg_color"

        // Sensible defaults: the things that always work unprivileged.
        private val DEFAULT_ON = setOf(
            MetricId.RESOLUTION, MetricId.RAM, MetricId.LOAD, MetricId.TEMP,
        )
    }
}

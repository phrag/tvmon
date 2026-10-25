package com.example.tvmon.metric

import com.example.tvmon.nativebridge.NativeSnapshot

/** Stable ids for tiles — used as SharedPreferences keys and in the UI. */
enum class MetricId(val label: String) {
    RESOLUTION("Video output"),
    RAM("RAM"),
    BATTERY("Battery"),
    VPN("VPN"),
    NETWORK("Network"),
    ADDRESSES("IP / DNS"),
    TRAFFIC("Traffic"),
    LOAD("System load"),
    TEMP("Temperatures"),
    UPTIME("Uptime"),
}

/**
 * One tile. `sample` returns the lines to show, or null when this metric has no
 * data on this device (e.g. no thermal zones, or the native lib is missing).
 * Returning null lets the overlay simply skip the tile instead of showing blanks.
 *
 * Framework sources ignore `native`; sources backed by the Rust core read it.
 * A single NativeSnapshot is taken per refresh tick and shared across sources.
 */
interface MetricSource {
    val id: MetricId
    fun sample(native: NativeSnapshot?): List<String>?

    /**
     * True when the latest sample should be drawn in the warning colour (e.g.
     * the VPN dropped). Read right after `sample`.
     */
    val warn: Boolean get() = false
}

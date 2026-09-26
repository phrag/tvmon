package com.phrag.tvmon.metric

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.view.Display
import com.phrag.tvmon.nativebridge.NativeSnapshot

/**
 * The full set of unprivileged sources. No root, no Shizuku — everything here
 * either uses a framework API or reads a world-readable kernel file via the
 * Rust core. See PROJECT.md for what's deliberately excluded.
 */
fun allSources(context: Context): List<MetricSource> = listOf(
    ResolutionSource(context),
    RamSource(context),
    BatterySource(context),
    WifiSource(context),
    TrafficSource(),
    LoadSource(),
    TempSource(),
    UptimeSource(),
    UpscaleSource(),
    VpnSource(context),
    PacketDropsSource(),
    LatencySource(),
)

// ---- framework-API sources ---------------------------------------------------

/** The headline tile: the actual negotiated HDMI output mode. */
class ResolutionSource(private val ctx: Context) : MetricSource {
    override val id = MetricId.RESOLUTION
    override fun sample(native: NativeSnapshot?): List<String> {
        val d = display() ?: return listOf("no display")
        val m = d.mode
        val lines = mutableListOf(
            "${m.physicalWidth}x${m.physicalHeight} @ ${"%.2f".format(d.refreshRate)}Hz"
        )
        lines.add(hdrLine(d))
        return lines
    }

    // Display#getHdrCapabilities() is the panel's static capability list — it
    // never changes based on what's currently playing, so it always reads the
    // same regardless of SDR/HDR content. Display#isHdr() (API 33+) reflects
    // what's actually being output right now; there's no equivalent below 33.
    private fun hdrLine(d: Display): String {
        val types = d.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (d.isHdr) "HDR: active (${hdrNames(types)})" else "HDR: inactive"
        } else {
            "HDR (panel): " + hdrNames(types)
        }
    }

    // Context#getDisplay() requires a UI-associated context on API 30+, which a
    // Service context isn't. DisplayManager#getDisplay(int) has no such
    // restriction and works the same from any context.
    private fun display(): Display? {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.getDisplay(Display.DEFAULT_DISPLAY)
    }

    private fun hdrNames(types: IntArray): String {
        if (types.isEmpty()) return "SDR"
        return types.joinToString(",") {
            when (it) { 1 -> "DV"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> "t$it" }
        }
    }
}

class RamSource(private val ctx: Context) : MetricSource {
    override val id = MetricId.RAM
    override fun sample(native: NativeSnapshot?): List<String> {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val mb = 1024L * 1024L
        return listOf("${(mi.totalMem - mi.availMem) / mb} / ${mi.totalMem / mb} MB")
    }
}

class BatterySource(private val ctx: Context) : MetricSource {
    override val id = MetricId.BATTERY
    override fun sample(native: NativeSnapshot?): List<String>? {
        val i: Intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) // tenths of °C
        // Many Shield models report no battery; skip the tile if so.
        val present = i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)
        if (!present && level < 0) return null
        val lines = mutableListOf<String>()
        if (level >= 0 && scale > 0) lines.add("${level * 100 / scale} %")
        if (mv > 0) lines.add("$mv mV")
        if (temp > 0) lines.add("${temp / 10.0} °C")
        return lines.ifEmpty { null }
    }
}

class WifiSource(private val ctx: Context) : MetricSource {
    override val id = MetricId.WIFI
    @Suppress("DEPRECATION")
    override fun sample(native: NativeSnapshot?): List<String>? {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        // Link speed + frequency need no location permission (unlike SSID/BSSID),
        // which keeps this tile permission-free. Some OEM builds still restrict
        // ACCESS_WIFI_STATE further via app ops, so fail soft rather than crash.
        val info = try {
            wm.connectionInfo
        } catch (e: SecurityException) {
            null
        } ?: return null
        val speed = info.linkSpeed        // Mbps, -1 if unknown
        val freq = info.frequency         // MHz (API 21+)
        if (speed < 0 && freq <= 0) return null
        val lines = mutableListOf<String>()
        if (speed >= 0) lines.add("$speed Mbps")
        if (freq > 0) lines.add(if (freq > 5000) "5 GHz" else "2.4 GHz")
        return lines.ifEmpty { null }
    }
}

/** Total rx/tx delta since the previous sample. */
class TrafficSource : MetricSource {
    override val id = MetricId.TRAFFIC
    private var lastRx = TrafficStats.getTotalRxBytes()
    private var lastTx = TrafficStats.getTotalTxBytes()

    override fun sample(native: NativeSnapshot?): List<String>? {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx == TrafficStats.UNSUPPORTED.toLong()) return null
        val dRx = (rx - lastRx).coerceAtLeast(0)
        val dTx = (tx - lastTx).coerceAtLeast(0)
        lastRx = rx; lastTx = tx
        return listOf("↓ ${rate(dRx)}", "↑ ${rate(dTx)}")
    }

    private fun rate(bytesPerSec: Long): String {
        val kb = bytesPerSec / 1024.0
        return if (kb < 1024) "%.0f KB/s".format(kb) else "%.1f MB/s".format(kb / 1024)
    }
}

// ---- native (Rust core) sources ---------------------------------------------

class LoadSource : MetricSource {
    override val id = MetricId.LOAD
    override fun sample(native: NativeSnapshot?): List<String>? {
        val (a, b, c) = native?.load() ?: return null
        return listOf("%.2f  %.2f  %.2f".format(a, b, c))
    }
}

class TempSource : MetricSource {
    override val id = MetricId.TEMP
    override fun sample(native: NativeSnapshot?): List<String>? {
        val zones = native?.thermal()?.filter { it.second > 0 } ?: return null
        if (zones.isEmpty()) return null
        // Show at most three, shortened names.
        return zones.take(3).map { (k, c) -> "${short(k)} ${"%.1f".format(c)}°" }
    }

    private fun short(kind: String): String = kind
        .removeSuffix("-thermal").removeSuffix("_thermal")
        .take(10)
}

class UptimeSource : MetricSource {
    override val id = MetricId.UPTIME
    override fun sample(native: NativeSnapshot?): List<String>? {
        val s = native?.uptimeSecs() ?: return null
        val total = s.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        return listOf(if (h > 0) "${h}h ${m}m" else "${m}m")
    }
}

/** Shield-only: reads the Tegra HWC's AI Upscaling filter mode. Absent on other boxes. */
class UpscaleSource : MetricSource {
    override val id = MetricId.AI_UPSCALE
    override fun sample(native: NativeSnapshot?): List<String>? {
        val (filter, comparisonMode) = native?.upscale() ?: return null
        val lines = mutableListOf("filter: $filter")
        if (comparisonMode) lines.add("comparison mode: on")
        return lines
    }
}

/** Whether the active network route is through a VPN. Always shows a state, never hides. */
class VpnSource(private val ctx: Context) : MetricSource {
    override val id = MetricId.VPN
    override fun sample(native: NativeSnapshot?): List<String>? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return null) ?: return null
        val active = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        return listOf(if (active) "active" else "off")
    }
}

/** Cumulative dropped-packet counters since boot, summed across non-loopback interfaces. */
class PacketDropsSource : MetricSource {
    override val id = MetricId.PACKET_DROPS
    override fun sample(native: NativeSnapshot?): List<String>? {
        val (rx, tx) = native?.netDrops() ?: return null
        return listOf("rx $rx / tx $tx (since boot)")
    }
}

/**
 * Round-trip latency to the local gateway and a public resolver. Unlike the
 * other sources this needs active network probing (there's no passive kernel
 * counter for RTT), so the actual pinging happens on a background thread in
 * [LatencyProbe] — sample() only ever reads its cached last-measured values,
 * keeping this call as cheap as every other tile despite the ~1s ping cost.
 */
class LatencySource : MetricSource {
    override val id = MetricId.LATENCY
    override fun sample(native: NativeSnapshot?): List<String>? {
        val gw = LatencyProbe.gatewayMs
        val net = LatencyProbe.internetMs
        if (gw == null && net == null) return null
        return listOf(
            "gateway: " + (gw?.let { "${it}ms" } ?: "timeout"),
            "internet: " + (net?.let { "${it}ms" } ?: "timeout"),
        )
    }
}

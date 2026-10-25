package com.example.tvmon.metric

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.net.TrafficStats
import android.net.wifi.WifiInfo
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.view.Display
import com.example.tvmon.nativebridge.NativeSnapshot

/**
 * The full set of unprivileged sources. No root, no Shizuku — everything here
 * either uses a framework API or reads a world-readable kernel file via the
 * Rust core. See PROJECT.md for what's deliberately excluded.
 */
fun allSources(context: Context, net: NetMonitor): List<MetricSource> = listOf(
    ResolutionSource(context),
    RamSource(context),
    BatterySource(context),
    VpnSource(net),
    NetworkSource(net),
    AddressesSource(net),
    TrafficSource(),
    LoadSource(),
    TempSource(),
    UptimeSource(),
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
        // Display.Mode.getSupportedHdrTypes replaces this on API 34+; this still works and covers 26+.
        @Suppress("DEPRECATION")
        val hdr = d.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
        lines.add("HDR: " + hdrNames(hdr))
        return lines
    }

    // Not Context.display: a Service isn't a visual context, and on API 30+
    // that getter throws for one.
    private fun display(): Display? =
        ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)

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

// ---- network sources (shared NetMonitor) --------------------------------------

/**
 * VPN up or down, and what it runs over. Drawn in the warning colour when no VPN
 * is up, so a dropped tunnel stands out. Only VPNs that apply to this app are
 * visible: a per-app VPN that excludes tvmon reads as off.
 */
class VpnSource(private val net: NetMonitor) : MetricSource {
    override val id = MetricId.VPN
    override var warn = false
        private set

    override fun sample(native: NativeSnapshot?): List<String> {
        val s = net.state()
        val vpn = s.vpn
        warn = vpn == null
        if (vpn == null) {
            return listOf(NetFormat.vpnLine(false, null) + if (s.physical == null) " (offline)" else "")
        }
        // A VPN's capabilities usually also list the transport it runs over;
        // otherwise fall back to the physical network we picked.
        val via = vpn.transport ?: s.physical?.transport
        val lines = mutableListOf(NetFormat.vpnLine(true, via))
        val lp = vpn.lp
        if (lp != null) {
            lp.interfaceName?.let { lines.add("iface $it") }
            lines.addAll(NetFormat.addresses(lp.linkAddresses.map { it.address }, max = 2))
        }
        if (!vpn.validated) lines.add("not validated")
        return lines
    }
}

/** Transport, interface, validated/metered, and the Wi-Fi link when on Wi-Fi. */
class NetworkSource(private val net: NetMonitor) : MetricSource {
    override val id = MetricId.NETWORK

    override fun sample(native: NativeSnapshot?): List<String> {
        val s = net.state()
        val p = s.physical ?: return listOf("offline")
        val head = listOfNotNull(p.transport ?: "Network", p.lp?.interfaceName).joinToString(" · ")
        val lines = mutableListOf(head, NetFormat.stateLine(p.validated, p.metered, p.captivePortal))
        if (p.transport == "Wi-Fi") lines.addAll(wifiLines(net.wifiInfo(p)))
        return lines
    }

    private fun wifiLines(info: WifiInfo?): List<String> {
        info ?: return emptyList()
        val lines = mutableListOf<String>()
        NetFormat.bandLine(info.frequency)?.let(lines::add)
        val std = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) NetFormat.wifiStandard(info.wifiStandard) else null
        val link = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            NetFormat.linkSpeedLine(info.txLinkSpeedMbps, info.rxLinkSpeedMbps)
        } else {
            NetFormat.linkSpeedLine(info.linkSpeed, -1)
        }
        listOfNotNull(std, link).joinToString(" · ").takeIf { it.isNotEmpty() }?.let(lines::add)
        NetFormat.rssiLine(info.rssi)?.let(lines::add)
        return lines
    }
}

/**
 * LAN addresses and gateway of the physical network; DNS and Private DNS of the
 * network that actually resolves names (the VPN's, when one is up).
 */
class AddressesSource(private val net: NetMonitor) : MetricSource {
    override val id = MetricId.ADDRESSES

    override fun sample(native: NativeSnapshot?): List<String>? {
        val s = net.state()
        val phys = s.physical ?: return listOf("offline")
        val lp = phys.lp ?: return listOf("offline")
        val lines = NetFormat.addresses(lp.linkAddresses.map { it.address }).toMutableList()
        lp.routes.firstOrNull { it.isDefaultRoute && it.gateway?.isAnyLocalAddress == false }
            ?.gateway?.let { lines.add("gw " + NetFormat.addr(it)) }

        val dnsNet = s.dflt ?: phys
        val dnsLp = dnsNet.lp ?: lp
        NetFormat.dnsLine(dnsLp.dnsServers)?.let { lines.add(if (dnsNet.isVpn) "$it (VPN)" else it) }
        lines.add(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                NetFormat.privateDnsLine(true, dnsLp.isPrivateDnsActive, dnsLp.privateDnsServerName)
            } else {
                NetFormat.privateDnsLine(false, false, null)
            }
        )
        return lines
    }
}

/**
 * Device-wide rx/tx throughput from TrafficStats, averaged over the time since
 * the previous sample. With a VPN up the totals count both the tunnel and the
 * interface under it, so figures read roughly double the real line rate.
 */
class TrafficSource : MetricSource {
    override val id = MetricId.TRAFFIC
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastAt = 0L

    override fun sample(native: NativeSnapshot?): List<String>? {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) return null
        val now = SystemClock.elapsedRealtime()
        val secs = (now - lastAt) / 1000.0
        val fresh = lastRx < 0 || secs <= 0 || secs > 5   // first sample, or tile was off for a while
        val dRx = (rx - lastRx).coerceAtLeast(0)
        val dTx = (tx - lastTx).coerceAtLeast(0)
        lastRx = rx; lastTx = tx; lastAt = now
        if (fresh) return listOf("↓ …", "↑ …")
        return listOf("↓ ${NetFormat.rate(dRx / secs)}", "↑ ${NetFormat.rate(dTx / secs)}")
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

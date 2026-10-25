package com.example.tvmon.metric

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Keeps the current NetworkCapabilities + LinkProperties of every network this
 * app can see (VPNs included) up to date from ConnectivityManager callbacks, so
 * the network tiles read a cached state instead of polling binder calls every
 * second. Needs only ACCESS_NETWORK_STATE; Wi-Fi details use ACCESS_WIFI_STATE.
 * No location permission, so no SSID/BSSID — neither is shown.
 *
 * Callbacks arrive on the main looper, the same thread the overlay samples on,
 * so no locking is needed.
 */
class NetMonitor(context: Context) {

    private val ctx = context.applicationContext
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val wifi = ctx.getSystemService(WifiManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private class Entry(var caps: NetworkCapabilities? = null, var lp: LinkProperties? = null)

    private val nets = HashMap<Network, Entry>()
    private var defaultNet: Network? = null
    private var started = false

    /** Every network, including VPNs (the default request excludes them). */
    private val allCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            nets.getOrPut(n) { Entry() }.caps = caps
        }
        override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
            nets.getOrPut(n) { Entry() }.lp = lp
        }
        override fun onLost(n: Network) {
            nets.remove(n)
        }
    }

    /** Which of those networks this app's traffic actually uses right now. */
    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { defaultNet = n }
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            defaultNet = n
            nets.getOrPut(n) { Entry() }.caps = caps
        }
        override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
            nets.getOrPut(n) { Entry() }.lp = lp
        }
        override fun onLost(n: Network) {
            if (defaultNet == n) defaultNet = null
        }
    }

    fun start() {
        if (started || cm == null) return
        // Seed from a direct query so the first tick isn't "offline" while the
        // initial callbacks are still queued.
        runCatching {
            cm.activeNetwork?.let { n ->
                defaultNet = n
                nets[n] = Entry(cm.getNetworkCapabilities(n), cm.getLinkProperties(n))
            }
        }
        val all = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        started = runCatching {
            cm.registerNetworkCallback(all, allCallback, handler)
            cm.registerDefaultNetworkCallback(defaultCallback, handler)
        }.isSuccess
    }

    fun stop() {
        if (!started || cm == null) return
        runCatching { cm.unregisterNetworkCallback(allCallback) }
        runCatching { cm.unregisterNetworkCallback(defaultCallback) }
        started = false
        nets.clear()
        defaultNet = null
    }

    /** One consistent view of the network state, built from the cache. */
    fun state(): NetState {
        val entries = nets.entries.filter { it.value.caps != null }
        val vpn = entries.firstOrNull { it.value.caps!!.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        val dflt = defaultNet?.let { nets[it] }?.takeIf { it.caps != null }
        val physical = pickPhysical(entries.filter { !it.value.caps!!.isVpn() }, dflt)
        return NetState(
            vpn = vpn?.let { NetView(it.value.caps!!, it.value.lp) },
            dflt = dflt?.let { NetView(it.caps!!, it.lp) },
            physical = physical?.let { NetView(it.caps!!, it.lp) },
        )
    }

    /** The non-VPN network carrying traffic: the default one, else the best candidate. */
    private fun pickPhysical(candidates: List<Map.Entry<Network, Entry>>, dflt: Entry?): Entry? {
        if (dflt != null && !dflt.caps!!.isVpn()) return dflt
        return candidates.maxByOrNull { (_, e) ->
            val c = e.caps!!
            var score = 0
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 100
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) score += 50
            score += when {
                c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
                c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                else -> 0
            }
            score
        }?.value
    }

    /**
     * Wi-Fi link details. API 31+ hands them out on the network's capabilities
     * (location fields redacted, which we don't need); before that the
     * deprecated WifiManager.connectionInfo is the only route that needs no
     * location permission.
     */
    fun wifiInfo(view: NetView?): WifiInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (view?.caps?.transportInfo as? WifiInfo)?.let { return it }
        }
        @Suppress("DEPRECATION")
        return runCatching { wifi?.connectionInfo }.getOrNull()
    }
}

private fun NetworkCapabilities.isVpn() = hasTransport(NetworkCapabilities.TRANSPORT_VPN)

class NetView(val caps: NetworkCapabilities, val lp: LinkProperties?) {
    val isVpn get() = caps.isVpn()
    val validated get() = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    val metered get() = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    val captivePortal get() = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)

    /** The physical transport name, ignoring VPN; null if none is listed. */
    val transport: String?
        get() = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> null
        }
}

/**
 * `vpn`: a VPN network visible to this app, if any. `dflt`: the network this
 * app's traffic uses (the VPN when one is up). `physical`: the non-VPN network
 * underneath — the one whose band, gateway and LAN addresses matter.
 */
class NetState(val vpn: NetView?, val dflt: NetView?, val physical: NetView?)

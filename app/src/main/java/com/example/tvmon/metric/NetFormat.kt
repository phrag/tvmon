package com.example.tvmon.metric

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

/**
 * Pure formatting for the network tiles. No android.* imports on purpose, so all
 * of it is covered by plain JVM unit tests (NetFormatTest).
 */
object NetFormat {

    /** "2.4 GHz" / "5 GHz" / "6 GHz", or null for an unknown frequency. */
    fun band(freqMhz: Int): String? = when (freqMhz) {
        in 2400..2500 -> "2.4 GHz"
        in 4900..5900 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        else -> null
    }

    /** IEEE channel number for a centre frequency in MHz, or null if unknown. */
    fun channel(freqMhz: Int): Int? = when (freqMhz) {
        2484 -> 14
        in 2412..2472 -> (freqMhz - 2407) / 5
        in 4910..4980 -> (freqMhz - 4000) / 5      // Japan 4.9 GHz
        in 5000..5900 -> (freqMhz - 5000) / 5
        5935 -> 2                                  // the one odd 6 GHz channel
        in 5955..7115 -> (freqMhz - 5950) / 5
        else -> null
    }

    /** "5 GHz · ch 36 (5180 MHz)", or null when the frequency is unknown. */
    fun bandLine(freqMhz: Int): String? {
        val band = band(freqMhz) ?: return null
        val ch = channel(freqMhz)
        return if (ch != null) "$band · ch $ch ($freqMhz MHz)" else "$band ($freqMhz MHz)"
    }

    /** A plain word for an RSSI in dBm. */
    fun rssiQuality(rssi: Int): String = when {
        rssi >= -50 -> "excellent"
        rssi >= -60 -> "good"
        rssi >= -70 -> "fair"
        rssi >= -80 -> "weak"
        else -> "poor"
    }

    /** "-52 dBm (excellent)", or null when the RSSI is not a real reading. */
    fun rssiLine(rssi: Int): String? =
        if (rssi in -127..-1) "$rssi dBm (${rssiQuality(rssi)})" else null

    /**
     * Link rate. tx/rx are separate on API 29+; older devices only have one
     * figure, passed as `tx` with `rx` < 0. Values < 0 mean unknown.
     */
    fun linkSpeedLine(txMbps: Int, rxMbps: Int): String? = when {
        txMbps > 0 && rxMbps > 0 && txMbps != rxMbps -> "link tx $txMbps / rx $rxMbps Mbps"
        txMbps > 0 -> "link $txMbps Mbps"
        rxMbps > 0 -> "link rx $rxMbps Mbps"
        else -> null
    }

    /** WifiInfo.getWifiStandard() (API 30+) to a marketing name, or null. */
    fun wifiStandard(std: Int): String? = when (std) {
        1 -> "802.11a/b/g"   // WIFI_STANDARD_LEGACY
        4 -> "Wi-Fi 4"       // 11n
        5 -> "Wi-Fi 5"       // 11ac
        6 -> "Wi-Fi 6"       // 11ax
        7 -> "802.11ad"
        8 -> "Wi-Fi 7"       // 11be
        else -> null
    }

    /** Throughput as bits per second, the same unit as the link rate. */
    fun rate(bytesPerSec: Double): String {
        val bits = bytesPerSec.coerceAtLeast(0.0) * 8
        return when {
            bits < 1_000 -> String.format(Locale.US, "%.0f bit/s", bits)
            bits < 1_000_000 -> String.format(Locale.US, "%.0f kbit/s", bits / 1_000)
            bits < 100_000_000 -> String.format(Locale.US, "%.1f Mbit/s", bits / 1_000_000)
            else -> String.format(Locale.US, "%.0f Mbit/s", bits / 1_000_000)
        }
    }

    /** Address text without an IPv6 scope suffix ("fe80::1%wlan0" -> "fe80::1"). */
    fun addr(a: InetAddress): String = (a.hostAddress ?: "?").substringBefore('%')

    /**
     * The addresses worth showing: IPv4 first, then IPv6 with link-local ones
     * dropped unless they are all there is. At most `max` entries.
     */
    fun addresses(all: List<InetAddress>, max: Int = 3): List<String> {
        val v4 = all.filterIsInstance<Inet4Address>()
        val v6 = all.filterIsInstance<Inet6Address>()
        val v6Shown = v6.filterNot { it.isLinkLocalAddress }.ifEmpty { v6 }
        return (v4 + v6Shown).map(::addr).distinct().take(max)
    }

    /** "dns 1.1.1.1, 9.9.9.9" (at most `max`), or null when there are none. */
    fun dnsLine(servers: List<InetAddress>, max: Int = 2): String? {
        if (servers.isEmpty()) return null
        val shown = servers.map(::addr).distinct()
        val more = if (shown.size > max) " +${shown.size - max}" else ""
        return "dns " + shown.take(max).joinToString(", ") + more
    }

    /**
     * Private DNS (DNS-over-TLS) state from LinkProperties (API 28+).
     * `supported` false = the OS is too old to tell.
     */
    fun privateDnsLine(supported: Boolean, active: Boolean, serverName: String?): String = when {
        !supported -> "private DNS n/a"
        active && !serverName.isNullOrEmpty() -> "private DNS $serverName"
        active -> "private DNS on (auto)"
        else -> "private DNS off"
    }

    /** "validated · unmetered", plus a captive-portal hint when relevant. */
    fun stateLine(validated: Boolean, metered: Boolean, captivePortal: Boolean = false): String =
        listOfNotNull(
            if (validated) "validated" else "not validated",
            if (metered) "metered" else "unmetered",
            if (captivePortal) "captive portal" else null,
        ).joinToString(" · ")

    /**
     * The VPN tile's first line. `underlying` is the physical transport the
     * tunnel runs over ("Wi-Fi", "Ethernet"), or null if unknown.
     */
    fun vpnLine(on: Boolean, underlying: String?): String = when {
        !on -> "OFF"
        underlying != null -> "ON via $underlying"
        else -> "ON"
    }
}

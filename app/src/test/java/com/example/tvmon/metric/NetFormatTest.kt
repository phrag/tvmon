package com.example.tvmon.metric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class NetFormatBandTest {
    @Test fun bandsByFrequency() {
        assertEquals("2.4 GHz", NetFormat.band(2437))
        assertEquals("5 GHz", NetFormat.band(5180))
        assertEquals("6 GHz", NetFormat.band(5955))
        assertNull(NetFormat.band(-1))
        assertNull(NetFormat.band(0))
    }

    @Test fun channelsByFrequency() {
        assertEquals(1, NetFormat.channel(2412))
        assertEquals(6, NetFormat.channel(2437))
        assertEquals(13, NetFormat.channel(2472))
        assertEquals(14, NetFormat.channel(2484))
        assertEquals(36, NetFormat.channel(5180))
        assertEquals(149, NetFormat.channel(5745))
        assertEquals(165, NetFormat.channel(5825))
        assertEquals(1, NetFormat.channel(5955))
        assertEquals(2, NetFormat.channel(5935))
        assertEquals(233, NetFormat.channel(7115))
        assertNull(NetFormat.channel(1234))
    }

    @Test fun bandLine() {
        assertEquals("5 GHz · ch 36 (5180 MHz)", NetFormat.bandLine(5180))
        assertEquals("2.4 GHz · ch 6 (2437 MHz)", NetFormat.bandLine(2437))
        assertNull(NetFormat.bandLine(-1))
    }
}

class NetFormatLinkTest {
    @Test fun rssiQualityWords() {
        assertEquals("excellent", NetFormat.rssiQuality(-40))
        assertEquals("excellent", NetFormat.rssiQuality(-50))
        assertEquals("good", NetFormat.rssiQuality(-55))
        assertEquals("fair", NetFormat.rssiQuality(-70))
        assertEquals("weak", NetFormat.rssiQuality(-75))
        assertEquals("poor", NetFormat.rssiQuality(-90))
    }

    @Test fun rssiLineSkipsInvalidReadings() {
        assertEquals("-52 dBm (good)", NetFormat.rssiLine(-52))
        assertNull(NetFormat.rssiLine(-127 - 1))   // WifiInfo.INVALID_RSSI
        assertNull(NetFormat.rssiLine(0))
    }

    @Test fun linkSpeed() {
        assertEquals("link tx 866 / rx 780 Mbps", NetFormat.linkSpeedLine(866, 780))
        assertEquals("link 866 Mbps", NetFormat.linkSpeedLine(866, 866))
        assertEquals("link 433 Mbps", NetFormat.linkSpeedLine(433, -1))
        assertEquals("link rx 300 Mbps", NetFormat.linkSpeedLine(-1, 300))
        assertNull(NetFormat.linkSpeedLine(-1, -1))
    }

    @Test fun wifiStandards() {
        assertEquals("Wi-Fi 6", NetFormat.wifiStandard(6))
        assertEquals("Wi-Fi 5", NetFormat.wifiStandard(5))
        assertNull(NetFormat.wifiStandard(0))
    }
}

class NetFormatRateTest {
    @Test fun ratesInBits() {
        assertEquals("0 bit/s", NetFormat.rate(0.0))
        assertEquals("0 bit/s", NetFormat.rate(-5.0))
        assertEquals("800 bit/s", NetFormat.rate(100.0))
        assertEquals("80 kbit/s", NetFormat.rate(10_000.0))
        assertEquals("12.5 Mbit/s", NetFormat.rate(1_562_500.0))
        assertEquals("940 Mbit/s", NetFormat.rate(117_500_000.0))
    }
}

class NetFormatAddressTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test fun ipv4FirstAndLinkLocalDropped() {
        val list = listOf(ip("fe80::1"), ip("2001:db8::10"), ip("192.168.1.20"))
        assertEquals(listOf("192.168.1.20", "2001:db8:0:0:0:0:0:10"), NetFormat.addresses(list))
    }

    @Test fun linkLocalKeptWhenAlone() {
        assertEquals(listOf("fe80:0:0:0:0:0:0:1"), NetFormat.addresses(listOf(ip("fe80::1"))))
    }

    @Test fun capsTheCount() {
        val list = listOf(ip("10.0.0.1"), ip("10.0.0.2"), ip("10.0.0.3"), ip("10.0.0.4"))
        assertEquals(2, NetFormat.addresses(list, max = 2).size)
    }

    @Test fun scopeSuffixStripped() {
        assertEquals("fe80:0:0:0:0:0:0:1", NetFormat.addr(ip("fe80::1%1")))
    }

    @Test fun dnsLine() {
        assertNull(NetFormat.dnsLine(emptyList()))
        assertEquals("dns 1.1.1.1", NetFormat.dnsLine(listOf(ip("1.1.1.1"))))
        assertEquals("dns 1.1.1.1, 9.9.9.9 +1",
            NetFormat.dnsLine(listOf(ip("1.1.1.1"), ip("9.9.9.9"), ip("8.8.8.8"))))
    }
}

class NetFormatStateTest {
    @Test fun privateDns() {
        assertEquals("private DNS n/a", NetFormat.privateDnsLine(false, false, null))
        assertEquals("private DNS off", NetFormat.privateDnsLine(true, false, null))
        assertEquals("private DNS on (auto)", NetFormat.privateDnsLine(true, true, null))
        assertEquals("private DNS dns.quad9.net", NetFormat.privateDnsLine(true, true, "dns.quad9.net"))
    }

    @Test fun stateLine() {
        assertEquals("validated · unmetered", NetFormat.stateLine(validated = true, metered = false))
        assertEquals("not validated · metered · captive portal",
            NetFormat.stateLine(validated = false, metered = true, captivePortal = true))
    }

    @Test fun vpnLine() {
        assertEquals("OFF", NetFormat.vpnLine(false, "Wi-Fi"))
        assertEquals("ON via Wi-Fi", NetFormat.vpnLine(true, "Wi-Fi"))
        assertEquals("ON", NetFormat.vpnLine(true, null))
    }
}

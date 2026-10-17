package dev.wifibridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiQrTest {
    @Test fun everyVectorCase() {
        val cases = Vectors.wifiQr
        assertEquals(9, cases.size)
        for (c in cases) assertEquals("input ${c.input}", c.out, WifiQr.parse(c.input))
    }

    @Test fun formatRoundTripsVectorOutputs() {
        for (c in Vectors.wifiQr) {
            val net = c.out ?: continue
            assertEquals(net, WifiQr.parse(WifiQr.format(net)))
        }
    }

    @Test fun extraCases() {
        // Samsung/Android style with trailing whitespace and WPA2 type
        assertEquals(WifiNetwork("Home", "WPA", "pw", false), WifiQr.parse("  WIFI:T:WPA2;S:Home;P:pw;;\n"))
        assertEquals(WifiNetwork("Home", "nopass", "", false), WifiQr.parse("WIFI:S:Home;;"))
        assertEquals(null, WifiQr.parse("WIFI:S:;T:WPA;P:x;;"))
        assertEquals(null, WifiQr.parse(""))
    }
}

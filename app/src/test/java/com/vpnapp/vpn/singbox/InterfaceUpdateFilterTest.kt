package com.vpnapp.vpn.singbox

import org.junit.Assert.*
import org.junit.Test

class InterfaceUpdateFilterTest {
    @Test fun ignoresRepeatedNotificationsButKeepsNetworkReplacementAndMeteringChanges() {
        val updates = InterfaceUpdateFilter()
        val wifi = PhysicalInterfaceState("wlan0", 12, 300, false)
        assertTrue(updates.changed(wifi))
        repeat(10) { assertFalse(updates.changed(wifi)) }
        assertTrue(updates.changed(wifi.copy(networkHandle = 301)))
        assertTrue(updates.changed(wifi.copy(networkHandle = 301, metered = true)))
        assertTrue(updates.changed(PhysicalInterfaceState("rmnet0", 15, 302, true)))
    }

    @Test fun networkLossAndReusedInterfaceInvalidateStaleSessionsWithoutResettingForMetering() {
        val updates = InterfaceUpdateFilter()
        val wifi = PhysicalInterfaceState("wlan0", 12, 300, false, "192.168.1.10/24")
        assertFalse(updates.needsReset(wifi)); updates.changed(wifi)
        assertTrue(updates.needsReset(wifi.copy(networkHandle = 301)))
        assertTrue(updates.needsReset(wifi.copy(addresses = "192.168.1.11/24")))
        assertFalse(updates.needsReset(wifi.copy(metered = true)))
        assertTrue(updates.changed(PhysicalInterfaceState("", -1, 0, false)))
        assertFalse(updates.changed(PhysicalInterfaceState("", -1, 0, false)))
        assertTrue(updates.changed(wifi))
    }
}

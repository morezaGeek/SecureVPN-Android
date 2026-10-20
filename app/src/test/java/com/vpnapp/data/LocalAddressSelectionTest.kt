package com.vpnapp.data

import org.junit.Assert.*
import org.junit.Test

class LocalAddressSelectionTest {
    @Test fun ignoresCarrierAuxiliaryNetworksAndUsesTheActiveInternetAddress() {
        val ims = PhysicalAddressCandidate(1, listOf("30.1.2.3"), internet = false)
        val data = PhysicalAddressCandidate(2, listOf("25.4.5.6"), internet = true)
        assertEquals("25.4.5.6", LocalAddressSelection.primary(listOf(ims, data), 2, emptyList()))
    }
    @Test fun vpnUnderlyingNetworkTakesPriorityOverAnotherAvailableNetwork() {
        val wifi = PhysicalAddressCandidate(1, listOf("192.168.1.5"), internet = true, validated = true, lan = true)
        val data = PhysicalAddressCandidate(2, listOf("100.64.1.5"), internet = true, validated = true)
        val vpn = PhysicalAddressCandidate(3, listOf("172.19.0.1"), internet = true, vpn = true)
        assertEquals("100.64.1.5", LocalAddressSelection.primary(listOf(wifi, data, vpn), 3, listOf(2)))
    }
    @Test fun neverShowsTheTunnelAddressOrAnAuxiliaryNetworkAsTheLocalUplink() {
        val vpn = PhysicalAddressCandidate(3, listOf("172.19.0.1"), internet = true, vpn = true)
        val auxiliary = PhysicalAddressCandidate(4, listOf("10.1.1.1"), internet = false)
        assertNull(LocalAddressSelection.primary(listOf(vpn, auxiliary), 3, emptyList()))
    }
}

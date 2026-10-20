package com.vpnapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LanAddressSelectionTest {
    private fun iface(name: String, vararg addresses: String, up: Boolean = true, loopback: Boolean = false) =
        LanInterfaceCandidate(name, up, loopback, addresses.toList())

    @Test fun hotspotAddressesDoNotRequireAConnectivityManagerNetwork() {
        assertEquals(listOf("192.168.43.1", "192.168.55.1", "172.20.10.1"), LanAddressSelection.addresses(emptyList(), listOf(
            iface("ap0", "192.168.43.1"), iface("swlan0", "192.168.55.1"), iface("ap_br_wlan0", "172.20.10.1")
        )))
    }

    @Test fun wifiFromConnectivityManagerAndInterfacesIsOnlyListedOnce() {
        assertEquals(listOf("192.168.1.7", "192.168.43.1"), LanAddressSelection.addresses(listOf("192.168.1.7"), listOf(
            iface("wlan0", "192.168.1.7"), iface("ap0", "192.168.43.1")
        )))
    }

    @Test fun tunnelCellularAndKnownBlockedInterfacesOrAddressesAreExcluded() {
        val interfaces = listOf("tun0", "tap0", "ppp0", "ipsec0", "dummy0", "rmnet_data0", "clat4", "v4-rmnet_data0", "wg0")
            .map { iface(it, "10.9.0.1") } + listOf(iface("wlan9", "10.8.0.1"), iface("ap0", "10.7.0.1", "192.168.43.1"))
        assertEquals(listOf("192.168.43.1"), LanAddressSelection.addresses(listOf("10.7.0.1"), interfaces,
            excludedNames = setOf("WLAN9"), excludedAddresses = setOf("10.7.0.1")))
    }

    @Test fun onlyUpLanInterfacesAndPrivateIpv4AddressesAreEligible() {
        assertEquals(listOf("10.0.0.1", "172.16.0.1", "172.31.255.1", "192.168.1.1"), LanAddressSelection.addresses(emptyList(), listOf(
            iface("ap0", "8.8.8.8", "100.64.0.1", "127.0.0.1", "169.254.1.1", "172.15.0.1", "172.32.0.1", "::1", "10.1.1.999", "10.0.0.1", "172.16.0.1", "172.31.255.1", "192.168.1.1"),
            iface("eth0", "10.0.0.2", up = false), iface("wlan0", "10.0.0.3", loopback = true), iface("unknown0", "10.0.0.4")
        )))
    }

    @Test fun usbEthernetBluetoothAndBridgeLanNamesAreRecognized() {
        val names = listOf("eth0", "rndis0", "usb0", "bnep0", "bridge0", "br0", "br-lan")
        val expected = names.indices.map { "192.168.${it + 1}.1" }
        assertEquals(expected, LanAddressSelection.addresses(emptyList(), names.mapIndexed { index, name -> iface(name, expected[index]) }))
    }
}

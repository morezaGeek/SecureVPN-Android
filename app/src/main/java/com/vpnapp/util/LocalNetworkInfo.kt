package com.vpnapp.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.vpnapp.data.LanAddressSelection
import com.vpnapp.data.LanInterfaceCandidate
import com.vpnapp.data.LocalAddressSelection
import com.vpnapp.data.PhysicalAddressCandidate
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object LocalNetworkInfo {
    /** Read interface addresses, never an Internet IP service or the VPN's virtual address. */
    fun addresses(context: Context, lanOnly: Boolean = false, preferredNetwork: Network? = null): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (lanOnly) return lanAddresses(cm)
        val candidates = cm.allNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            val addresses = cm.getLinkProperties(network)?.linkAddresses.orEmpty().map { it.address }
                .filter { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
                .mapNotNull { it.hostAddress }.distinct()
            PhysicalAddressCandidate(network.networkHandle, addresses,
                internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
        val active = cm.activeNetwork
        // Android does not expose a VPN's underlying networks through the public SDK.
        // Reuse the physical network tracked by the app; otherwise choose a validated Internet uplink.
        return listOfNotNull(LocalAddressSelection.primary(candidates, active?.networkHandle, listOfNotNull(preferredNetwork?.networkHandle)))
    }

    private fun lanAddresses(cm: ConnectivityManager): List<String> {
        val knownLan = mutableListOf<String>()
        val excludedNames = mutableSetOf<String>()
        val excludedAddresses = mutableSetOf<String>()
        fun exclude(properties: android.net.LinkProperties) {
            properties.interfaceName?.let(excludedNames::add)
            properties.linkAddresses.mapNotNullTo(excludedAddresses) { it.address.hostAddress }
        }
        cm.allNetworks.forEach { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@forEach
            val properties = cm.getLinkProperties(network) ?: return@forEach
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> exclude(properties)
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                    properties.linkAddresses.mapNotNullTo(knownLan) { if (it.address is Inet4Address) it.address.hostAddress else null }
                }
            }
        }
        // Hotspot/downstream interfaces are not normally ConnectivityManager Networks.
        // Reading interface addresses needs no location or privileged tethering access.
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.let(Collections::list).orEmpty().mapNotNull { networkInterface ->
                runCatching {
                    LanInterfaceCandidate(networkInterface.name, networkInterface.isUp, networkInterface.isLoopback,
                        Collections.list(networkInterface.inetAddresses).filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress })
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
        return LanAddressSelection.addresses(knownLan, interfaces, excludedNames, excludedAddresses)
    }
}

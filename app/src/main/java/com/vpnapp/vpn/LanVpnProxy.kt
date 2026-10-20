package com.vpnapp.vpn

import android.net.Network
import java.io.Closeable
import java.net.Socket

/** All outbound sockets and DNS queries belong to the active VPN, without a direct-network fallback. */
class LanVpnProxy(settings: LanShareSettings, network: Network, protectClient: (Socket) -> Boolean) : Closeable {
    private val server = LanProxyServer(settings,
        createSocket = { network.socketFactory.createSocket() },
        resolveHost = { network.getAllByName(it).first() },
        prepareClient = protectClient)

    fun status(): LanProxyStatus = server.status()
    override fun close() = server.close()
}

package com.vpnapp.vpn

import android.content.Intent

/** Runtime state sent from the VPN worker; contains no destination or credential data. */
data class LanProxyStatus(
    val state: String,
    val protocol: String,
    val port: Int,
    val accepted: Int = 0,
    val forwarded: Int = 0,
    val lastError: String? = null
) {
    fun writeTo(intent: Intent) {
        intent.putExtra("lan_status_state", state).putExtra("lan_status_protocol", protocol)
            .putExtra("lan_status_port", port).putExtra("lan_status_accepted", accepted)
            .putExtra("lan_status_forwarded", forwarded).putExtra("lan_status_error", lastError)
    }

    companion object {
        fun read(intent: Intent): LanProxyStatus? {
            val state = intent.getStringExtra("lan_status_state") ?: return null
            if (state !in setOf("OFF", "WAITING", "LISTENING", "ERROR")) return null
            val protocol = intent.getStringExtra("lan_status_protocol") ?: return null
            if (protocol !in setOf("HTTP", "SOCKS5")) return null
            val port = intent.getIntExtra("lan_status_port", 0)
            if (port !in 1024..65535) return null
            return LanProxyStatus(state, protocol, port,
                intent.getIntExtra("lan_status_accepted", 0).coerceAtLeast(0),
                intent.getIntExtra("lan_status_forwarded", 0).coerceAtLeast(0),
                intent.getStringExtra("lan_status_error"))
        }
    }
}

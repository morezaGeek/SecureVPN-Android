package com.vpnapp.vpn

import android.content.SharedPreferences
import com.vpnapp.data.VpnProtocol

internal object TunnelPreferences {
    // Start a new opt-in at 1.1.11. The old bypass_iran preference defaulted to on.
    // Both the UI and Always-on use the new key so a later opt-in persists.
    const val BYPASS_IRAN_KEY = "bypass_iran_enabled"
    const val OTHER_MTU_KEY = "mtu_size"
    const val V2RAY_MTU_KEY = "v2ray_mtu_size"
    val mtuRange = 1200..1500

    fun mtu(prefs: SharedPreferences, forV2Ray: Boolean): Int {
        val key = if (forV2Ray) V2RAY_MTU_KEY else OTHER_MTU_KEY
        val defaultMtu = if (forV2Ray) 1500 else 1400
        return prefs.getInt(key, defaultMtu).takeIf { it in mtuRange } ?: defaultMtu
    }
}

internal val VpnProtocol.usesV2RaySettings: Boolean
    get() = when (this) {
        VpnProtocol.VLESS, VpnProtocol.VMESS, VpnProtocol.TROJAN,
        VpnProtocol.SHADOWSOCKS, VpnProtocol.HYSTERIA2 -> true
        VpnProtocol.OPENCONNECT, VpnProtocol.SSTP -> false
    }

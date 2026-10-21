package com.vpnapp.vpn

import android.content.Intent
import com.vpnapp.data.VpnProfile

/** Shared profile fields for manual connection and current-profile Always-on recovery. */
object ProfileConnectionExtras {
    fun write(intent: Intent, profile: VpnProfile) = intent.apply {
        putExtra(VpnConnectionService.EXTRA_SERVER_IP_INPUT, profile.serverAddress)
        putExtra(VpnConnectionService.EXTRA_SERVER_PORT, profile.port)
        putExtra(VpnConnectionService.EXTRA_PROTOCOL, profile.protocol.name)
        putExtra(VpnConnectionService.EXTRA_USERNAME, profile.username)
        putExtra(VpnConnectionService.EXTRA_PASSWORD, profile.password)
        putExtra(VpnConnectionService.EXTRA_SKIP_CERT_VERIFY, profile.skipCertificateVerification)
        putExtra(VpnConnectionService.EXTRA_PROFILE_NAME, profile.name)
        putExtra(VpnConnectionService.EXTRA_PROFILE_ID, profile.id)
        putExtra(VpnConnectionService.EXTRA_V2RAY_UUID, profile.v2rayUuid)
        putExtra(VpnConnectionService.EXTRA_V2RAY_ALTERID, profile.v2rayAlterId)
        putExtra(VpnConnectionService.EXTRA_V2RAY_SECURITY, profile.v2raySecurity)
        putExtra(VpnConnectionService.EXTRA_VLESS_ENCRYPTION, profile.vlessEncryption)
        putExtra(VpnConnectionService.EXTRA_V2RAY_NETWORK, profile.v2rayNetwork)
        putExtra(VpnConnectionService.EXTRA_V2RAY_TYPE, profile.v2rayType)
        putExtra("v2ray_xhttp_extra", profile.v2rayXhttpExtra)
        putExtra(VpnConnectionService.EXTRA_V2RAY_HOST, profile.v2rayHost)
        putExtra(VpnConnectionService.EXTRA_V2RAY_PATH, profile.v2rayPath)
        putExtra(VpnConnectionService.EXTRA_V2RAY_TLS, profile.v2rayTls)
        putExtra(VpnConnectionService.EXTRA_V2RAY_SNI, profile.v2raySni)
        putExtra(VpnConnectionService.EXTRA_V2RAY_ALPN, profile.v2rayAlpn)
        putExtra(VpnConnectionService.EXTRA_V2RAY_FP, profile.v2rayFp)
        putExtra(VpnConnectionService.EXTRA_V2RAY_FLOW, profile.v2rayFlow)
        putExtra(VpnConnectionService.EXTRA_V2RAY_PBK, profile.v2rayPbk)
        putExtra(VpnConnectionService.EXTRA_V2RAY_SHORT_ID, profile.v2rayShortId)
        putExtra("v2ray_ech", profile.v2rayEch)
        putExtra("hysteria_obfs_min", profile.hysteriaObfsMinPacketSize)
        putExtra("hysteria_obfs_max", profile.hysteriaObfsMaxPacketSize)
        putExtra("hysteria_obfs", profile.hysteriaObfs)
        putExtra("hysteria_obfs_password", profile.hysteriaObfsPassword)
        putExtra(VpnConnectionService.EXTRA_DISABLE_DTLS, profile.disableDtls)
        putExtra(VpnConnectionService.EXTRA_USE_NATIVE, profile.useNative)
    }
}

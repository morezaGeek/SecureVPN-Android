package com.vpnapp.data

/** Ensures the device-to-VPN-endpoint hop is encrypted before a tunnel starts. */
object TunnelEncryptionPolicy {
    private val encryptedVmessMethods = setOf(
        "auto",
        "aes-128-gcm",
        "chacha20-poly1305",
        "aes-128-ctr"
    )

    fun blockingReason(profile: VpnProfile): String? = when (profile.protocol) {
        VpnProtocol.VLESS -> {
            val hasTls = profile.v2raySecurity.equals("tls", ignoreCase = true) ||
                profile.v2raySecurity.equals("reality", ignoreCase = true) ||
                profile.v2rayTls.equals("tls", ignoreCase = true)
            val hasVlessEncryption = profile.vlessEncryption.trim().let {
                it.isNotEmpty() && !it.equals("none", ignoreCase = true)
            }
            if (hasTls || hasVlessEncryption) null else
                "This VLESS profile has no TLS, REALITY, or VLESS encryption. Import or edit an encrypted profile to connect."
        }

        VpnProtocol.VMESS -> {
            val hasTls = profile.v2rayTls.equals("tls", ignoreCase = true)
            val hasProtocolEncryption = profile.v2raySecurity.trim().lowercase() in encryptedVmessMethods
            if (hasTls || hasProtocolEncryption) null else
                "This VMess profile uses no encryption. Enable TLS or select an encrypted VMess security method to connect."
        }

        VpnProtocol.SHADOWSOCKS -> {
            val method = profile.username.trim()
            if (method.isNotEmpty() && !method.equals("none", ignoreCase = true)) null else
                "This Shadowsocks profile has no encryption method. Import or edit an encrypted profile to connect."
        }

        // OpenConnect and SSTP use TLS; Trojan and Hysteria2 always create TLS
        // configuration in Sing-box.
        VpnProtocol.OPENCONNECT, VpnProtocol.SSTP, VpnProtocol.TROJAN, VpnProtocol.HYSTERIA2 -> null
    }
}

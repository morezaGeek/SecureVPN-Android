package com.vpnapp.data

import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class TunnelEncryptionPolicyTest {
    private fun profile(protocol: VpnProtocol) = VpnProfile(
        name = "test",
        serverAddress = "vpn.example",
        protocol = protocol
    )

    @Test fun vlessRequiresTlsRealityOrProtocolEncryption() {
        assertNotNull(TunnelEncryptionPolicy.blockingReason(profile(VpnProtocol.VLESS)))
        assertNotNull(TunnelEncryptionPolicy.blockingReason(profile(VpnProtocol.VLESS).copy(vlessEncryption = " none ")))
        assertNull(TunnelEncryptionPolicy.blockingReason(profile(VpnProtocol.VLESS).copy(v2rayTls = "tls")))
        assertNull(TunnelEncryptionPolicy.blockingReason(profile(VpnProtocol.VLESS).copy(v2raySecurity = "reality")))
        assertNull(TunnelEncryptionPolicy.blockingReason(profile(VpnProtocol.VLESS).copy(vlessEncryption = "mlkem768x25519plus.native.key")))
    }

    @Test fun vmessAllowsTlsOrSupportedEncryptedSecurityAndBlocksNone() {
        assertNull(TunnelEncryptionPolicy.blockingReason(profile(VpnProtocol.VMESS))) // default auto
        assertNull(TunnelEncryptionPolicy.blockingReason(profile(VpnProtocol.VMESS).copy(v2raySecurity = "none", v2rayTls = "tls")))
        assertNull(TunnelEncryptionPolicy.blockingReason(profile(VpnProtocol.VMESS).copy(v2raySecurity = "chacha20-poly1305")))
        assertNotNull(TunnelEncryptionPolicy.blockingReason(profile(VpnProtocol.VMESS).copy(v2raySecurity = "none")))
        assertNotNull(TunnelEncryptionPolicy.blockingReason(profile(VpnProtocol.VMESS).copy(v2raySecurity = "zero")))
    }

    @Test fun shadowsocksNoneIsRejectedAndEncryptedProtocolsRemainAllowed() {
        assertNotNull(TunnelEncryptionPolicy.blockingReason(profile(VpnProtocol.SHADOWSOCKS).copy(username = "none")))
        assertNull(TunnelEncryptionPolicy.blockingReason(profile(VpnProtocol.SHADOWSOCKS).copy(username = "aes-256-gcm")))
        listOf(VpnProtocol.OPENCONNECT, VpnProtocol.SSTP, VpnProtocol.TROJAN, VpnProtocol.HYSTERIA2)
            .forEach { assertNull(TunnelEncryptionPolicy.blockingReason(profile(it))) }
    }
}

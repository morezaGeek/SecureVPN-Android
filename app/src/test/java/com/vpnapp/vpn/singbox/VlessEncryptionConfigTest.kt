package com.vpnapp.vpn.singbox

import com.vpnapp.data.VpnProfile
import com.vpnapp.data.VpnProtocol
import org.junit.Assert.*
import org.junit.Test

class VlessEncryptionConfigTest {
    private val profile = VpnProfile(name = "Encrypted VLESS", serverAddress = "203.0.113.10",
        protocol = VpnProtocol.VLESS, v2rayUuid = "00000000-0000-4000-8000-000000000001",
        v2rayNetwork = "ws", v2rayTls = "tls", v2raySni = "example.com")

    @Test fun protocolEncryptionIsPreservedIndependentlyOfTlsAndFlow() {
        val encryption = "mlkem768x25519plus.native.0rtt.100-111-1111.75-0-111.50-0-3333.test-public-key"
        val outbound = SingBoxConfigGenerator.buildOutbounds(profile.copy(vlessEncryption = encryption,
            v2rayFlow = "xtls-rprx-vision")).getJSONObject(0)
        assertEquals(encryption, outbound.getString("encryption"))
        assertTrue(outbound.getJSONObject("tls").getBoolean("enabled"))
        assertEquals("xtls-rprx-vision", outbound.getString("flow"))
        assertEquals("ws", outbound.getJSONObject("transport").getString("type"))
    }

    @Test fun oldAndPlainVlessDoNotEnableProtocolEncryption() {
        for (value in listOf("none", "", "  none  ")) {
            assertFalse(SingBoxConfigGenerator.buildOutbounds(profile.copy(vlessEncryption = value))
                .getJSONObject(0).has("encryption"))
        }
        val vmess = SingBoxConfigGenerator.buildOutbounds(profile.copy(protocol = VpnProtocol.VMESS,
            vlessEncryption = "mlkem768x25519plus.native.0rtt.key", v2raySecurity = "auto")).getJSONObject(0)
        assertFalse(vmess.has("encryption"))
        assertEquals("auto", vmess.getString("security"))
    }
}

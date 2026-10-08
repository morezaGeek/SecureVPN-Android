package com.vpnapp

import com.vpnapp.data.*
import org.junit.Assert.*
import org.junit.Test

class ShareLinkRoundTripTest {
    private val base = VpnProfile(name = "فرانسه + 50% 🇫🇷", serverAddress = "2001:db8::1", port = 8443,
        protocol = VpnProtocol.VLESS, v2rayUuid = "11111111-2222-3333-4444-555555555555",
        v2rayNetwork = "xhttp", v2rayType = "stream-up", v2rayHost = "cdn.example.com",
        v2rayPath = "/a path/?token=a+b%20", v2raySni = "example.com", v2rayTls = "tls",
        v2rayAlpn = "h2,http/1.1", v2rayFp = "chrome", skipCertificateVerification = false,
        v2rayXhttpExtra = "{\"headers\":{\"X-Test\":\"a+b %\"}}")
    private fun roundTrip(profile: VpnProfile): VpnProfile {
        val parsed = ShareLinkCodec.parseImport(ShareLinkCodec.export(profile)) as? LinkImport.Profile
        assertNotNull(parsed)
        return parsed!!.profile.also {
            assertEquals(profile.name, it.name); assertEquals(profile.serverAddress, it.serverAddress)
            assertEquals(profile.port, it.port); assertEquals(profile.protocol, it.protocol)
            assertNull(it.subscriptionId); assertNull(it.latency)
        }
    }
    @Test fun vlessPreservesXhttpEncryptionUnicodeAndTls() {
        val source = base.copy(vlessEncryption = "mlkem768x25519plus.native.0.test-key")
        val target = roundTrip(source)
        assertEquals(source.vlessEncryption, target.vlessEncryption)
        assertEquals(source.v2rayUuid, target.v2rayUuid); assertEquals(source.v2rayPath, target.v2rayPath)
        assertEquals(source.v2rayType, target.v2rayType); assertEquals(source.v2rayXhttpExtra, target.v2rayXhttpExtra)
        assertEquals(source.v2rayAlpn, target.v2rayAlpn); assertFalse(target.skipCertificateVerification)
    }
    @Test fun realityAndGrpcAreNotDowngradedToOrdinaryTls() {
        val source = base.copy(v2rayNetwork = "grpc", v2rayPath = "service", v2rayType = "none",
            v2rayPbk = "public-key", v2rayShortId = "aabb", v2rayFlow = "xtls-rprx-vision",
            v2raySecurity = "reality", skipCertificateVerification = true)
        val target = roundTrip(source)
        assertEquals("reality", target.v2raySecurity); assertEquals(source.v2rayPbk, target.v2rayPbk)
        assertEquals(source.v2rayShortId, target.v2rayShortId); assertEquals(source.v2rayFlow, target.v2rayFlow)
        assertEquals(source.v2rayPath, target.v2rayPath); assertTrue(target.skipCertificateVerification)
    }
    @Test fun vmessPreservesCipherAlterIdAndXhttpExtra() {
        val source = base.copy(protocol = VpnProtocol.VMESS, v2raySecurity = "aes-128-gcm", v2rayAlterId = 7)
        val target = roundTrip(source)
        assertEquals(7, target.v2rayAlterId); assertEquals(source.v2raySecurity, target.v2raySecurity)
        assertEquals(source.v2rayXhttpExtra, target.v2rayXhttpExtra); assertEquals(source.v2rayPath, target.v2rayPath)
    }
    @Test fun passwordProtocolsPreserveReservedCharactersAndIpv6() {
        for (protocol in listOf(VpnProtocol.TROJAN, VpnProtocol.SHADOWSOCKS, VpnProtocol.HYSTERIA2,
            VpnProtocol.OPENCONNECT, VpnProtocol.SSTP)) {
            val source = base.copy(protocol = protocol, password = "p:@/+#% رمز", username =
                if (protocol == VpnProtocol.SHADOWSOCKS) "aes-256-gcm" else "u:@+%",
                hysteriaObfs = "salamander", hysteriaObfsPassword = "obfs+%",
                mtu = 1360, disableDtls = true, useNative = false)
            val target = roundTrip(source)
            assertEquals(source.password, target.password)
            if (protocol in listOf(VpnProtocol.OPENCONNECT, VpnProtocol.SSTP)) {
                assertEquals(source.username, target.username); assertEquals(1360, target.mtu)
                assertTrue(target.disableDtls); assertFalse(target.useNative)
            }
            if (protocol == VpnProtocol.SHADOWSOCKS) assertEquals(source.username, target.username)
            if (protocol == VpnProtocol.HYSTERIA2) {
                assertEquals(source.hysteriaObfsPassword, target.hysteriaObfsPassword)
                assertFalse(target.skipCertificateVerification)
            }
        }
    }
    @Test fun subscriptionIsKeptIntactAndUnsupportedOrInvalidQrIsRejected() {
        val url = "https://example.com/sub/token?key=a%2Bb&foo=1"
        assertEquals(LinkImport.Subscription(url), ShareLinkCodec.parseImport(url))
        for (bad in listOf("", "WIFI:S:test;P:password;;", "file:///data/a", "https://", "vless://@example.com:443",
            "https://user:pass@example.com/sub", "vless://id@example.com:99999")) {
            assertNull(bad, ShareLinkCodec.parseImport(bad))
        }
    }
}

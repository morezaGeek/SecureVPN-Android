package com.vpnapp.data

import org.junit.Assert.*
import org.junit.Test

class XHttpSmartPolicyTest {
    private val profile = VpnProfile(name = "Smart test", serverAddress = "example.com",
        protocol = VpnProtocol.VLESS, v2rayNetwork = "xhttp", v2rayType = "stream-up",
        v2rayOriginalXhttpMode = "auto", v2rayXhttpModeOverride = "smart",
        vlessEncryption = "mlkem768x25519plus.native.0rtt.key", skipCertificateVerification = false,
        v2raySni = "example.com", v2rayUuid = "test-user", v2rayXhttpExtra = "{\"noSSEHeader\":true}")

    @Test fun startsWithOriginalOrVerifiedModeAndNeverLoops() {
        assertEquals(listOf("auto", "stream-up", "packet-up", "stream-one"), XHttpSmartPolicy.candidates(profile))
        assertEquals(listOf("packet-up", "auto", "stream-up", "stream-one"),
            XHttpSmartPolicy.candidates(profile.copy(v2raySmartXhttpMode = "packet-up")))
        val applied = XHttpModePolicy.apply(profile, true)
        assertEquals("auto", applied.v2rayType)
        assertEquals("smart", applied.v2rayXhttpModeOverride)
    }

    @Test fun attemptsOnlyChangeModeAndRefreshInvalidatesChangedAuthentication() {
        for (mode in XHttpSmartPolicy.candidates(profile)) {
            assertEquals(profile, XHttpSmartPolicy.candidate(profile, mode).copy(v2rayType = profile.v2rayType))
        }
        val cached = profile.copy(v2raySmartXhttpMode = "auto")
        val incoming = profile.copy(v2rayXhttpModeOverride = null, v2rayOriginalXhttpMode = null)
        assertEquals("auto", XHttpModePolicy.preserveOverride(incoming, listOf(cached)).v2raySmartXhttpMode)
        val changed = XHttpModePolicy.preserveOverride(incoming.copy(vlessEncryption = "changed"), listOf(cached))
        assertEquals("smart", changed.v2rayXhttpModeOverride)
        assertNull(changed.v2raySmartXhttpMode)
    }
}

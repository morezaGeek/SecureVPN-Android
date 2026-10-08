package com.vpnapp.data

import com.vpnapp.vpn.singbox.SingBoxConfigGenerator
import org.junit.Assert.*
import org.junit.Test

class XHttpModePolicyTest {
    private val profile = VpnProfile(name = "Policy test", serverAddress = "cdn.example",
        protocol = VpnProtocol.VLESS, v2rayNetwork = "xhttp", v2rayType = "auto",
        v2rayTls = "tls", v2rayPath = "/original", v2rayAlpn = "h2",
        v2rayXhttpExtra = """{"mode":"packet-up","xPaddingBytes":"100-200","headers":{"X-Test":"keep"}}""")

    @Test fun everyXHttpModeCanBeForcedAndRestoredWithoutChangingOtherFields() {
        for (network in listOf("xhttp", "splithttp", "XHTTP")) {
            for (mode in listOf("auto", "packet-up", "stream-one", "stream-up", "none", "")) {
                val original = profile.copy(v2rayNetwork = network, v2rayType = mode)
                val forced = XHttpModePolicy.apply(original, true)
                assertEquals("stream-up", forced.v2rayType)
                assertEquals(original.copy(v2rayType = "stream-up", v2rayOriginalXhttpMode = mode), forced)
                assertEquals(forced, XHttpModePolicy.apply(forced, true))
                assertEquals(original, XHttpModePolicy.apply(forced, false))
                assertEquals(original, XHttpModePolicy.apply(original, false))
            }
        }
    }

    @Test fun extraModeCannotOverrideForcedCoreModeAndOtherExtrasRemainIntact() {
        val forced = XHttpModePolicy.apply(profile, true)
        val transport = SingBoxConfigGenerator.buildOutbounds(forced).getJSONObject(0).getJSONObject("transport")
        assertEquals("stream-up", transport.getString("mode"))
        assertEquals("100-200", transport.getString("x_padding_bytes"))
        assertEquals("keep", transport.getJSONObject("headers").getString("X-Test"))
        assertEquals(profile.v2rayXhttpExtra, forced.v2rayXhttpExtra)
        assertEquals("auto", SingBoxConfigGenerator.buildOutbounds(XHttpModePolicy.apply(forced, false))
            .getJSONObject(0).getJSONObject("transport").getString("mode"))
    }

    @Test fun otherTransportsRemainUnchanged() {
        for (network in listOf("tcp", "ws", "grpc", "httpupgrade", "h2")) {
            val other = profile.copy(v2rayNetwork = network)
            assertSame(other, XHttpModePolicy.apply(other, true))
            assertSame(other, XHttpModePolicy.apply(other, false))
        }
    }

    @Test fun compatibilityOverrideSurvivesGlobalToggleAndCanBeRemoved() {
        val forced = XHttpModePolicy.apply(profile, true)
        val custom = XHttpModePolicy.apply(forced.copy(v2rayXhttpModeOverride = "auto"), true)
        assertEquals("auto", custom.v2rayType)
        assertEquals("auto", custom.v2rayOriginalXhttpMode)
        assertEquals(custom, XHttpModePolicy.apply(custom, false))
        assertEquals("auto", SingBoxConfigGenerator.buildOutbounds(custom).getJSONObject(0).getJSONObject("transport").getString("mode"))
        assertEquals(forced, XHttpModePolicy.apply(custom.copy(v2rayXhttpModeOverride = null), true))
        assertEquals(profile, XHttpModePolicy.apply(custom.copy(v2rayXhttpModeOverride = null), false))
    }

    @Test fun subscriptionRefreshKeepsOverrideOnlyForTheSameEndpoint() {
        val old = profile.copy(v2rayXhttpModeOverride = "auto")
        val refreshed = profile.copy(id = "new-id", v2rayUuid = "renewed-credential")
        assertEquals("auto", XHttpModePolicy.preserveOverride(refreshed, listOf(old)).v2rayXhttpModeOverride)
        assertNull(XHttpModePolicy.preserveOverride(refreshed.copy(v2rayPath = "/different"), listOf(old)).v2rayXhttpModeOverride)
        assertNull(XHttpModePolicy.preserveOverride(refreshed.copy(serverAddress = "other.example"), listOf(old)).v2rayXhttpModeOverride)
    }
}

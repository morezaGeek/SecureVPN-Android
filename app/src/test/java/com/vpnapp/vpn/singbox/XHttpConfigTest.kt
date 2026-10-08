package com.vpnapp.vpn.singbox

import com.vpnapp.data.VpnProfile
import com.vpnapp.data.VpnProtocol
import org.junit.Assert.*
import org.junit.Test

class XHttpConfigTest {
    private val profile = VpnProfile(name = "XHTTP config test", serverAddress = "203.0.113.10",
        protocol = VpnProtocol.VLESS, v2rayUuid = "00000000-0000-4000-8000-000000000001",
        v2rayNetwork = "xhttp", v2rayType = "packet-up", v2rayTls = "tls",
        v2rayPath = "/preserve%2Fpath+suffix", v2rayHost = "cdn.example", v2raySni = "cdn.example")

    @Test fun xhttpHonorsExplicitHttp1AndPreservesModeAndPath() {
        val outbound = SingBoxConfigGenerator.buildOutbounds(profile.copy(v2rayAlpn = "http/1.1")).getJSONObject(0)
        val tls = outbound.getJSONObject("tls")
        assertEquals(1, tls.getJSONArray("alpn").length())
        assertEquals("http/1.1", tls.getJSONArray("alpn").getString(0))
        val transport = outbound.getJSONObject("transport")
        assertEquals("packet-up", transport.getString("mode"))
        assertEquals(profile.v2rayPath, transport.getString("path"))
        assertEquals("cdn.example", transport.getString("host"))
        assertEquals("xudp", outbound.getString("packet_encoding"))
    }

    @Test fun xhttpDoesNotForceHttp2WhenAlpnIsUnspecified() {
        val tls = SingBoxConfigGenerator.buildOutbounds(profile).getJSONObject(0).getJSONObject("tls")
        assertEquals(listOf("h2", "http/1.1"), (0 until tls.getJSONArray("alpn").length()).map { tls.getJSONArray("alpn").getString(it) })
        val reality = SingBoxConfigGenerator.buildOutbounds(profile.copy(v2raySecurity = "reality",
            v2rayPbk = "test-key", v2rayAlpn = "http/1.1")).getJSONObject(0).getJSONObject("tls")
        assertEquals("http/1.1", reality.getJSONArray("alpn").getString(0))
    }

    @Test fun interactivePacingDefaultAndExplicitSubscriptionOptions() {
        val defaults = XHttpOptions.build(profile)
        assertEquals("1-1", defaults.getString("sc_min_posts_interval_ms"))
        val explicit = XHttpOptions.build(profile.copy(v2rayXhttpExtra = """{
            "scMinPostsIntervalMs": "10-20", "scMaxEachPostBytes": 1000000,
            "xPaddingBytes": "100-1000", "noGRPCHeader": true,
            "headers": {"X-Test": "value"}, "xmux": {"maxConcurrency": 8}
        }"""))
        assertEquals("10-20", explicit.getString("sc_min_posts_interval_ms"))
        assertEquals("1000000", explicit.getString("sc_max_each_post_bytes"))
        assertTrue(explicit.getBoolean("no_grpc_header"))
        assertEquals("value", explicit.getJSONObject("headers").getString("X-Test"))
        assertEquals(8, explicit.getJSONObject("xmux").getInt("max_concurrency"))
    }
}

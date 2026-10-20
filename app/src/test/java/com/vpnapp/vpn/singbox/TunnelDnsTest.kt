package com.vpnapp.vpn.singbox

import com.vpnapp.data.*
import org.junit.Assert.*
import org.junit.Test

class TunnelDnsTest {
    @Test fun echAndGeckoReachTheCoreWithTheirOriginalParameters() {
        val p = VpnProfile(name = "ECH", serverAddress = "192.0.2.1", protocol = VpnProtocol.VLESS,
            v2rayNetwork = "xhttp", v2rayType = "packet-up", v2rayTls = "tls", v2rayAlpn = "h2",
            v2rayEch = "cloudflare-ech.com+udp://1.1.1.1")
        val outbound = SingBoxConfigGenerator.buildOutbounds(XHttpModePolicy.apply(p, true)).getJSONObject(0)
        assertEquals("cloudflare-ech.com", outbound.getJSONObject("tls").getJSONObject("ech").getString("query_server_name"))
        assertEquals("packet-up", outbound.getJSONObject("transport").getString("mode"))
        val resolver = TunnelDns.echResolver(p.v2rayEch)
        assertEquals("udp", resolver.getString("type")); assertEquals("1.1.1.1", resolver.getString("server"))
        assertFalse(resolver.has("detour"))
        val hy = p.copy(protocol = VpnProtocol.HYSTERIA2, hysteriaObfs = "gecko",
            hysteriaObfsMinPacketSize = 600, hysteriaObfsMaxPacketSize = 1300)
        val obfs = SingBoxConfigGenerator.buildOutbounds(hy).getJSONObject(0).getJSONObject("obfs")
        assertEquals("gecko", obfs.getString("type")); assertEquals(600, obfs.getInt("min_packet_size"))
        assertEquals(1300, obfs.getInt("max_packet_size"))
    }

    @Test fun tunnelDnsUsesProxyAndHostnameBootstrapUsesDirectDns() {
        val url = TunnelDns.validateUrl("https://doh.rahanetmci.com/dns-query")
        val resolver = TunnelDns.server(url, "proxy-dns", "proxy")
        assertEquals("https", resolver.getString("type")); assertEquals("proxy", resolver.getString("detour"))
        assertEquals("/dns-query", resolver.getString("path")); assertEquals("direct-dns", resolver.getString("domain_resolver"))
        for (bad in listOf("http://example.com/dns-query", "https://user:pass@example.com/", "not a URL")) {
            assertTrue(runCatching { TunnelDns.validateUrl(bad) }.isFailure)
        }
    }
}

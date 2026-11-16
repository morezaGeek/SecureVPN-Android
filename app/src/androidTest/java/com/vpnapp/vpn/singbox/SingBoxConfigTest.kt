package com.vpnapp.vpn.singbox

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.data.VpnProfile
import com.vpnapp.data.VpnProtocol
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SingBoxConfigTest {
    @Before fun setUpCore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Libbox.setup(SetupOptions().apply {
            setBasePath(context.filesDir.absolutePath)
            setWorkingPath(context.cacheDir.absolutePath)
            setTempPath(context.cacheDir.absolutePath)
        })
    }

    @Test fun bundledCoreAppliesIpv6PolicyWithRealDnsAndFakeIp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = VpnProfile(
            name = "IP policy regression", serverAddress = "192.0.2.1",
            protocol = VpnProtocol.VLESS, v2rayUuid = "00000000-0000-4000-8000-000000000001",
            v2rayTls = "tls", mtu = 1200
        )
        assertFalse(TunnelDnsSettings(TunnelDns.DEFAULT_URL, false).preferIpv6)
        for (prefer in listOf(false, true)) for (fakeIp in listOf(false, true)) {
            val snapshot = TunnelDnsSettings(TunnelDns.DEFAULT_URL, fakeIp, preferIpv6 = prefer)
            assertEquals(snapshot, TunnelDnsSettings.read(Intent().also(snapshot::writeTo)))
            val config = SingBoxConfigGenerator.generate(profile, context, runtimeDns = snapshot)
            Libbox.checkConfig(config)
            val json = JSONObject(config)
            val strategy = if (prefer) "prefer_ipv6" else "ipv4_only"
            val dns = json.getJSONObject("dns")
            assertEquals(strategy, dns.getString("strategy"))
            val dnsRules = dns.getJSONArray("rules")
            assertEquals(!prefer, dnsRules.getJSONObject(0).optString("action") == "predefined")
            if (fakeIp) assertEquals("[\"A\"]",
                dnsRules.getJSONObject(dnsRules.length() - 1).getJSONArray("query_type").toString())
            val tun = json.getJSONArray("inbounds").getJSONObject(0)
            assertEquals(if (prefer) 1280 else 1200, tun.getInt("mtu"))
            assertEquals(if (prefer) 2 else 1, tun.getJSONArray("address").length())
            val routes = json.getJSONObject("route").getJSONArray("rules")
            assertEquals(!prefer, routes.getJSONObject(0).optInt("ip_version") == 6)
            val resolve = (0 until routes.length()).map { routes.getJSONObject(it) }
                .firstOrNull { it.optString("action") == "resolve" }
            assertEquals(strategy.takeUnless { fakeIp }, resolve?.getString("strategy"))
        }
    }

    @Test fun bundledCoreAcceptsEveryRoutingCombination() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (iran in listOf(false, true)) for (lan in listOf(false, true)) {
            val profile = VpnProfile(name = "Route regression", serverAddress = "203.0.113.10",
                protocol = VpnProtocol.VLESS, v2rayUuid = "00000000-0000-4000-8000-000000000001",
                v2rayTls = "tls", bypassIranRoutes = iran, bypassPrivateIps = lan, mtu = 1300)
            val config = SingBoxConfigGenerator.generate(profile, context)
            Libbox.checkConfig(config)
            val json = JSONObject(config)
            val cache = json.getJSONObject("experimental").getJSONObject("cache_file")
            assertTrue(cache.getBoolean("enabled")); assertTrue(cache.getBoolean("store_fakeip"))
            assertEquals(java.io.File(context.noBackupFilesDir, "sing-box-fakeip.db").absolutePath, cache.getString("path"))
            assertEquals(1300, json.getJSONArray("inbounds").getJSONObject(0).getInt("mtu"))
            val rules = json.getJSONObject("route").getJSONArray("rules")
            val privateRule = (0 until rules.length()).map { rules.getJSONObject(it) }.single { it.has("ip_is_private") }
            assertEquals(if (lan) "direct" else "proxy", privateRule.getString("outbound"))
            val fakeIpRules = (0 until rules.length()).map { rules.getJSONObject(it) }
                .filter { rule ->
                    val cidrs = rule.optJSONArray("ip_cidr")
                    cidrs != null && (0 until cidrs.length()).any { cidrs.getString(it) == "198.18.0.0/15" }
                }
            assertEquals(1, fakeIpRules.size)
            assertEquals("proxy", fakeIpRules.last().getString("outbound"))
            assertFalse((0 until rules.length()).map { rules.getJSONObject(it) }.any {
                it.optString("network") == "udp" && it.optString("outbound") == "block"
            })
            val dnsRules = json.getJSONObject("dns").getJSONArray("rules")
            assertEquals(iran, (0 until dnsRules.length()).any { dnsRules.getJSONObject(it).has("domain_suffix") })
        }
    }
}

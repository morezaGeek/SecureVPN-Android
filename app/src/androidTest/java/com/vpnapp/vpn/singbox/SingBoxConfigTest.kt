package com.vpnapp.vpn.singbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.data.VpnProfile
import com.vpnapp.data.VpnProtocol
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SingBoxConfigTest {
    @Test fun bundledCoreAcceptsEveryRoutingCombination() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Libbox.setup(SetupOptions().apply {
            setBasePath(context.filesDir.absolutePath)
            setWorkingPath(context.cacheDir.absolutePath)
            setTempPath(context.cacheDir.absolutePath)
        })
        for (iran in listOf(false, true)) for (lan in listOf(false, true)) {
            val profile = VpnProfile(name = "Route regression", serverAddress = "203.0.113.10",
                protocol = VpnProtocol.VLESS, v2rayUuid = "00000000-0000-4000-8000-000000000001",
                bypassIranRoutes = iran, bypassPrivateIps = lan, mtu = 1300)
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

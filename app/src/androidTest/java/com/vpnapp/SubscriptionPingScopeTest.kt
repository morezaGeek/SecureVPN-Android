package com.vpnapp

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.data.*
import com.vpnapp.viewmodel.VpnViewModel
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class SubscriptionPingScopeTest {
    @Test fun coreAcceptsEchBootstrapAndIndependentTunnelDnsForBothModes() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = app.getSharedPreferences("vpn_app_prefs", 0)
        val oldUrl = prefs.getString(com.vpnapp.vpn.singbox.TunnelDns.URL_KEY, null)
        val oldFake = prefs.getBoolean(com.vpnapp.vpn.singbox.TunnelDns.FAKE_IP_KEY, true)
        val profile = VpnProfile(name="ECH",serverAddress="192.0.2.1",protocol=VpnProtocol.VLESS,
            v2rayUuid="00000000-0000-4000-8000-000000000001",v2rayNetwork="xhttp",
            v2rayType="packet-up",v2rayTls="tls",v2raySni="example.com",v2rayFp="chrome",
            v2rayEch="cloudflare-ech.com+udp://1.1.1.1")
        try {
            for (fake in listOf(true,false)) {
                prefs.edit().putString(com.vpnapp.vpn.singbox.TunnelDns.URL_KEY,"https://doh.rahanetmci.com/dns-query")
                    .putBoolean(com.vpnapp.vpn.singbox.TunnelDns.FAKE_IP_KEY,fake).commit()
                val config = com.vpnapp.vpn.singbox.SingBoxConfigGenerator.generate(profile, app,
                    com.vpnapp.vpn.singbox.ProbeEndpoint.create())
                io.nekohasekai.libbox.Libbox.checkConfig(config)
                val route = org.json.JSONObject(config).getJSONObject("route").getJSONArray("rules")
                val routeRules = (0 until route.length()).map { route.getJSONObject(it) }
                val resolveIndex = routeRules.indexOfFirst { it.optString("action") == "resolve" }
                val probeIndex = routeRules.indexOfFirst { it.optJSONArray("inbound")?.toString()?.contains("app-probe") == true }
                assertTrue("The app probe must remain authenticated and routed", probeIndex >= 0)
                if(fake) assertEquals(-1, resolveIndex) else {
                    assertTrue("Resolve cached FakeIP domains before forcing the probe route", resolveIndex in 1 until probeIndex)
                    assertFalse("Keep Iran/ECH DNS rule selection", routeRules[resolveIndex].has("server"))
                }
                assertFalse("Co-hosted DNS relays must not inherit the VPN server's direct bypass",
                    routeRules.any { it.optString("outbound") == "direct" && it.optJSONArray("ip_cidr")?.toString()?.contains("192.0.2.1/32") == true })
                val dns = org.json.JSONObject(config).getJSONObject("dns")
                assertEquals("ech-dns",dns.getJSONArray("rules").getJSONObject(0).getString("server"))
                val rules=dns.getJSONArray("rules")
                assertEquals(if(fake) "fakeip" else "proxy-dns",rules.getJSONObject(rules.length()-1).getString("server"))
                val resolver=dns.getJSONArray("servers").getJSONObject(0)
                assertEquals("doh.rahanetmci.com",resolver.getString("server"))
                assertEquals("proxy",resolver.getString("detour"))
            }
        } finally {
            prefs.edit().apply { if(oldUrl==null) remove(com.vpnapp.vpn.singbox.TunnelDns.URL_KEY)
                else putString(com.vpnapp.vpn.singbox.TunnelDns.URL_KEY,oldUrl) }
                .putBoolean(com.vpnapp.vpn.singbox.TunnelDns.FAKE_IP_KEY,oldFake).commit()
        }
    }

    @Test fun importsNeverProbeAndManualTestUsesOnlyTheSelectedSubscription() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val repo = ProfileRepository.getInstance(app)
        val savedProfiles = repo.loadProfiles(); val savedSubs = repo.loadSubscriptions()
        val savedSelected = repo.getSelectedProfileId(); val savedFilter = repo.getSelectedSubscriptionId()
        val store = ViewModelStore(); val calls = CopyOnWriteArrayList<String>()
        val first = VpnProfile(id="first", name="First", serverAddress="example.com", subscriptionId="first-sub", latency=100)
        lateinit var vm: VpnViewModel
        fun waitUntil(condition: () -> Boolean) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (!condition() && System.nanoTime() < end) Thread.sleep(30)
            assertTrue("Timed out", condition())
        }
        try {
            repo.saveProfiles(listOf(first)); repo.saveSubscriptions(listOf(VpnSubscription("first-sub","First","https://example.com/first")))
            repo.setSelectedProfileId(first.id); repo.setSelectedSubscriptionId("first-sub")
            instrumentation.runOnMainSync {
                vm = VpnViewModel(app, subscriptionFetcher={url,id,_ ->
                    VpnSubscription(id,"Second",url) to listOf(first.copy(id="second", name="Second", subscriptionId=id))
                }, latencyTester={_,p -> calls.add(p.id); 42})
                store.put("test",vm)
                vm.addProfile(first.copy(id="manual",subscriptionId=null))
                vm.addSubscription("https://example.com/second")
            }
            waitUntil { vm.profiles.value.size == 3 && vm.selectedSubscriptionId.value != "first-sub" }
            instrumentation.waitForIdleSync(); assertTrue("Imports must not ping",calls.isEmpty())
            val firstLatency = vm.profiles.value.first { it.id == "first" }.latency
            instrumentation.runOnMainSync { vm.measureAllLatencies() }
            waitUntil { calls.size == 1 && !vm.latencyTestState.value.isRunning }
            assertEquals(listOf("second"),calls.toList())
            assertEquals(firstLatency, vm.profiles.value.first { it.id == "first" }.latency)
            instrumentation.runOnMainSync { vm.selectSubscription(null); vm.measureAllLatencies() }
            waitUntil { calls.size == 4 && !vm.latencyTestState.value.isRunning }
            assertEquals(setOf("first","manual","second"), calls.drop(1).toSet())
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            repo.saveProfiles(savedProfiles); repo.saveSubscriptions(savedSubs)
            repo.setSelectedProfileId(savedSelected); repo.setSelectedSubscriptionId(savedFilter)
        }
    }

    @Test fun echAndGeckoSurviveImportStorageAndSharing() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = ProfileRepository.getInstance(app); val saved = repo.loadProfiles()
        try {
            val ech = V2RayLinkParser.parseLink("vless://00000000-0000-4000-8000-000000000001@example.com:443?security=tls&type=xhttp&ech=cloudflare-ech.com%2Budp%3A%2F%2F1.1.1.1&extra=%7B%22mode%22%3A%22packet-up%22%7D#ECH")!!
            assertEquals("packet-up",ech.v2rayType); assertEquals("cloudflare-ech.com+udp://1.1.1.1",ech.v2rayEch)
            val gecko = V2RayLinkParser.parseLink("hysteria2://test@example.com:443?obfs=gecko&minPacketSize=600&maxPacketSize=1300#Gecko")!!
            repo.saveProfiles(listOf(ech,gecko)); val restored=repo.loadProfiles()
            assertEquals(listOf(ech,gecko),restored)
            assertEquals(ech.v2rayEch,V2RayLinkParser.parseLink(ShareLinkCodec.export(restored[0]))!!.v2rayEch)
            val shared = V2RayLinkParser.parseLink(ShareLinkCodec.export(restored[1]))!!
            assertEquals(600,shared.hysteriaObfsMinPacketSize);assertEquals(1300,shared.hysteriaObfsMaxPacketSize)
            val ssAuth = android.util.Base64.encodeToString("aes-256-gcm:test".toByteArray(),android.util.Base64.NO_WRAP)
            val ss = V2RayLinkParser.parseLink("ss://$ssAuth@[2001:db8::1]:32666/#Test")!!
            assertEquals(32666,ss.port);assertEquals("2001:db8::1",ss.serverAddress);assertEquals("test",ss.password)
        } finally { repo.saveProfiles(saved) }
    }
}

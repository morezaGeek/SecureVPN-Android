package com.vpnapp.vpn.singbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.MainActivity
import com.vpnapp.vpn.VpnConnectionService
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VpnRoutingLifecycleTest {
    @Test fun changesRoutesLiveAndReconnectsAfterCompletedTeardown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val events = LinkedBlockingQueue<String>()
        val closingPid = java.util.concurrent.atomic.AtomicInteger()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra(VpnConnectionService.EXTRA_STATUS) == "DISCONNECTED")
                    closingPid.set(intent.getIntExtra("closed_worker_pid", 0))
                events.offer(intent.getStringExtra("message") ?: intent.getStringExtra(VpnConnectionService.EXTRA_STATUS) ?: "")
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(VpnConnectionService.ACTION_VPN_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertNull("Grant ACTIVATE_VPN to the test app before running", android.net.VpnService.prepare(context))
        val prefs = context.getSharedPreferences("vpn_app_prefs", Context.MODE_PRIVATE)
        val originalIran = prefs.getBoolean("bypass_iran", true)
        val originalLan = prefs.getBoolean("bypass_private_ips", true)
        fun awaitEvent(expected: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < deadline) {
                val event = events.poll(1, TimeUnit.SECONDS) ?: continue
                if (event == expected) return
                assertFalse("Unexpected VPN error: $event", event == "ERROR")
            }
            fail("Timed out waiting for $expected")
        }
        fun connect() {
            events.clear()
            context.startService(Intent(context, VpnConnectionService::class.java).apply {
                action = VpnConnectionService.ACTION_CONNECT
                putExtra(VpnConnectionService.EXTRA_PROTOCOL, "VLESS")
                putExtra(VpnConnectionService.EXTRA_PROFILE_NAME, "Local lifecycle regression")
                putExtra(VpnConnectionService.EXTRA_SERVER_IP_INPUT, "203.0.113.10")
                putExtra(VpnConnectionService.EXTRA_V2RAY_UUID, "00000000-0000-4000-8000-000000000001")
                putExtra(VpnConnectionService.EXTRA_MTU, 1300)
            })
            awaitEvent("CONNECTED")
        }
        fun disconnect() {
            context.startService(Intent(context, VpnConnectionService::class.java).setAction(VpnConnectionService.ACTION_DISCONNECT))
            awaitEvent("DISCONNECTING")
            awaitEvent("DISCONNECTED")
            com.vpnapp.AwaitVpnTeardown.await(context, closingPid.get())
            // Check the actual Android VPN network, not just the app's disconnected label.
            val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            fun vpnRemains() = connectivity.allNetworks.any {
                connectivity.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
            }
            while (vpnRemains() && System.nanoTime() < deadline) Thread.sleep(100)
            assertFalse("Android still reports a VPN after disconnect", vpnRemains())
        }
        try {
            connect()
            for ((iran, lan) in listOf(true to false, false to false, false to true, true to true, false to false)) {
                prefs.edit().putBoolean("bypass_iran", iran).putBoolean("bypass_private_ips", lan).commit()
                context.startService(Intent(context, VpnConnectionService::class.java).apply {
                    action = VpnConnectionService.ACTION_UPDATE_ROUTING
                    putExtra("bypass_iran", iran)
                    putExtra("bypass_private_ips", lan)
                })
                awaitEvent("Routing applied: Iran=$iran, LAN=$lan")
                System.gc()
                System.runFinalization()
                val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
                assertTrue(connectivity.allNetworks.any {
                    connectivity.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                })
                val json = JSONObject(java.io.File(context.cacheDir, "sing-box-config.json").readText())
                val rules = json.getJSONObject("route").getJSONArray("rules")
                val privateRule = (0 until rules.length()).map { rules.getJSONObject(it) }.single { it.has("ip_is_private") }
                assertEquals(if (lan) "direct" else "proxy", privateRule.getString("outbound"))
            }
            disconnect()
            repeat(3) {
                connect() // Repeated immediate reconnect after completed teardown.
                disconnect()
            }
        } finally {
            context.startService(Intent(context, VpnConnectionService::class.java).setAction(VpnConnectionService.ACTION_DISCONNECT))
            prefs.edit().putBoolean("bypass_iran", originalIran).putBoolean("bypass_private_ips", originalLan).commit()
            context.unregisterReceiver(receiver)
            activity.finish()
        }
    }
}


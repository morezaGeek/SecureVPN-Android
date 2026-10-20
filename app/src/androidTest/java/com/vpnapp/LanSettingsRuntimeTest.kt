package com.vpnapp

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.ui.screens.NetworkToolsScreen
import com.vpnapp.ui.theme.VPNAppTheme
import com.vpnapp.vpn.LanProxyProtocol
import com.vpnapp.vpn.LanShareSettings
import com.vpnapp.vpn.VpnConnectionService
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Opt-in regression: a temporarily stale UI state must not drop live proxy edits. */
class LanSettingsRuntimeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun staleDisconnectedScreenStillUpdatesTheRunningVpnProxy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.cacheDir, "network-features.private.json")
        assumeTrue("Requires the authorized private OpenConnect fixture", fixture.isFile)
        assumeTrue("Requires VPN consent on the dedicated test emulator", VpnService.prepare(context) == null)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun vpn() = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        assumeTrue("Do not disturb an existing VPN", vpn() == null)
        val original = LanShareSettings.read(context)
        val ports = ServerSocket(0).use { first -> ServerSocket(0).use { second -> first.localPort to second.localPort } }
        val oldPort = ports.first
        val newPort = ports.second
        fun listener(port: Int) = runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250); true }
        }.getOrDefault(false)
        fun waitFor(message: String, condition: () -> Boolean) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!condition() && System.nanoTime() < end) Thread.sleep(100)
            assertTrue(message, condition())
        }
        var started = false
        try {
            val input = JSONObject(fixture.readText())
            val initial = original.copy(enabled = true, port = oldPort, protocol = LanProxyProtocol.HTTP)
            LanShareSettings.save(context, initial)
            val connect = Intent(context, VpnConnectionService::class.java).setAction(VpnConnectionService.ACTION_CONNECT)
                .putExtra(VpnConnectionService.EXTRA_SERVER_IP_INPUT, input.getString("server"))
                .putExtra(VpnConnectionService.EXTRA_SERVER_PORT, 443)
                .putExtra(VpnConnectionService.EXTRA_PROTOCOL, "OPENCONNECT")
                .putExtra(VpnConnectionService.EXTRA_PROFILE_NAME, "Temporary LAN settings diagnostic")
                .putExtra(VpnConnectionService.EXTRA_USERNAME, input.getString("username"))
                .putExtra(VpnConnectionService.EXTRA_PASSWORD, input.getString("password"))
                .putExtra(VpnConnectionService.EXTRA_SKIP_CERT_VERIFY, true)
                .putExtra(VpnConnectionService.EXTRA_USE_NATIVE, true)
                .putExtra("bypass_iran", false).putExtra("bypass_private_ips", false)
                .also(initial::writeTo)
            context.startForegroundService(connect)
            started = true
            waitFor("VPN and the original LAN listener must start") { vpn() != null && listener(oldPort) }
            compose.setContent {
                VPNAppTheme { NetworkToolsScreen(alwaysOn = false, lockdown = false, connected = false, onBack = {}) }
            }
            compose.onNodeWithTag("lan_port").performScrollTo().performTextReplacement(newPort.toString())
            compose.onNodeWithTag("lan_save").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(newPort, LanShareSettings.read(context).port) }
            waitFor("Saving from a stale disconnected screen must replace the running listener") {
                !listener(oldPort) && listener(newPort)
            }
            assertNotNull("The VPN must remain connected while its LAN port changes", vpn())
        } finally {
            try {
                if (started) {
                    context.startService(Intent(context, VpnConnectionService::class.java).setAction(VpnConnectionService.ACTION_DISCONNECT))
                    waitFor("Disconnect must remove VPN and both LAN listeners") {
                        vpn() == null && !listener(oldPort) && !listener(newPort)
                    }
                }
            } finally {
                LanShareSettings.save(context, original)
                fixture.delete()
            }
        }
    }
}

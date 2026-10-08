package com.vpnapp

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.data.VpnProfile
import com.vpnapp.data.VpnProtocol
import com.vpnapp.viewmodel.VpnViewModel
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ProbeLifecycleTest {
    @Test fun mtuPersistsAcrossViewModelsAndRejectsInvalidValues() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("vpn_app_prefs", 0)
        val previous = prefs.getInt("mtu_size", 1400)
        val existed = prefs.contains("mtu_size")
        instrumentation.runOnMainSync {
            val first = ViewModelStore()
            val second = ViewModelStore()
            try {
                val model = VpnViewModel(context.applicationContext as Application)
                first.put("mtu", model)
                model.setMtuSize(1280)
                first.clear()
                val restored = VpnViewModel(context.applicationContext as Application)
                second.put("mtu", restored)
                assertEquals(1280, restored.mtuSize.value)
                try { restored.setMtuSize(1100); fail("Invalid MTU accepted") }
                catch (_: IllegalArgumentException) { }
                assertEquals(1280, restored.mtuSize.value)
            } finally {
                first.clear(); second.clear()
                prefs.edit().apply { if (existed) putInt("mtu_size", previous) else remove("mtu_size") }.commit()
            }
        }
    }

    @Test fun probesLeaveCheckingAndRestartForEverySessionWithoutATunnel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        assertFalse("Run this failure-path test with VPN disconnected", connectivity.allNetworks.any {
            connectivity.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        })
        val prefs = context.getSharedPreferences("vpn_profiles", 0)
        val selected = prefs.getString("selected_profile_id", null)
        val store = ViewModelStore()
        lateinit var model: VpnViewModel
        instrumentation.runOnMainSync {
            model = VpnViewModel(context.applicationContext as Application)
            store.put("probeRegression", model)
            model.selectProfile(VpnProfile(name = "Probe regression", serverAddress = "203.0.113.10", protocol = VpnProtocol.VLESS))
        }
        try {
            repeat(3) {
                instrumentation.runOnMainSync {
                    model.updateConnectionStatus("CONNECTING", null, null)
                    model.updateConnectionStatus("CONNECTED", "172.19.0.1", "203.0.113.10")
                }
                assertEquals("Checking...", model.connectionInfo.value.stats.publicIp)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
                while (System.nanoTime() < deadline &&
                    (model.connectionInfo.value.stats.publicIp == "Checking..." || model.connectionInfo.value.stats.ping == -1L)) {
                    Thread.sleep(100)
                }
                assertEquals("Unavailable", model.connectionInfo.value.stats.publicIp)
                assertEquals(0L, model.connectionInfo.value.stats.ping)
                instrumentation.runOnMainSync { model.updateConnectionStatus("DISCONNECTED", null, null) }
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            prefs.edit().putString("selected_profile_id", selected).commit()
        }
    }
}

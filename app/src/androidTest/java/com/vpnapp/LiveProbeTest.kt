package com.vpnapp

import android.app.Application
import android.content.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.data.*
import com.vpnapp.util.IpAddressValidator
import com.vpnapp.viewmodel.VpnViewModel
import com.vpnapp.vpn.VpnConnectionService as Service
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.*

/** Optional device smoke test: uses the user's selected saved V2Ray profile, never prints credentials. */
class LiveProbeTest {
    @Test fun savedProfileReturnsExitAndWarmPingAfterThreeReconnects() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = ProfileRepository.getInstance(context)
        val profile = repository.loadProfiles().find { it.id == repository.getSelectedProfileId() }
        assumeTrue("No selected V2Ray profile on this emulator", profile != null && profile.protocol in
            listOf(VpnProtocol.VLESS, VpnProtocol.VMESS, VpnProtocol.TROJAN, VpnProtocol.SHADOWSOCKS, VpnProtocol.HYSTERIA2))
        val selected = checkNotNull(profile)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertNull(android.net.VpnService.prepare(context))
        val store = ViewModelStore()
        lateinit var model: VpnViewModel
        instrumentation.runOnMainSync {
            model = VpnViewModel(context.applicationContext as Application)
            store.put("liveProbe", model)
            model.selectProfile(selected)
        }
        val events = LinkedBlockingQueue<String>()
        val closingPid = java.util.concurrent.atomic.AtomicInteger()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val status = intent.getStringExtra(Service.EXTRA_STATUS) ?: return
                if (status == "DISCONNECTED") closingPid.set(intent.getIntExtra("closed_worker_pid", 0))
                model.updateConnectionStatus(status, intent.getStringExtra(Service.EXTRA_PRIVATE_IP),
                    intent.getStringExtra(Service.EXTRA_SERVER_IP), intent.getIntExtra(Service.EXTRA_MTU, 1400),
                    closedWorkerPid = intent.getIntExtra("closed_worker_pid", 0))
                events.offer(status)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Service.ACTION_VPN_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        fun await(status: String) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < end) {
                val event = events.poll(1, TimeUnit.SECONDS) ?: continue
                assertNotEquals("VPN start failed", "ERROR", event)
                if (event == status) {
                    if (status == "DISCONNECTED") AwaitVpnTeardown.await(context, closingPid.get())
                    return
                }
            }
            fail("Missing $status")
        }
        try {
            repeat(3) {
                events.clear()
                context.startService(Intent(context, Service::class.java).apply {
                    action = Service.ACTION_CONNECT
                    putExtra(Service.EXTRA_PROTOCOL, selected.protocol.name)
                    putExtra(Service.EXTRA_PROFILE_NAME, selected.name)
                    putExtra(Service.EXTRA_SERVER_IP_INPUT, selected.serverAddress)
                    putExtra(Service.EXTRA_SERVER_PORT, selected.port)
                    putExtra(Service.EXTRA_USERNAME, selected.username)
                    putExtra(Service.EXTRA_PASSWORD, selected.password)
                    putExtra(Service.EXTRA_SKIP_CERT_VERIFY, selected.skipCertificateVerification)
                    putExtra(Service.EXTRA_V2RAY_UUID, selected.v2rayUuid)
                    putExtra(Service.EXTRA_V2RAY_ALTERID, selected.v2rayAlterId)
                    putExtra(Service.EXTRA_V2RAY_SECURITY, selected.v2raySecurity)
                    putExtra(Service.EXTRA_V2RAY_NETWORK, selected.v2rayNetwork)
                    putExtra(Service.EXTRA_V2RAY_TYPE, selected.v2rayType)
                    putExtra("v2ray_xhttp_extra", selected.v2rayXhttpExtra)
                    putExtra(Service.EXTRA_V2RAY_HOST, selected.v2rayHost)
                    putExtra(Service.EXTRA_V2RAY_PATH, selected.v2rayPath)
                    putExtra(Service.EXTRA_V2RAY_TLS, selected.v2rayTls)
                    putExtra(Service.EXTRA_V2RAY_SNI, selected.v2raySni)
                    putExtra(Service.EXTRA_V2RAY_ALPN, selected.v2rayAlpn)
                    putExtra(Service.EXTRA_V2RAY_FP, selected.v2rayFp)
                    putExtra(Service.EXTRA_V2RAY_FLOW, selected.v2rayFlow)
                    putExtra(Service.EXTRA_V2RAY_PBK, selected.v2rayPbk)
                    putExtra(Service.EXTRA_V2RAY_SHORT_ID, selected.v2rayShortId)
                    putExtra("hysteria_obfs", selected.hysteriaObfs)
                    putExtra("hysteria_obfs_password", selected.hysteriaObfsPassword)
                    putExtra(Service.EXTRA_MTU, model.mtuSize.value)
                    putExtra("bypass_iran", model.bypassIran.value)
                    putExtra("bypass_private_ips", model.bypassPrivateIps.value)
                    putStringArrayListExtra(Service.EXTRA_EXCLUDED_APPS, ArrayList(model.excludedApps.value))
                })
                await("CONNECTED")
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(75)
                while (System.nanoTime() < end && (!IpAddressValidator.isValid(model.connectionInfo.value.stats.publicIp) ||
                    model.connectionInfo.value.stats.ping <= 0)) Thread.sleep(200)
                assertTrue("Live exit lookup failed after reconnect", IpAddressValidator.isValid(model.connectionInfo.value.stats.publicIp))
                assertTrue("Live warm ping failed after reconnect", model.connectionInfo.value.stats.ping > 0)
                context.startService(Intent(context, Service::class.java).setAction(Service.ACTION_DISCONNECT))
                await("DISCONNECTED")
            }
        } finally {
            context.startService(Intent(context, Service::class.java).setAction(Service.ACTION_DISCONNECT))
            context.unregisterReceiver(receiver)
            instrumentation.runOnMainSync { store.clear() }
            activity.finish()
        }
    }
}

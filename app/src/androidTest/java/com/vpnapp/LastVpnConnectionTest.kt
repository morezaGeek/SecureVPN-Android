package com.vpnapp

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.data.ProfileRepository
import com.vpnapp.data.VpnProfile
import com.vpnapp.data.VpnProtocol
import com.vpnapp.vpn.LanShareSettings
import com.vpnapp.vpn.LastVpnConnection
import com.vpnapp.vpn.TunnelPreferences
import com.vpnapp.vpn.VpnConnectionService
import com.vpnapp.vpn.singbox.TunnelDns
import com.vpnapp.vpn.singbox.TunnelDnsSettings
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LastVpnConnectionTest {
    @Test fun restoresOnlyAnExistingProfileWithCurrentDnsAndLanSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = ProfileRepository.getInstance(context)
        val profiles = repository.loadProfiles()
        val saved = AtomicFile(File(context.noBackupFilesDir, "last-vpn-connection"))
        val previous = runCatching { saved.readFully() }.getOrNull()
        val prefs = context.getSharedPreferences("vpn_app_prefs", 0)
        val lan = context.getSharedPreferences("lan_share", 0)
        val previousPrefs = prefs.all.toMap()
        val previousLan = lan.all.toMap()
        val profile = VpnProfile(name = "Restore diagnostic only", serverAddress = "vpn.example.invalid")
        try {
            repository.saveProfiles(profiles + profile)
            val connect = Intent(context, VpnConnectionService::class.java)
                .setAction(VpnConnectionService.ACTION_CONNECT)
                .putExtra(VpnConnectionService.EXTRA_PROFILE_ID, profile.id)
                .putExtra(VpnConnectionService.EXTRA_SERVER_IP_INPUT, profile.serverAddress)
            TunnelDnsSettings(TunnelDns.DEFAULT_URL, true).writeTo(connect)
            LastVpnConnection.save(context, connect)
            prefs.edit().remove(TunnelDns.URL_KEY).remove(TunnelDns.FAKE_IP_KEY).remove(TunnelDns.LEGACY_SERVER_DNS_KEY)
                .putString("tunnel_dns_url", "https://old.example.invalid/dns-query").putBoolean("tunnel_dns_fake_ip", true)
                .putBoolean("bypass_iran", true).remove(TunnelPreferences.BYPASS_IRAN_KEY)
                .putInt(TunnelPreferences.OTHER_MTU_KEY, 1360).remove(TunnelPreferences.V2RAY_MTU_KEY).commit()
            LanShareSettings.save(context, false, 10810)
            val edited = profile.copy(name = "Edited restore diagnostic", serverAddress = "edited.example.invalid",
                port = 8443, protocol = VpnProtocol.VLESS, username = "edited-user", password = "edited-diagnostic-password",
                v2rayUuid = "edited-uuid", v2rayPath = "/edited", disableDtls = true)
            repository.saveProfiles(profiles + edited)

            val restored = requireNotNull(LastVpnConnection.restore(context))
            assertEquals(ComponentName(context, VpnConnectionService::class.java), restored.component)
            assertEquals(VpnConnectionService.ACTION_CONNECT, restored.action)
            assertEquals(profile.id, restored.getStringExtra(VpnConnectionService.EXTRA_PROFILE_ID))
            assertEquals(edited.serverAddress, restored.getStringExtra(VpnConnectionService.EXTRA_SERVER_IP_INPUT))
            assertEquals(edited.port, restored.getIntExtra(VpnConnectionService.EXTRA_SERVER_PORT, 0))
            assertEquals(edited.username, restored.getStringExtra(VpnConnectionService.EXTRA_USERNAME))
            assertEquals(edited.password, restored.getStringExtra(VpnConnectionService.EXTRA_PASSWORD))
            assertEquals(edited.v2rayUuid, restored.getStringExtra(VpnConnectionService.EXTRA_V2RAY_UUID))
            assertEquals(edited.v2rayPath, restored.getStringExtra(VpnConnectionService.EXTRA_V2RAY_PATH))
            assertTrue(restored.getBooleanExtra(VpnConnectionService.EXTRA_DISABLE_DTLS, false))
            assertFalse("The saved selection must not retain credentials", String(saved.readFully()).contains("edited-diagnostic-password"))
            assertEquals(TunnelDnsSettings("https://8.8.8.8/dns-query", false), TunnelDnsSettings.read(restored))
            assertFalse(restored.getBooleanExtra("bypass_iran", true))
            assertEquals(1500, restored.getIntExtra(VpnConnectionService.EXTRA_MTU, 0))
            assertEquals(10810, requireNotNull(LanShareSettings.fromIntent(restored)).port)

            prefs.edit().putBoolean(TunnelPreferences.BYPASS_IRAN_KEY, true)
                .putInt(TunnelPreferences.V2RAY_MTU_KEY, 1480)
                .putString(TunnelDns.URL_KEY, "https://resolver.example.invalid/dns-query")
                .putBoolean(TunnelDns.FAKE_IP_KEY, true).putBoolean(TunnelDns.LEGACY_SERVER_DNS_KEY, true).commit()
            val optedIn = requireNotNull(LastVpnConnection.restore(context))
            assertTrue(optedIn.getBooleanExtra("bypass_iran", false))
            assertEquals(1480, optedIn.getIntExtra(VpnConnectionService.EXTRA_MTU, 0))
            assertEquals(TunnelDnsSettings("https://resolver.example.invalid/dns-query", true, true), TunnelDnsSettings.read(optedIn))

            repository.saveProfiles(profiles + edited.copy(protocol = VpnProtocol.SSTP))
            val sstp = requireNotNull(LastVpnConnection.restore(context))
            assertEquals(1360, sstp.getIntExtra(VpnConnectionService.EXTRA_MTU, 0))

            repository.saveProfiles(profiles)
            assertNull("A deleted profile must not be reconnected by Always-on", LastVpnConnection.restore(context))
        } finally {
            repository.saveProfiles(profiles)
            restorePreferences(prefs, previousPrefs)
            restorePreferences(lan, previousLan)
            if (previous == null) saved.delete() else {
                val stream = saved.startWrite()
                try { stream.write(previous); saved.finishWrite(stream) }
                catch (error: Exception) { saved.failWrite(stream); throw error }
            }
        }
    }

    private fun restorePreferences(prefs: SharedPreferences, values: Map<String, *>) {
        val edit = prefs.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        check(edit.commit())
    }
}

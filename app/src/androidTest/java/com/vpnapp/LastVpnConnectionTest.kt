package com.vpnapp

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.data.ProfileRepository
import com.vpnapp.data.VpnProfile
import com.vpnapp.vpn.LanShareSettings
import com.vpnapp.vpn.LastVpnConnection
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
            prefs.edit().putString(TunnelDns.URL_KEY, "https://resolver.example.invalid/dns-query")
                .putBoolean(TunnelDns.FAKE_IP_KEY, false).putInt("mtu_size", 1360).commit()
            LanShareSettings.save(context, false, 10810)
            val edited = profile.copy(name = "Edited restore diagnostic", serverAddress = "edited.example.invalid",
                port = 8443, username = "edited-user", password = "edited-diagnostic-password",
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
            assertEquals(TunnelDnsSettings("https://resolver.example.invalid/dns-query", false), TunnelDnsSettings.read(restored))
            assertEquals(1360, restored.getIntExtra(VpnConnectionService.EXTRA_MTU, 0))
            assertEquals(10810, requireNotNull(LanShareSettings.fromIntent(restored)).port)

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

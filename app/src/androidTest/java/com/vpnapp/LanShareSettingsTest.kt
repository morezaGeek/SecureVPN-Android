package com.vpnapp

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.vpn.LanProxyProtocol
import com.vpnapp.vpn.LanShareSettings
import org.junit.Assert.*
import org.junit.Test

class LanShareSettingsTest {
    @Test fun oldSettingsKeepThePasswordAndNewCredentialsRoundTripAcrossServiceIntent() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("lan_share",0)
        val before=prefs.all.toMap()
        try {
            prefs.edit().clear().putBoolean("enabled",false).putInt("port",10811)
                .putString("password","old-generated-password").commit()
            val old=LanShareSettings.read(context)
            assertEquals(LanProxyProtocol.HTTP,old.protocol)
            assertEquals("securevpn",old.username)
            assertEquals("old-generated-password",old.password)
            val custom=old.copy(protocol=LanProxyProtocol.SOCKS5,username="my-user",password=" my own password ")
            LanShareSettings.save(context,custom)
            assertEquals(custom,LanShareSettings.read(context))
            assertEquals(custom,LanShareSettings.fromIntent(Intent().also(custom::writeTo)))
            val anonymous=custom.copy(socksAuthentication=false,username="",password="")
            LanShareSettings.save(context,anonymous)
            assertEquals(anonymous,LanShareSettings.fromIntent(Intent().also(anonymous::writeTo)))
            assertNull(LanShareSettings.fromIntent(Intent().also(custom::writeTo).putExtra("lan_port",0)))
            assertNull(LanShareSettings.fromIntent(Intent().also(custom::writeTo).putExtra("lan_password","")))
        } finally {
            prefs.edit().clear().apply {
                before.forEach { (key,value) -> when(value) {
                    is Boolean -> putBoolean(key,value)
                    is Int -> putInt(key,value)
                    is String -> putString(key,value)
                    is Long -> putLong(key,value)
                    is Float -> putFloat(key,value)
                } }
            }.commit()
        }
    }
}

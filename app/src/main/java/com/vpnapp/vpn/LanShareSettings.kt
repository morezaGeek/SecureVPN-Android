package com.vpnapp.vpn

import android.content.Context
import java.util.UUID

enum class LanProxyProtocol(val label: String) {
    HTTP("HTTP"), SOCKS5("SOCKS5");

    companion object {
        fun parse(value: String?): LanProxyProtocol = entries.firstOrNull { it.name == value } ?: HTTP
    }
}

data class LanShareSettings(
    val enabled: Boolean,
    val port: Int,
    val username: String,
    val password: String,
    val protocol: LanProxyProtocol = LanProxyProtocol.HTTP,
    val socksAuthentication: Boolean = true
) {
    fun validated(): LanShareSettings {
        require(port in 1024..65535) { "Choose a port from 1024 to 65535" }
        if (protocol == LanProxyProtocol.SOCKS5 && socksAuthentication) {
            require(username.toByteArray(Charsets.UTF_8).size in 1..255) {
                "SOCKS5 username must contain 1–255 UTF-8 bytes"
            }
            require(password.toByteArray(Charsets.UTF_8).size in 1..255) {
                "SOCKS5 password must contain 1–255 UTF-8 bytes"
            }
        }
        return this
    }

    fun writeTo(intent: android.content.Intent) {
        intent.putExtra("lan_enabled",enabled).putExtra("lan_port",port)
            .putExtra("lan_user",username).putExtra("lan_password",password)
            .putExtra("lan_protocol", protocol.name).putExtra("lan_socks_auth", socksAuthentication)
    }
    companion object {
        fun fromIntent(intent: android.content.Intent): LanShareSettings? {
            val password=intent.getStringExtra("lan_password") ?: return null
            val port=intent.getIntExtra("lan_port",10808)
            return runCatching {
                LanShareSettings(intent.getBooleanExtra("lan_enabled",false),port,
                    intent.getStringExtra("lan_user") ?: "securevpn",password,
                    LanProxyProtocol.parse(intent.getStringExtra("lan_protocol")),
                    intent.getBooleanExtra("lan_socks_auth", true)).validated()
            }.getOrNull()
        }
        fun read(context: Context): LanShareSettings {
            val prefs=context.getSharedPreferences("lan_share",0)
            val password=prefs.getString("password",null) ?: UUID.randomUUID().toString().replace("-","").take(20).also {
                prefs.edit().putString("password",it).commit()
            }
            return LanShareSettings(prefs.getBoolean("enabled",false),
                prefs.getInt("port",10808).takeIf { it in 1024..65535 } ?: 10808,
                prefs.getString("username", "securevpn") ?: "securevpn", password,
                LanProxyProtocol.parse(prefs.getString("protocol", null)),
                prefs.getBoolean("socks_auth", true))
        }
        fun save(context: Context, enabled: Boolean, port: Int) {
            save(context, read(context).copy(enabled = enabled, port = port))
        }
        fun save(context: Context, settings: LanShareSettings) {
            settings.validated()
            check(context.getSharedPreferences("lan_share",0).edit()
                .putBoolean("enabled", settings.enabled).putInt("port", settings.port)
                .putString("username", settings.username).putString("password", settings.password)
                .putString("protocol", settings.protocol.name)
                .putBoolean("socks_auth", settings.socksAuthentication).commit()) {
                "Could not save LAN sharing settings"
            }
        }
    }
}

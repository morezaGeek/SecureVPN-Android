package com.vpnapp.vpn.singbox

import android.content.Intent
import android.content.SharedPreferences

/** Explicit UI-to-service snapshot: SharedPreferences caches are not coherent across processes. */
data class TunnelDnsSettings(
    val url: String,
    val fakeIp: Boolean,
    val useLegacyServerDns: Boolean = false,
    val preferIpv6: Boolean = false
) {
    fun writeTo(intent: Intent) {
        intent.putExtra("runtime_dns_url", url)
            .putExtra("runtime_dns_fake", fakeIp)
            .putExtra("runtime_legacy_server_dns", useLegacyServerDns)
            .putExtra(PREFER_IPV6_KEY, preferIpv6)
    }

    companion object {
        const val PREFER_IPV6_KEY = "prefer_ipv6"

        fun fromPreferences(prefs: SharedPreferences): TunnelDnsSettings = TunnelDnsSettings(
            prefs.getString(TunnelDns.URL_KEY, TunnelDns.DEFAULT_URL) ?: TunnelDns.DEFAULT_URL,
            prefs.getBoolean(TunnelDns.FAKE_IP_KEY, TunnelDns.DEFAULT_FAKE_IP),
            prefs.getBoolean(TunnelDns.LEGACY_SERVER_DNS_KEY, false),
            prefs.getBoolean(PREFER_IPV6_KEY, false)
        )

        fun read(intent: Intent): TunnelDnsSettings? =
            intent.getStringExtra("runtime_dns_url")?.let {
                TunnelDnsSettings(
                    TunnelDns.validateUrl(it),
                    intent.getBooleanExtra("runtime_dns_fake", TunnelDns.DEFAULT_FAKE_IP),
                    intent.getBooleanExtra("runtime_legacy_server_dns", false),
                    intent.getBooleanExtra(PREFER_IPV6_KEY, false)
                )
            }
    }
}

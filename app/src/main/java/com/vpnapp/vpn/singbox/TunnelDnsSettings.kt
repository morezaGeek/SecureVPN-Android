package com.vpnapp.vpn.singbox

/** Explicit UI-to-service snapshot: SharedPreferences caches are not coherent across processes. */
data class TunnelDnsSettings(val url: String, val fakeIp: Boolean) {
    fun writeTo(intent: android.content.Intent) {
        intent.putExtra("runtime_dns_url",url).putExtra("runtime_dns_fake",fakeIp)
    }
    companion object {
        fun read(intent: android.content.Intent): TunnelDnsSettings? =
            intent.getStringExtra("runtime_dns_url")?.let {
                TunnelDnsSettings(TunnelDns.validateUrl(it),intent.getBooleanExtra("runtime_dns_fake",true))
            }
    }
}

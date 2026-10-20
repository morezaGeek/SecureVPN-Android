package com.vpnapp.vpn.singbox

import java.net.URI
import org.json.JSONObject

object TunnelDns {
    const val DEFAULT_URL = "https://1.1.1.1/dns-query"
    const val URL_KEY = "tunnel_dns_url"
    const val FAKE_IP_KEY = "tunnel_dns_fake_ip"

    fun validateUrl(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.fragment == null && (uri.port == -1 || uri.port in 1..65535)) {
            "Enter a valid HTTPS DNS-over-HTTPS URL"
        }
        return uri.toString()
    }

    fun server(value: String, tag: String, detour: String): JSONObject {
        val uri = URI(value.trim())
        require(uri.scheme in listOf("udp", "tcp", "https") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port in 1..65535)) {
            "Invalid DNS resolver"
        }
        return JSONObject().apply {
            put("tag", tag); put("type", uri.scheme); put("server", uri.host.removeSurrounding("[", "]"))
            if (detour != "direct") put("detour", detour)
            put("server_port", uri.port.takeIf { it != -1 } ?: if (uri.scheme == "https") 443 else 53)
            if (uri.scheme == "https") put("path", (uri.rawPath.takeUnless { it.isNullOrEmpty() || it == "/" }
                ?: "/dns-query") + (uri.rawQuery?.let { "?$it" } ?: ""))
            if (!uri.host.contains(':') && !uri.host.matches(Regex("[0-9.]+"))) put("domain_resolver", "direct-dns")
        }
    }

    fun echQueryName(value: String): String = value.substringBefore('+').trim().also {
        require(it.isNotEmpty() && it.matches(Regex("[A-Za-z0-9.-]+"))) { "Invalid ECH query domain" }
    }

    fun echResolver(value: String): JSONObject =
        server(value.substringAfter('+', "udp://1.1.1.1"), "ech-dns", "direct")
}

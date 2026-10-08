package com.vpnapp.vpn.singbox

import org.json.JSONArray
import org.json.JSONObject

/** Shared by the config generator and route regression tests. Order matters. */
object BypassRouting {
    fun rules(iran: Boolean, lan: Boolean, domains: List<String>, cidrs: List<String>): JSONArray =
        JSONArray().apply {
            // FakeIP must never be mistaken for a local/reserved address.
            put(JSONObject().put("ip_cidr", JSONArray().put("198.18.0.0/15")).put("outbound", "proxy"))
            // A private destination always obeys the LAN switch, even on an Iranian domain.
            put(JSONObject().put("ip_is_private", true).put("outbound", if (lan) "direct" else "proxy"))
            if (iran) {
                put(JSONObject().put("domain_suffix", JSONArray(listOf("ir") + domains)).put("outbound", "direct"))
                val publicCidrs = cidrs.filter(::isPublicIpv4Cidr)
                if (publicCidrs.isNotEmpty()) {
                    put(JSONObject().put("ip_cidr", JSONArray(publicCidrs)).put("outbound", "direct"))
                }
            }
        }

    fun isPublicIpv4Cidr(cidr: String): Boolean {
        val parts = cidr.split('/')
        val prefix = parts.getOrNull(1)?.toIntOrNull() ?: return false
        val octets = parts[0].split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 2 || prefix !in 0..32 || octets.size != 4 || octets.any { it !in 0..255 }) return false
        val address = octets.fold(0L) { value, octet -> (value shl 8) or octet.toLong() }
        val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        val start = address and mask
        val end = start or (0xffffffffL xor mask)
        val reserved = listOf(
            0x00000000L to 0x00ffffffL, 0x0a000000L to 0x0affffffL,
            0x64400000L to 0x647fffffL, 0x7f000000L to 0x7fffffffL,
            0xa9fe0000L to 0xa9feffffL, 0xac100000L to 0xac1fffffL,
            0xc0a80000L to 0xc0a8ffffL, 0xc6120000L to 0xc613ffffL,
            0xe0000000L to 0xffffffffL
        )
        return reserved.none { (low, high) -> start <= high && end >= low }
    }
}

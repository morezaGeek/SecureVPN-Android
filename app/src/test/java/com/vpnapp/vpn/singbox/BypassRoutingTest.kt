package com.vpnapp.vpn.singbox

import org.junit.Assert.*
import org.junit.Test

class BypassRoutingTest {
    @Test fun allSwitchCombinationsKeepLanIndependent() {
        for (iran in listOf(false, true)) for (lan in listOf(false, true)) {
            val rules = BypassRouting.rules(iran, lan, listOf("example.ir"),
                listOf("5.160.0.0/13", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
            assertEquals("proxy", rules.getJSONObject(0).getString("outbound"))
            val privateRule = rules.getJSONObject(1)
            assertTrue(privateRule.getBoolean("ip_is_private"))
            assertEquals(if (lan) "direct" else "proxy", privateRule.getString("outbound"))
            assertEquals(if (iran) 4 else 2, rules.length())
            if (iran) {
                assertEquals("direct", rules.getJSONObject(2).getString("outbound"))
                val cidrs = rules.getJSONObject(3).getJSONArray("ip_cidr")
                assertEquals(1, cidrs.length())
                assertEquals("5.160.0.0/13", cidrs.getString(0))
            }
        }
    }

    @Test fun filtersPrivateOverlapsAndMalformedCidrs() {
        listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10",
            "198.18.0.0/15", "127.0.0.0/8", "169.254.0.0/16", "0.0.0.0/0",
            "192.0.0.0/8", "invalid", "5.1.2.999/24", "5.1.2.3/33").forEach {
            assertFalse(it, BypassRouting.isPublicIpv4Cidr(it))
        }
        listOf("5.160.0.0/13", "172.32.0.0/16", "192.169.0.0/16", "8.8.8.8/32").forEach {
            assertTrue(it, BypassRouting.isPublicIpv4Cidr(it))
        }
    }

    @Test fun iranSwitchDoesNotLeaveRulesBehindWhenTurnedOff() {
        assertEquals(4, BypassRouting.rules(true, false, listOf("example.ir"), listOf("5.160.0.0/13")).length())
        val disabled = BypassRouting.rules(false, false, listOf("example.ir"), listOf("5.160.0.0/13"))
        assertEquals(2, disabled.length())
        assertEquals("proxy", disabled.getJSONObject(1).getString("outbound"))
    }
}

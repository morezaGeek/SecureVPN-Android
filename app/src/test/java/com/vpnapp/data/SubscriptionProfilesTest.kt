package com.vpnapp.data

import org.junit.Assert.*
import org.junit.Test

class SubscriptionProfilesTest {
    private fun profile(id: String, name: String = "France", host: String = "example.com") =
        VpnProfile(id = id, name = name, serverAddress = host, protocol = VpnProtocol.VLESS,
            subscriptionId = "sub", v2rayUuid = "user", v2rayNetwork = "xhttp", v2rayType = "auto")

    @Test fun refreshKeepsIdentityOrderAndManualMode() {
        val manual = profile("manual").copy(subscriptionId = null)
        val old = profile("selected").copy(v2rayXhttpModeOverride = "packet-up", latency = 91,
            createdAt = 12L, lastConnected = 34L)
        val other = profile("other").copy(subscriptionId = "other-sub")
        val merged = SubscriptionProfiles.merge(listOf(manual, old, other), listOf(profile("new-id")), "sub", true)
        assertEquals(listOf("manual", "selected", "other"), merged.map { it.id })
        assertEquals("packet-up", merged[1].v2rayType)
        assertEquals(91, merged[1].latency)
        assertEquals(12L, merged[1].createdAt)
        assertEquals(34L, merged[1].lastConnected)
    }

    @Test fun updatedCredentialsKeepSelectionButDiscardStalePingAndSmartResult() {
        val old = profile("selected").copy(v2rayXhttpModeOverride = "smart", v2raySmartXhttpMode = "stream-up", latency = 78)
        val fresh = profile("new").copy(v2rayUuid = "new-user")
        val merged = SubscriptionProfiles.merge(listOf(old), listOf(fresh), "sub", false).single()
        assertEquals("selected", merged.id)
        assertEquals("new-user", merged.v2rayUuid)
        assertEquals("smart", merged.v2rayXhttpModeOverride)
        assertNull(merged.v2raySmartXhttpMode)
        assertNull(merged.latency)
    }

    @Test fun duplicateNamesMatchEndpointsOnce() {
        val a = profile("a", host = "a.example")
        val b = profile("b", host = "b.example")
        val merged = SubscriptionProfiles.merge(listOf(a, b), listOf(b.copy(id = "x"), a.copy(id = "y")), "sub", true)
        assertEquals(listOf("b", "a"), merged.map { it.id })
        assertEquals(2, merged.map { it.id }.distinct().size)
    }

    @Test fun renamedEndpointKeepsIdentityAndRemovedEntriesDisappear() {
        val old = profile("selected")
        val removed = profile("removed", host = "gone.example")
        val merged = SubscriptionProfiles.merge(listOf(old, removed), listOf(profile("fresh", "New France")), "sub", false)
        assertEquals("selected", merged.single().id)
        assertEquals("New France", merged.single().name)
    }

    @Test fun changedServerDataIsUsedAndOtherSubscriptionsAreUntouched() {
        val old = profile("selected").copy(v2rayXhttpModeOverride = "packet-up")
        val other = profile("other").copy(subscriptionId = "another")
        val merged = SubscriptionProfiles.merge(listOf(old, other), listOf(profile("new", host = "updated.example")), "sub", true)
        assertEquals("selected", merged[0].id)
        assertEquals("updated.example", merged[0].serverAddress)
        assertEquals("packet-up", merged[0].v2rayType)
        assertEquals(other, merged[1])
    }
}

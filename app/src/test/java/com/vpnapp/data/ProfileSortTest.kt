package com.vpnapp.data

import org.junit.Assert.*
import org.junit.Test

class ProfileSortTest {
    private fun item(id: String, name: String = id, ping: Int? = null) =
        VpnProfile(id = id, name = name, serverAddress = "example.invalid", latency = ping)

    @Test fun providerOrderIsPreservedAndSortingNeverMutatesStorage() {
        val original = listOf(item("z"), item("a"))
        assertEquals(listOf("z", "a"), ProfileSort.DEFAULT.apply(original).map { it.id })
        ProfileSort.NAME.apply(original)
        assertEquals(listOf("z", "a"), original.map { it.id })
        assertEquals(ProfileSort.DEFAULT, ProfileSort.stored("obsolete"))
    }

    @Test fun alphabeticalOrderIgnoresCaseAndKeepsEqualNamesStable() {
        val items = listOf(item("z", "Zulu"), item("b", "beta"), item("a", "Alpha"), item("a2", "alpha"))
        assertEquals(listOf("a", "a2", "b", "z"), ProfileSort.NAME.apply(items).map { it.id })
    }

    @Test fun failedAndUnmeasuredServersFollowSuccessfulPings() {
        val items = listOf(item("failed", ping = -1), item("slow", ping = 200), item("none"), item("fast", ping = 10), item("equal", ping = 200))
        assertEquals(listOf("fast", "slow", "equal", "failed", "none"), ProfileSort.PING.apply(items).map { it.id })
    }
}

package com.vpnapp.ads

import org.junit.Assert.*
import org.junit.Test

class AdCyclePolicyTest {
    private fun cycle(state: AdCycleState, id: Long) = AdCyclePolicy.disconnected(AdCyclePolicy.connected(state, id), id)
    @Test fun twoSuccessfulCompleteSessionsAreRequired() {
        val first = cycle(AdCycleState(), 10)
        assertFalse(AdCyclePolicy.eligible(first, 10_000, true, true, true))
        val second = cycle(first, 11)
        assertTrue(AdCyclePolicy.eligible(second, 10_000, true, true, true))
    }
    @Test fun duplicateDisconnectFailedConnectAndOldSessionsDoNotCount() {
        val first = cycle(AdCycleState(), 10)
        assertEquals(first, AdCyclePolicy.disconnected(first, 10))
        assertEquals(AdCycleState(), AdCyclePolicy.disconnected(AdCycleState(), 20))
        val active = AdCyclePolicy.connected(first, 11)
        assertEquals(active, AdCyclePolicy.disconnected(active, 10))
        assertEquals(active, AdCyclePolicy.connected(active, 0))
        assertEquals(1, AdCyclePolicy.disconnected(active, 0).completedCycles)
    }
    @Test fun neverEligibleOutsideForegroundHomeOrWhileVpnIsActive() {
        val state = AdCycleState(completedCycles = 2)
        assertFalse(AdCyclePolicy.eligible(state, 1000, false, true, true))
        assertFalse(AdCyclePolicy.eligible(state, 1000, true, false, true))
        assertFalse(AdCyclePolicy.eligible(state, 1000, true, true, false))
        assertFalse(AdCyclePolicy.eligible(state.copy(successfulSession = 10), 1000, true, true, true))
    }
    @Test fun rapidReconnectsAndClockChangesCannotBypassCooldown() {
        val state = AdCycleState(completedCycles = 2, lastAttemptMillis = 10_000)
        assertFalse(AdCyclePolicy.eligible(state, 9_999, true, true, true))
        assertFalse(AdCyclePolicy.eligible(state, 129_999, true, true, true))
        assertTrue(AdCyclePolicy.eligible(state, 130_000, true, true, true))
    }
    @Test fun pendingCyclesAreCappedRatherThanQueuedForBackToBackAds() {
        var state = AdCycleState()
        repeat(20) { state = cycle(state, it.toLong() + 1) }
        assertEquals(2, state.completedCycles)
    }
}

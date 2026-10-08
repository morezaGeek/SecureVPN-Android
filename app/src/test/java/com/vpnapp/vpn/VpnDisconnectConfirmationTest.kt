package com.vpnapp.vpn

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class VpnDisconnectConfirmationTest {
    @Test fun idleSnapshotDoesNotWaitForAnyProcessOrExternalVpn() = runBlocking {
        val forbiddenCheck: (Int) -> Boolean = { error("An idle status must not inspect running processes") }
        assertFalse(VpnDisconnectConfirmation.requiresWait(0, forbiddenCheck))
        assertTrue(VpnDisconnectConfirmation.awaitExit(0, forbiddenCheck))
    }

    @Test fun completedShutdownDoesNotLeaveDisconnecting() = runBlocking {
        assertFalse(VpnDisconnectConfirmation.requiresWait(123, { false }))
        assertTrue(VpnDisconnectConfirmation.awaitExit(123, { false }))
        val checks = java.util.concurrent.atomic.AtomicInteger()
        assertTrue(VpnDisconnectConfirmation.requiresWait(123, { true }))
        assertTrue(withTimeout(2000) {
            VpnDisconnectConfirmation.awaitExit(123, { checks.incrementAndGet() < 3 })
        })
    }

    @Test fun missingShutdownConfirmationHasABoundedWait() = runBlocking {
        assertFalse(withTimeout(2000) {
            VpnDisconnectConfirmation.awaitExit(123, { true }, timeoutMillis = 30)
        })
    }
}

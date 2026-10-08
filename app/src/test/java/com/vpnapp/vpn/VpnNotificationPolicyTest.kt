package com.vpnapp.vpn

import org.junit.Assert.*
import org.junit.Test

class VpnNotificationPolicyTest {
    @Test fun restoresOnlyCurrentLiveSession() {
        assertTrue(VpnNotificationPolicy.shouldRestore(10, 10, true, false, false))
        assertTrue(VpnNotificationPolicy.shouldRestore(10, 10, false, true, false))
        assertFalse(VpnNotificationPolicy.shouldRestore(10, 11, true, false, false))
        assertFalse(VpnNotificationPolicy.shouldRestore(10, 10, false, false, false))
        assertFalse(VpnNotificationPolicy.shouldRestore(10, 10, true, false, true))
        assertFalse(VpnNotificationPolicy.shouldRestore(0, 0, true, false, false))
    }
}

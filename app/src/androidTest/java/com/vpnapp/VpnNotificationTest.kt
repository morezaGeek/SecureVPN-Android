package com.vpnapp

import android.app.Notification
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.vpn.VpnNotificationFactory
import org.junit.Assert.*
import org.junit.Test

class VpnNotificationTest {
    @Test fun connectedNotificationRemainsOngoingWithExplicitDisconnectAndDismissalRecovery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notification = VpnNotificationFactory.create(context, "vpn_channel", "France", "198.51.100.10", "Connected", "FR", 10)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(0, notification.flags and Notification.FLAG_AUTO_CANCEL)
        assertEquals(Notification.CATEGORY_SERVICE, notification.category)
        assertNotNull(notification.contentIntent)
        assertNotNull(notification.deleteIntent)
        assertEquals("France - Connected", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(1, notification.actions.size)
        assertEquals("Disconnect", notification.actions[0].title.toString())
    }
}

package com.vpnapp

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.vpnapp.ui.screens.NetworkToolsScreen
import com.vpnapp.ui.screens.UsageHistoryScreen
import com.vpnapp.ui.theme.VPNAppTheme
import com.vpnapp.vpn.LanProxyProtocol
import com.vpnapp.vpn.LanShareSettings
import com.vpnapp.vpn.LanProxyStatus
import com.vpnapp.vpn.VpnConnectionService
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NetworkScreensTest {
    @get:Rule val compose=createComposeRule()
    @Test fun lanSettingsAndSystemKillSwitchInstructionsAreReachable() {
        compose.setContent { VPNAppTheme { NetworkToolsScreen(false,false,false) {} } }
        compose.onNodeWithText("Share VPN over LAN").assertIsDisplayed()
        compose.onNodeWithText("Proxy port · 1024–65535").assertExists()
        compose.onNodeWithText("Configure Android Kill Switch").performScrollTo().performClick()
        compose.onNodeWithText("Enable system protection").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
    }
    @Test fun historySupportsDateRangesAndWeeklyGrouping() {
        compose.setContent { VPNAppTheme { UsageHistoryScreen {} } }
        compose.onNodeWithText("Yesterday").performClick().assertIsSelected()
        compose.onNodeWithText("Today").assertIsNotSelected()
        compose.onNodeWithText("Custom").performClick()
        compose.onNodeWithText("From · YYYY-MM-DD").assertIsDisplayed()
        compose.onNodeWithText("To · YYYY-MM-DD").assertIsDisplayed()
        compose.onNodeWithText("Group by week").performScrollTo().assertIsDisplayed()
    }

    @Test fun proxyTypeAndUserCredentialsCanBeChangedAndSaved() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val original=LanShareSettings.read(context)
        try {
            LanShareSettings.save(context, original.copy(enabled=false, protocol=LanProxyProtocol.HTTP))
            compose.setContent { VPNAppTheme { NetworkToolsScreen(false,false,false) {} } }
            compose.onNodeWithTag("lan_protocol_HTTP").assertIsSelected()
            compose.onNodeWithTag("lan_password").assertDoesNotExist()
            compose.onNodeWithTag("lan_protocol_SOCKS5").performClick()
            compose.onNodeWithTag("lan_socks_auth").performScrollTo().assertIsOn()
            compose.onNodeWithTag("lan_username").performScrollTo().performTextReplacement("my-lan-user")
            compose.onNodeWithTag("lan_password").performScrollTo().performTextReplacement("a password I chose")
            compose.onNodeWithTag("lan_save").performScrollTo().performClick()
            compose.runOnIdle {
                val saved=LanShareSettings.read(context)
                assertEquals(LanProxyProtocol.SOCKS5,saved.protocol)
                assertEquals("my-lan-user",saved.username)
                assertEquals("a password I chose",saved.password)
                assertEquals(saved,LanShareSettings.fromIntent(android.content.Intent().also(saved::writeTo)))
            }
            compose.onNodeWithTag("lan_socks_auth").performScrollTo().performClick()
            compose.onNodeWithTag("lan_password").assertDoesNotExist()
            compose.onNodeWithTag("lan_save").performScrollTo().performClick()
            compose.runOnIdle { assertFalse(LanShareSettings.read(context).socksAuthentication) }
            compose.onNodeWithTag("lan_protocol_HTTP").performScrollTo().performClick()
            compose.onNodeWithTag("lan_save").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(LanProxyProtocol.HTTP,LanShareSettings.read(context).protocol) }
        } finally { LanShareSettings.save(context,original) }
    }

    @Test fun realWorkerStatusAndActionableErrorsAreDisplayed() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { VPNAppTheme { NetworkToolsScreen(false,false,false) {} } }
        compose.waitForIdle()
        val status=LanProxyStatus("LISTENING","HTTP",10808,3,1,
            "Client used SOCKS5, but this port is HTTP. Select HTTP on the other device.")
        val event=android.content.Intent(VpnConnectionService.ACTION_LAN_SHARE_STATUS).setPackage(context.packageName)
            .also(status::writeTo)
        assertEquals(status,LanProxyStatus.read(event))
        context.sendBroadcast(event)
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Incoming connections: 3 · Forwarded: 1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("lan_runtime_status").performScrollTo().assertTextEquals("Listening · HTTP · port 10808")
        compose.onNodeWithTag("lan_runtime_error").performScrollTo().assertTextEquals(status.lastError!!)
    }
}

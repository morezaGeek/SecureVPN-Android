package com.vpnapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.vpnapp.data.*
import com.vpnapp.ui.screens.HomeScreen
import com.vpnapp.ui.theme.VPNAppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomePingControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun busyCounterIsClickableToStopAndHistoryIsReachableFromHome() {
        val ids = (0 until 150).map { "profile-$it" }.toSet()
        val state = mutableStateOf(LatencyTestState(pendingIds = ids, activeIds = ids.take(8).toSet(), total = 150))
        var stopped = 0; var started = 0; var history = 0
        compose.setContent {
            VPNAppTheme {
                Box(Modifier.width(360.dp)) {
                    HomeScreen(connectionInfo = VpnConnectionInfo(), profiles = emptyList(), selectedProfile = null,
                        logs = emptyList(), subscriptions = emptyList(),
                        onToggleConnection = {}, onSelectProfile = {}, onEditProfile = {}, onDeleteProfile = {},
                        onAddProfile = {}, onMeasureLatency = { started++ }, onTestCurrentLatency = {}, onNavigateToLogs = {},
                        onNavigateToSettings = {}, onAddSubscription = { _, _ -> }, onRefreshSubscription = {},
                        onRefreshAllSubscriptions = {}, onDeleteSubscription = {}, onUpdateSubscription = {},
                        latencyTestState = state.value, onStopLatency = { stopped++; state.value = LatencyTestState() },
                        onUsageHistory = { history++ })
                }
            }
        }
        compose.onNodeWithContentDescription("History of usage").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, history) }
        compose.onNodeWithText("8 testing in parallel · Tap counter to stop").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop ping tests").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stopped); assertEquals(0, started) }
        compose.onNodeWithContentDescription("Start ping tests").performClick()
        compose.runOnIdle { assertEquals(1, started) }
    }
}

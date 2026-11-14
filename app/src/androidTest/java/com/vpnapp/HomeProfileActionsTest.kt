package com.vpnapp

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.vpnapp.data.VpnConnectionInfo
import com.vpnapp.data.VpnProfile
import com.vpnapp.ui.screens.HomeScreen
import com.vpnapp.ui.theme.VPNAppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class HomeProfileActionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun longPressTargetsActionsWithoutSelectingTheConnection() {
        val selected = VpnProfile(id = "one", name = "First config", serverAddress = "one.example")
        val target = VpnProfile(id = "two", name = "Second config", serverAddress = "two.example")
        var tapped: VpnProfile? = null
        var edited: VpnProfile? = null
        var shared: VpnProfile? = null
        var deleted: String? = null
        compose.setContent {
            VPNAppTheme {
                HomeScreen(
                    connectionInfo = VpnConnectionInfo(), profiles = listOf(selected, target),
                    selectedProfile = selected, logs = emptyList(), subscriptions = emptyList(),
                    onToggleConnection = {}, onSelectProfile = { tapped = it },
                    onEditProfile = { edited = it }, onShareProfile = { shared = it },
                    onDeleteProfile = { deleted = it }, onAddProfile = {}, onMeasureLatency = {},
                    onTestCurrentLatency = {}, onNavigateToLogs = {}, onNavigateToSettings = {},
                    onAddSubscription = { _, _ -> }, onRefreshSubscription = {},
                    onRefreshAllSubscriptions = {}, onDeleteSubscription = {}, onUpdateSubscription = {}
                )
            }
        }
        val cardName = compose.onNodeWithText(target.name, useUnmergedTree = true)
        fun hold() = cardName.performScrollTo().performTouchInput { longClick() }
        val edit = compose.onNodeWithText("Edit")
        val share = compose.onNodeWithText("Share")
        val delete = compose.onNodeWithText("Delete")
        edit.assertDoesNotExist()
        hold()
        edit.assertIsDisplayed()
        share.assertIsDisplayed()
        delete.assertIsDisplayed()
        compose.runOnIdle {
            assertNull(tapped)
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        edit.assertDoesNotExist()
        hold()
        edit.performClick()
        compose.runOnIdle { assertEquals(target, edited) }
        hold()
        share.performClick()
        compose.runOnIdle { assertEquals(target, shared) }
        hold()
        delete.performClick()
        compose.onNodeWithText("Delete Profile?").assertIsDisplayed()
        compose.runOnIdle { assertNull(deleted) }
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle { assertEquals(target.id, deleted) }
        compose.onNode(hasText(target.name) and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(target, tapped) }
    }
}

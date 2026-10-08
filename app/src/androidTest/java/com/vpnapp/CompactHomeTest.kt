package com.vpnapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.data.*
import com.vpnapp.ui.screens.HomeScreen
import com.vpnapp.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CompactHomeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactConnectedHomeShowsActualProfileAndOneTrafficRowInBothThemes() {
        val actual = VpnProfile(id = "active", name = "France • My connected profile", serverAddress = "example.com", subscriptionId = "sale")
        val selected = actual.copy(id = "selected", name = "Different selection", subscriptionId = "gozar")
        val chosen = mutableStateOf<String?>("sale")
        val previous = ThemeState.themeMode
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.2f)) {
                VPNAppTheme {
                    Box(Modifier.width(360.dp)) {
                        HomeScreen(
                            connectionInfo = VpnConnectionInfo(ConnectionState.CONNECTED, actual,
                                ConnectionStats(downloadSpeed = 1_500_000, uploadSpeed = 32_000,
                                    totalDownloaded = 10_000_000, totalUploaded = 2_000_000,
                                    originalIp = "203.0.113.10", publicIp = "198.51.100.20", countryCode = "FR", ping = 91, mtu = 1400)),
                            profiles = listOf(actual, selected), selectedProfile = selected, logs = emptyList(),
                            subscriptions = listOf(VpnSubscription("gozar", "GozarVPN", "https://example.com/a"),
                                VpnSubscription("sale", "Sale", "https://example.com/b")),
                            selectedSubscriptionId = chosen.value, onSelectSubscription = { chosen.value = it },
                            onToggleConnection = {}, onSelectProfile = {}, onEditProfile = {}, onDeleteProfile = {},
                            onAddProfile = {}, onMeasureLatency = {}, onTestCurrentLatency = {}, onNavigateToLogs = {},
                            onNavigateToSettings = {}, onAddSubscription = { _, _ -> }, onRefreshSubscription = {},
                            onRefreshAllSubscriptions = {}, onDeleteSubscription = {}, onUpdateSubscription = {})
                    }
                }
            }
        }
        try {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                compose.runOnIdle { ThemeState.themeMode = mode }
                compose.onNodeWithText("Connected to").assertIsDisplayed()
                compose.onAllNodesWithText(actual.name)[0].assertIsDisplayed()
                compose.onNodeWithText("Private IP").assertDoesNotExist()
                compose.onNodeWithText("Transport").assertDoesNotExist()
                compose.onNodeWithText("Origin IP:").assertIsDisplayed()
                compose.onNodeWithText("1.5 MB/s").assertIsDisplayed()
                compose.onNodeWithText("32 KB/s").assertIsDisplayed()
                compose.onNodeWithText("12.0 MB").assertIsDisplayed()
                val row = listOf("1.5 MB/s", "32 KB/s", "12.0 MB").map {
                    compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.center.y
                }
                assertTrue("Traffic must share a single row", row.max() - row.min() < 2f)
                compose.onNodeWithText("12.0 MB").performClick()
                compose.onNodeWithText("Traffic for this connection").assertIsDisplayed()
                compose.onNodeWithText("Downloaded").assertIsDisplayed()
                compose.onNodeWithText("Uploaded").assertIsDisplayed()
                compose.onNodeWithText("10.0 MB").assertIsDisplayed()
                compose.onNodeWithText("2.0 MB").assertIsDisplayed()
                compose.onNodeWithText("Close").performClick()
                compose.onNodeWithText("Traffic for this connection").assertDoesNotExist()
                compose.onNodeWithText("Subscriptions").assertIsDisplayed()
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val directory = java.io.File(instrumentation.targetContext.filesDir, "review-screenshots").apply { mkdirs() }
                compose.waitForIdle()
                compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                    java.io.File(directory, "compact-home-${mode.name.lowercase()}.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
        } finally { compose.runOnIdle { ThemeState.themeMode = previous } }
    }
}

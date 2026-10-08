package com.vpnapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vpnapp.data.*
import com.vpnapp.ui.components.ProfileCard
import com.vpnapp.ui.theme.VPNAppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProfileCardLayoutTest {
    @get:Rule val compose = createComposeRule()
    @Test fun longNameUsesTwoLinesWithoutDateAndTestingReplacesOldLatency() {
        val name = "France Switzerland Premium connection with a very long name"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.2f)) {
                VPNAppTheme {
                    Box(Modifier.width(360.dp)) {
                        ProfileCard(VpnProfile(name = name, serverAddress = "example.com", latency = 999,
                            lastConnected = 1_759_363_200_000), false, ConnectionState.DISCONNECTED,
                            {}, {}, {}, isTestingLatency = true, onShare = {})
                    }
                }
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(name, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(2, results.single().lineCount)
        compose.onNodeWithText("999ms", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Testing…", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Oct 2", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Edit", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Share $name", useUnmergedTree = true).assertIsDisplayed()
    }
}

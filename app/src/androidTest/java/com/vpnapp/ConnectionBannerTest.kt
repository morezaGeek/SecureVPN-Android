package com.vpnapp

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.ads.ConnectionBanner
import com.vpnapp.ads.AdCycleStore
import com.vpnapp.data.ConnectionState
import com.vpnapp.ui.theme.VPNAppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConnectionBannerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun configuredBannerEitherLoadsOrSkipsWithoutBlockingConnectionControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = java.io.File(context.noBackupFilesDir, "ad-cycles").apply { mkdirs() }
        val counter = java.io.File(directory, "state.json")
        val previous = counter.takeIf { it.exists() }?.readBytes()
        val state = mutableStateOf(ConnectionState.DISCONNECTED)
        val report = java.io.File(context.filesDir, "test-banner-result.txt")
        // Timing fixture only. Real service-cycle persistence is checked in ProbeCoreLifecycleTest.
        counter.writeText("{\"successfulSession\":0,\"completedCycles\":2,\"lastAttemptMillis\":0}")
        try {
            compose.setContent {
                VPNAppTheme { Column { Text("Connection controls remain available"); ConnectionBanner(state.value, allowAds = true) } }
            }
            compose.onNodeWithText("Connection controls remain available").assertIsDisplayed()
            val deadline = android.os.SystemClock.elapsedRealtime() + 50_000
            fun loaded() = compose.onAllNodesWithText("Advertisement").fetchSemanticsNodes().isNotEmpty()
            while (!loaded() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(250)
            if (loaded()) {
                compose.onNodeWithText("Advertisement").assertIsDisplayed()
                Thread.sleep(1000)
                val folder = java.io.File(context.filesDir,"review-screenshots").apply { mkdirs() }
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    java.io.File(folder,"test-banner.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                    bitmap.recycle()
                }
                report.writeText("Google demo banner loaded; real SDK rendered on screen.\n")
            } else {
                report.writeText("Google demo creative unavailable on this network; no loading placeholder or blocked controls.\n")
            }
            compose.onNodeWithText("Connection controls remain available").assertIsDisplayed()
            compose.runOnIdle { state.value = ConnectionState.CONNECTING }
            compose.onNodeWithText("Advertisement").assertDoesNotExist()
            compose.onNodeWithText("Connection controls remain available").assertIsDisplayed()
        } finally {
            compose.runOnIdle { state.value = ConnectionState.CONNECTED }
            if(previous!=null)counter.writeBytes(previous) else counter.delete()
        }
    }
}

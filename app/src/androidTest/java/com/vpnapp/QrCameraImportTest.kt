package com.vpnapp

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.data.ProfileRepository
import com.vpnapp.ui.theme.VPNAppTheme
import com.vpnapp.viewmodel.VpnViewModel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class QrCameraImportTest {
    @get:Rule val compose = createComposeRule()
    @Test fun cameraScanShowsPreviewAndImportsOnlyOnConfirmation() {
        // Run explicitly on the emulator configured with the synthetic camera-qr.png input.
        assumeTrue(InstrumentationRegistry.getArguments().getString("qrCameraFixture") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val repo = ProfileRepository.getInstance(app)
        val savedProfiles = repo.loadProfiles(); val savedSubs = repo.loadSubscriptions()
        val savedSelected = repo.getSelectedProfileId(); val savedFilter = repo.getSelectedSubscriptionId()
        val store = ViewModelStore(); lateinit var vm: VpnViewModel
        try {
            repo.saveProfiles(emptyList()); repo.saveSubscriptions(emptyList())
            repo.setSelectedProfileId(null); repo.setSelectedSubscriptionId(null)
            instrumentation.runOnMainSync { vm = VpnViewModel(app); store.put("qr-camera", vm) }
            compose.setContent { VPNAppTheme { VpnApp(vm) } }
            fun openScan() {
                compose.onNodeWithText("Add", substring = false).performScrollTo().performClick()
                compose.onNodeWithText("Scan QR").performClick()
            }
            fun permissionButton(id: String) {
                val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
                var node: android.view.accessibility.AccessibilityNodeInfo? = null
                while (node == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                    node = instrumentation.uiAutomation.rootInActiveWindow
                        ?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/$id")?.firstOrNull()
                    if (node == null) android.os.SystemClock.sleep(50)
                }
                assertNotNull("Camera permission dialog button $id", node)
                assertTrue(node!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            }
            val needsPermission = app.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED
            openScan()
            if (needsPermission) {
                permissionButton("permission_deny_button")
                compose.onNodeWithText("Camera permission").assertIsDisplayed()
                compose.onNodeWithText("Cancel").performClick()
                assertTrue(vm.profiles.value.isEmpty())
                openScan()
                permissionButton("permission_allow_foreground_only_button")
            }
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText("Import configuration").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            compose.onNodeWithText("QR-Camera-Test").assertIsDisplayed()
            assertTrue("Scanned link must wait for confirmation", vm.profiles.value.isEmpty())
            compose.onNodeWithText("Import", substring = false).performClick()
            compose.runOnIdle {
                assertEquals(1, vm.profiles.value.size)
                assertEquals("QR-Camera-Test", vm.profiles.value.single().name)
            }
            compose.onNodeWithContentDescription("Share QR-Camera-Test").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("QR code for QR-Camera-Test").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Copy link").performScrollTo().performClick()
            compose.onNodeWithText("Close").performClick()
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            repo.saveProfiles(savedProfiles); repo.saveSubscriptions(savedSubs)
            repo.setSelectedProfileId(savedSelected); repo.setSelectedSubscriptionId(savedFilter)
        }
    }
}

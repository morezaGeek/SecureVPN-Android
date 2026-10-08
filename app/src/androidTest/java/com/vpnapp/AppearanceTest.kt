package com.vpnapp

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.ui.theme.ThemeMode
import com.vpnapp.ui.theme.ThemeState
import org.junit.Assert.*
import org.junit.Test

class AppearanceTest {
    @Test fun originAndSettingsAreVisibleInBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val previous = ThemeState.themeMode
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val directory = java.io.File(instrumentation.targetContext.filesDir, "review-screenshots").apply { mkdirs() }
        fun waitForFrame() { instrumentation.waitForIdleSync(); Thread.sleep(600) }
        fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString()?.contains(label) == true || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
            return null
        }
        fun visible(label: String): AccessibilityNodeInfo {
            var node: AccessibilityNodeInfo? = null
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            while (node == null && System.nanoTime() < deadline) {
                node = find(instrumentation.uiAutomation.rootInActiveWindow, label)
                if (node == null) Thread.sleep(250)
            }
            assertNotNull("Missing visible element: $label", node)
            val bounds = Rect()
            node!!.getBoundsInScreen(bounds)
            assertFalse(bounds.isEmpty)
            return node
        }
        fun screenshot(name: String) {
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                java.io.File(directory, "$name.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        try {
            instrumentation.runOnMainSync { ThemeState.themeMode = ThemeMode.LIGHT }; waitForFrame()
            visible("Origin IP:"); screenshot("home-light")
            instrumentation.runOnMainSync { ThemeState.themeMode = ThemeMode.DARK }; waitForFrame()
            visible("Origin IP:"); screenshot("home-dark")
            var settingsNode: AccessibilityNodeInfo? = visible("Settings")
            while (settingsNode != null && !settingsNode.isClickable) settingsNode = settingsNode.parent
            assertNotNull("Settings click target", settingsNode)
            assertTrue(settingsNode!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)); waitForFrame()
            visible("Bypass LAN"); screenshot("settings-dark")
            instrumentation.runOnMainSync { ThemeState.themeMode = ThemeMode.LIGHT }; waitForFrame()
            visible("Bypass LAN"); screenshot("settings-light")
        } finally {
            instrumentation.runOnMainSync { ThemeState.themeMode = previous; activity.finish() }
        }
    }
}

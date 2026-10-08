package com.vpnapp

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.ui.components.LinkShareDialog
import com.vpnapp.ui.components.LinkSharePayload
import com.vpnapp.ui.theme.VPNAppTheme
import com.vpnapp.ui.theme.ThemeMode
import com.vpnapp.ui.theme.ThemeState
import org.junit.Rule
import org.junit.Test

class LinkShareDialogTest {
    @get:Rule val compose = createComposeRule()
    @Test fun qrIsDisplayedAndOversizePayloadStillAllowsCopyAndTextSharing() {
        val payload = androidx.compose.runtime.mutableStateOf(LinkSharePayload("France", "https://example.com/sub/test"))
        compose.setContent { VPNAppTheme { LinkShareDialog(payload.value) {} } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("QR code for France").fetchSemanticsNodes().isNotEmpty() }
        val previousTheme = ThemeState.themeMode
        try {
            for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                compose.runOnIdle { ThemeState.themeMode = theme }
                // PixelCopy may precede the hardware renderer's theme redraw. Verify the QR pixels too.
                compose.waitUntil(5_000) {
                    val qr = compose.onNodeWithContentDescription("QR code for France").captureToImage().asAndroidBitmap()
                    val pixels = IntArray(qr.width * qr.height)
                    qr.getPixels(pixels, 0, qr.width, 0, 0, qr.width, qr.height)
                    try {
                        com.google.zxing.MultiFormatReader().decode(com.google.zxing.BinaryBitmap(
                            com.google.zxing.common.HybridBinarizer(com.google.zxing.RGBLuminanceSource(qr.width, qr.height, pixels))))
                            .text == payload.value.link
                    } catch (_: com.google.zxing.NotFoundException) { false }
                }
                val directory = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                    "review-screenshots").apply { mkdirs() }
                compose.onNode(isDialog()).captureToImage().asAndroidBitmap().let { image ->
                    java.io.File(directory, "qr-share-${theme.name.lowercase()}.png").outputStream().use {
                        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
        } finally { compose.runOnIdle { ThemeState.themeMode = previousTheme } }
        compose.onNodeWithText("Share QR image").performScrollTo().assertIsEnabled()
        compose.runOnIdle { payload.value = LinkSharePayload("Large link", "vless://" + "x".repeat(5000)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("This link is too long for one QR code. Copy or share the full link below.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Copy link").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Share link").assertIsEnabled()
        compose.onNodeWithText("Share QR image").assertIsNotEnabled()
    }
}

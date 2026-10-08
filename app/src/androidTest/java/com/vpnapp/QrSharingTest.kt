package com.vpnapp

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.vpnapp.sharing.QrSharing
import org.junit.Assert.*
import org.junit.Test

class QrSharingTest {
    private fun decode(bitmap: android.graphics.Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(
            RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))).text
    }
    @Test fun qrAndSharedPngDecodeToOriginalLinkWithScopedUriPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val text = "vless://test@example.com:443?type=xhttp&mode=stream-up#فرانسه+50%"
        val bitmap = QrSharing.bitmap(text)
        try {
            assertEquals(text, decode(bitmap))
            val intent = QrSharing.imageIntent(context, bitmap)
            assertEquals("image/png", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content", uri.scheme); assertEquals("${context.packageName}.qr-files", uri.authority)
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            context.contentResolver.openInputStream(uri)!!.use { stream ->
                val image = BitmapFactory.decodeStream(stream)
                assertEquals(text, decode(image)); image.recycle()
            }
            val textIntent = QrSharing.textIntent(text)
            assertEquals(text, textIntent.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals("text/plain", textIntent.type)
            // Fixture used by an optional end-to-end camera test on an emulator with imagefile camera input.
            val fixture = java.io.File(context.filesDir, "review-screenshots").apply { mkdirs() }
            QrSharing.bitmap("vless://11111111-2222-3333-4444-555555555555@example.com:443?type=tcp&security=none#QR-Camera-Test").let { image ->
                // API34 imagefile camera crops an offset viewport; keep the fixture in that viewport.
                val canvasImage = android.graphics.Bitmap.createBitmap(1920, 1920, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(canvasImage)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.drawBitmap(image, null, android.graphics.Rect(64, 704, 576, 1216), null)
                java.io.File(fixture, "camera-qr.png").outputStream().use { canvasImage.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                canvasImage.recycle()
                image.recycle()
            }
        } finally { bitmap.recycle() }
    }
    @Test fun clipboardContainsFullSensitiveLink() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        // A foreground activity is required by Android's clipboard access rules.
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                val previous = clipboard.primaryClip
                try {
                    val text = "https://example.com/sub/private-test-token"
                    QrSharing.copy(context, text)
                    assertEquals(text, clipboard.primaryClip!!.getItemAt(0).text.toString())
                    val flags = clipboard.primaryClip!!.description.extras!!
                    assertTrue("Clipboard flags: $flags", flags.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE))
                } finally { if (previous != null) clipboard.setPrimaryClip(previous) else if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip() }
            }
        }
    }
}

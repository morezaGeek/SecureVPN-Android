package com.vpnapp.ui.components

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vpnapp.sharing.QrSharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LinkSharePayload(val name: String, val link: String)

@Composable
fun LinkShareDialog(payload: LinkSharePayload, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember(payload.link) { mutableStateOf<Bitmap?>(null) }
    var qrError by remember(payload.link) { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    LaunchedEffect(payload.link) {
        try { bitmap = withContext(Dispatchers.Default) { QrSharing.bitmap(payload.link) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { qrError = true }
    }
    fun share(intent: Intent) {
        try { context.startActivity(Intent.createChooser(intent, "Share VPN link")) }
        catch (_: Exception) { Toast.makeText(context, "No app available for sharing", Toast.LENGTH_SHORT).show() }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(payload.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                bitmap?.let { image ->
                    Image(image.asImageBitmap(), "QR code for ${payload.name}",
                        modifier = Modifier.size(160.dp).align(Alignment.CenterHorizontally).background(Color.White))
                } ?: if (qrError) Text("This link is too long for one QR code. Copy or share the full link below.")
                    else CircularProgressIndicator(Modifier.size(24.dp))
                SelectionContainer { Text(payload.link, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        QrSharing.copy(context, payload.link)
                        if (android.os.Build.VERSION.SDK_INT < 33)
                            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.weight(1f)) { Text("Copy link") }
                    OutlinedButton(onClick = { share(QrSharing.textIntent(payload.link)) },
                        modifier = Modifier.weight(1f)) { Text("Share link") }
                }
                Button(onClick = {
                    val image = bitmap ?: return@Button
                    sharing = true
                    scope.launch {
                        try { share(withContext(Dispatchers.IO) { QrSharing.imageIntent(context, image) }) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { Toast.makeText(context, "Could not share QR image", Toast.LENGTH_SHORT).show() }
                        finally { sharing = false }
                    }
                }, enabled = bitmap != null && !sharing, modifier = Modifier.fillMaxWidth()) {
                    Text(if (sharing) "Preparing…" else "Share QR image")
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

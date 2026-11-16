package com.vpnapp.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vpnapp.BuildConfig
import com.vpnapp.ui.theme.CurrentBackground
import com.vpnapp.ui.theme.CurrentAccent
import com.vpnapp.ui.theme.CurrentCard
import com.vpnapp.ui.theme.CurrentTextPrimary
import com.vpnapp.ui.theme.CurrentTextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(true) }
    var release by remember { mutableStateOf<GitHubRelease?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var download by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var received by remember { mutableLongStateOf(0L) }
    var checkRequest by remember { mutableIntStateOf(0) }

    LaunchedEffect(checkRequest) {
        checking = true
        status = null
        try {
            release = DailyUpdateCheck.refresh(context)
            if (release == null) status = "You’re up to date."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            status = if (error is java.io.IOException) error.message ?: "Could not reach GitHub." else "Could not read the GitHub release."
        } finally {
            checking = false
        }
    }

    Scaffold(containerColor = CurrentBackground, topBar = {
        TopAppBar(title = { Text("App updates") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
        })
    }) { insets ->
        Column(Modifier.padding(insets).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Installed version: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            if (checking) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = CurrentAccent, strokeWidth = 2.dp)
                    Text("Checking GitHub…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            val update = release
            if (!checking && update != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null)
                        Text("SecureVPN ${update.version}", style = MaterialTheme.typography.headlineSmall)
                        if (download != null) {
                            DownloadProgress(received = received, total = update.bytes, onCancel = { download?.cancel() })
                        } else if (file?.exists() == true) {
                            Button(onClick = {
                                try {
                                    if (!context.packageManager.canRequestPackageInstalls()) {
                                        status = "Allow installs from SecureVPN in Android settings, then return and tap Install update."
                                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                                    } else {
                                        status = null
                                        context.startActivity(GitHubUpdate.installer(context, checkNotNull(file)))
                                    }
                                } catch (_: Exception) { status = "Android could not open the installer." }
                            }) { Text("Install update") }
                        } else {
                            Button(onClick = {
                                received = 0
                                status = null
                                download = scope.launch {
                                    try {
                                        file = GitHubUpdate.download(context, update) { received = it }
                                    } catch (cancelled: CancellationException) {
                                        status = "Download canceled."
                                        throw cancelled
                                    } catch (error: Exception) {
                                        status = if (error is java.io.IOException) error.message ?: "Download failed." else "The update could not be verified."
                                    } finally {
                                        download = null
                                    }
                                }
                            }) { Text("Download update · ${formatMb(update.bytes)}") }
                        }
                        Text("What’s new", style = MaterialTheme.typography.titleMedium)
                        Text(update.notes.ifBlank { "No release notes provided." }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else if (!checking) {
                OutlinedButton(onClick = { checkRequest++ }) { Text("Check again") }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text("SecureVPN checks GitHub about once a day. Updates are downloaded only when you choose to download them.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DownloadProgress(received: Long, total: Long, onCancel: () -> Unit) {
    val fraction = (received.toFloat() / total).coerceIn(0f, 1f)
    val progress by animateFloatAsState(fraction, tween(durationMillis = 300), label = "downloadProgress")
    val verifying = received >= total
    Surface(
        color = CurrentCard,
        contentColor = CurrentTextPrimary,
        border = BorderStroke(1.dp, CurrentAccent.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(8.dp), color = CurrentAccent.copy(alpha = 0.13f)) {
                    Icon(
                        if (verifying) Icons.Default.VerifiedUser else Icons.Default.Downloading,
                        contentDescription = null,
                        tint = CurrentAccent,
                        modifier = Modifier.padding(8.dp).size(20.dp)
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(if (verifying) "Verifying APK" else "Downloading update", style = MaterialTheme.typography.titleSmall)
                    Text("${formatMb(received)} of ${formatMb(total)}", color = CurrentTextSecondary, style = MaterialTheme.typography.labelSmall)
                }
                Text("${(fraction * 100).toInt()}%", color = CurrentAccent, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            Box(
                Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                    .background(CurrentAccent.copy(alpha = 0.12f))
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }
            ) {
                Box(
                    Modifier.fillMaxWidth(progress).fillMaxHeight().clip(RoundedCornerShape(4.dp))
                        .background(Brush.horizontalGradient(listOf(CurrentAccent.copy(alpha = 0.5f), CurrentAccent)))
                )
            }
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Cancel download") }
        }
    }
}

private fun formatMb(bytes: Long): String =
    String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)

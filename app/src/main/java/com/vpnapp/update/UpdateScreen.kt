package com.vpnapp.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vpnapp.BuildConfig
import com.vpnapp.ui.theme.CurrentBackground
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
            release = GitHubUpdate.latest(BuildConfig.VERSION_NAME)
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
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Checking GitHub…")
            }
            val update = release
            if (!checking && update != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null)
                        Text("SecureVPN ${update.version}", style = MaterialTheme.typography.headlineSmall)
                        Text("What’s new", style = MaterialTheme.typography.titleMedium)
                        Text(update.notes.ifBlank { "No release notes provided." }, style = MaterialTheme.typography.bodyMedium)
                        val target = (received.toFloat() / update.bytes).coerceIn(0f, 1f)
                        val progress by animateFloatAsState(target, label = "updateDownload")
                        if (download != null) {
                            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().height(8.dp))
                            Text("${(target * 100).toInt()}% · ${formatMb(received)} of ${formatMb(update.bytes)}")
                            OutlinedButton(onClick = { download?.cancel() }) { Text("Cancel download") }
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

private fun formatMb(bytes: Long): String =
    String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)

package com.vpnapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpnapp.data.ConnectionLog
import com.vpnapp.data.LogLevel
import com.vpnapp.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    logs: List<ConnectionLog>,
    onClearLogs: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showClearDialog by remember { mutableStateOf(false) }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CurrentBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top bar
        TopAppBar(
            title = {
                Text(
                    text = "Connection Logs",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = CurrentTextPrimary
                )
            },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = CurrentTextPrimary
                    )
                }
            },
            actions = {
                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                val context = androidx.compose.ui.platform.LocalContext.current
                
                if (logs.isNotEmpty()) {
                    // Copy Button
                    IconButton(onClick = {
                        val text = logs.joinToString("\n") { 
                            val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(it.timestamp))
                            "[${time}] ${it.level.name}: ${it.message}"
                        }
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(text))
                        android.widget.Toast.makeText(context, "Logs copied", android.widget.Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Logs",
                            tint = CurrentTextPrimary
                        )
                    }
                    
                    // Clear Button
                    // Clear Button
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear Logs",
                            tint = CurrentTextSecondary
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            )
        )
        
        if (logs.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Article,
                        contentDescription = null,
                        tint = CurrentTextMuted,
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        text = "No logs yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = CurrentTextSecondary
                    )
                    Text(
                        text = "Connection logs will appear here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CurrentTextMuted
                    )
                }
            }
        } else {
            // Logs list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(logs, key = { it.id }) { log ->
                    LogEntry(log = log)
                }
            }
        }
    }
    
    // Clear confirmation dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Logs?", color = CurrentTextPrimary) },
            text = { Text("This will delete all connection logs.", color = TextSecondary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearLogs()
                        showClearDialog = false
                    }
                ) {
                    Text("Clear", color = StatusError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = CurrentCard,
            shape = RoundedCornerShape(28.dp)
        )
    }
}

@Composable
private fun LogEntry(
    log: ConnectionLog,
    modifier: Modifier = Modifier
) {
    val isDark = isAppInDarkTheme()
    val (levelColor, levelIcon) = when (log.level) {
        LogLevel.INFO -> (if (isDark) AccentCyan else LightAccentCyan) to Icons.Default.Info
        LogLevel.WARNING -> StatusConnecting to Icons.Default.Warning
        LogLevel.ERROR -> StatusError to Icons.Default.Error
        LogLevel.DEBUG -> (if (isDark) CurrentTextMuted else LightTextMuted) to Icons.Default.BugReport
        LogLevel.SUCCESS -> StatusConnected to Icons.Default.CheckCircle
    }
    
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CurrentCard)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Timestamp
        Text(
            text = timeFormat.format(Date(log.timestamp)),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            ),
            color = CurrentTextMuted,
            modifier = Modifier.width(80.dp)
        )
        
        // Level indicator
        Icon(
            imageVector = levelIcon,
            contentDescription = log.level.name,
            tint = levelColor,
            modifier = Modifier.size(14.dp)
        )
        
        // Message
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = log.message,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = CurrentTextPrimary  // Use black/dark text for readability
            )
            
            if (log.profileName.isNotEmpty()) {
                Text(
                    text = log.profileName,
                    style = MaterialTheme.typography.labelSmall,
                    color = CurrentTextMuted
                )
            }
        }
    }
}

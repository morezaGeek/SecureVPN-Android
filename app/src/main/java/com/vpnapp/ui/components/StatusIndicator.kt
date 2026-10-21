package com.vpnapp.ui.components

import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import com.vpnapp.ui.components.appClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vpnapp.data.ConnectionState
import com.vpnapp.data.ConnectionStats
import com.vpnapp.ui.theme.*

@Composable
fun ConnectionStatusIndicator(
    connectionState: ConnectionState,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val (statusText, targetColor) = when (connectionState) {
        ConnectionState.CONNECTED -> "Connected" to ConnectedGreen
        ConnectionState.CONNECTING -> "Connecting..." to ConnectingAmber
        ConnectionState.DISCONNECTING -> "Disconnecting..." to ConnectingAmber
        ConnectionState.ERROR -> "Connection Error" to DisconnectedRed
        else -> "Not Connected" to CurrentTextSecondary
    }
    val statusColor by animateColorAsState(targetColor, tween(180), label = "statusColor")
    
    Row(
        modifier = modifier.clip(RoundedCornerShape(50))
            .background(statusColor.copy(alpha = 0.10f)).padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 7.dp else 10.dp)
                .clip(CircleShape)
                .background(
                    statusColor.copy(
                        alpha = 1f
                    )
                )
        )
        
        Text(
            text = statusText,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium,
            color = statusColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun CompactTrafficRow(stats: ConnectionStats, modifier: Modifier = Modifier) {
    var showDetails by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CurrentCard)
        .appClickable(onClickLabel = "Traffic details") { showDetails = true }
        .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CompactTrafficValue(Icons.Default.ArrowDownward, "Download", formatSpeed(stats.downloadSpeed),
            ConnectedGreen, Modifier.weight(1f))
        CompactTrafficValue(Icons.Default.ArrowUpward, "Upload", formatSpeed(stats.uploadSpeed),
            CurrentAccent, Modifier.weight(1f))
        CompactTrafficValue(Icons.Default.DataUsage, "Total data",
            formatDataSize(stats.totalDownloaded + stats.totalUploaded), CurrentTextSecondary, Modifier.weight(1f))
    }
    if (showDetails) AlertDialog(
        onDismissRequest = { showDetails = false },
        title = { Text("Traffic for this connection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TrafficDetail("Downloaded", stats.totalDownloaded)
                TrafficDetail("Uploaded", stats.totalUploaded)
                TrafficDetail("Total", stats.totalDownloaded + stats.totalUploaded)
            }
        },
        confirmButton = { TextButton(onClick = { showDetails = false }) { Text("Close") } }
    )
}

@Composable
private fun TrafficDetail(label: String, bytes: Long) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(formatDataSize(bytes), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CompactTrafficValue(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String,
                                value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(14.dp))
        Text(value, style = MaterialTheme.typography.labelMedium, color = CurrentTextPrimary,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
fun SpeedIndicator(
    stats: ConnectionStats,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .widthIn(min = 200.dp)  // Fixed minimum width to prevent resizing
            .clip(RoundedCornerShape(20.dp))
            .background(CurrentCard)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Download
        SpeedItem(
            icon = Icons.Default.ArrowDownward,
            label = "Download",
            speed = stats.downloadSpeed,
            color = ConnectedGreen
        )
        
        // Divider
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(32.dp)
                .background(CurrentTextMuted.copy(alpha = 0.3f))
        )
        
        // Upload
        SpeedItem(
            icon = Icons.Default.ArrowUpward,
            label = "Upload",
            speed = stats.uploadSpeed,
            color = CurrentAccent
        )
    }
}

@Composable
private fun SpeedItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    speed: Long,
    color: androidx.compose.ui.graphics.Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = formatSpeed(speed),
                style = MaterialTheme.typography.titleMedium,
                color = CurrentTextPrimary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = CurrentTextMuted
        )
    }
}

@Composable
fun ConnectionTimer(
    connectedTime: Long,
    modifier: Modifier = Modifier
) {
    val hours = (connectedTime / 3600000).toInt()
    val minutes = ((connectedTime % 3600000) / 60000).toInt()
    val seconds = ((connectedTime % 60000) / 1000).toInt()
    
    val timeString = if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
    
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Timer,
            contentDescription = "Connection time",
            tint = CurrentTextMuted,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = timeString,
            style = MaterialTheme.typography.bodyMedium,
            color = CurrentTextSecondary
        )
    }
}

@Composable
fun DataUsageIndicator(
    totalDownloaded: Long,
    totalUploaded: Long,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CurrentSurfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        DataItem(
            icon = Icons.Default.Download,
            value = formatDataSize(totalDownloaded),
            color = ConnectedGreen
        )
        DataItem(
            icon = Icons.Default.Upload,
            value = formatDataSize(totalUploaded),
            color = CurrentAccent
        )
    }
}

@Composable
private fun DataItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    color: androidx.compose.ui.graphics.Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = CurrentTextSecondary
        )
    }
}

private fun formatSpeed(bytesPerSecond: Long): String {
    return when {
        bytesPerSecond >= 1_000_000 -> String.format("%.1f MB/s", bytesPerSecond / 1_000_000.0)
        bytesPerSecond >= 1_000 -> String.format("%.0f KB/s", bytesPerSecond / 1_000.0)
        else -> "$bytesPerSecond B/s"
    }
}

private fun formatDataSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> String.format("%.2f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format("%.0f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}


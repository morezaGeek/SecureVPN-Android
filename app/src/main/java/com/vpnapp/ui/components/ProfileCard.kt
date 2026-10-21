package com.vpnapp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.background
import com.vpnapp.ui.components.appClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vpnapp.data.ConnectionState
import com.vpnapp.data.VpnProfile
import com.vpnapp.data.VpnProtocol
import com.vpnapp.data.XHttpModePolicy
import com.vpnapp.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileCard(
    profile: VpnProfile,
    isSelected: Boolean,
    connectionState: ConnectionState,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    isTestingLatency: Boolean = false,
    isLatencyQueued: Boolean = false,
    onShare: (() -> Unit)? = null
) {
    val isConnecting = connectionState == ConnectionState.CONNECTING
    val isConnected = connectionState == ConnectionState.CONNECTED && isSelected
    val interactionSource = remember { MutableInteractionSource() }
    val cardColor by animateColorAsState(if (isSelected) CurrentCard else CurrentCard.copy(alpha = 0.85f),
        tween(180), label = "profileFill")
    
    val borderColor by animateColorAsState(
        targetValue = when {
            isConnected -> StatusConnected
            isConnecting && isSelected -> StatusConnecting
            isSelected -> CurrentAccent
            else -> CurrentSurfaceVariant
        },
        label = "borderColor"
    )
    
    val (protocolIcon, protocolColor) = when (profile.protocol) {
        VpnProtocol.OPENCONNECT -> Icons.Default.VpnLock to CurrentAccent
        VpnProtocol.SSTP -> Icons.Default.Security to SstpColor
        VpnProtocol.VLESS -> Icons.Default.Share to MaterialTheme.colorScheme.tertiary
        VpnProtocol.VMESS -> Icons.Default.Settings to MaterialTheme.colorScheme.tertiary // Hub -> Settings
        VpnProtocol.TROJAN -> Icons.Default.Lock to MaterialTheme.colorScheme.error // Shield -> Lock
        VpnProtocol.SHADOWSOCKS -> Icons.Default.Info to MaterialTheme.colorScheme.primary // Visibility -> Info
        VpnProtocol.HYSTERIA2 -> Icons.Default.Share to MaterialTheme.colorScheme.secondary // Or Bolt/FlashOn
    }

    
    Card(
        modifier = modifier.fillMaxWidth(),
        interactionSource = interactionSource,
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                colors = if (isSelected) listOf(borderColor, borderColor.copy(alpha = 0.5f))
                else listOf(CurrentSurfaceVariant, CurrentSurfaceVariant)
            )
        ),
        onClick = onSelect
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Protocol icon with background
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(protocolColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = protocolIcon,
                        contentDescription = profile.protocol.displayName,
                        tint = protocolColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = CurrentTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Text(
                        text = "${profile.serverAddress}:${profile.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = CurrentTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val protocolText = if (profile.protocol in listOf(VpnProtocol.VLESS, VpnProtocol.VMESS, VpnProtocol.TROJAN)) {
                            val net = profile.v2rayNetwork.uppercase()
                            val sec = if (profile.v2rayTls == "tls") "TLS" else if (profile.v2rayTls == "reality") "REALITY" else ""
                            "${profile.protocol.displayName} $net $sec".trim()
                        } else {
                            profile.protocol.displayName
                        }
                        
                        Text(
                            text = protocolText,
                            style = MaterialTheme.typography.labelSmall,
                            color = protocolColor
                        )
                        
                        if (isTestingLatency) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp, color = CurrentAccent)
                            Text("Testing…", style = MaterialTheme.typography.labelSmall, color = CurrentAccent)
                        } else if (isLatencyQueued) {
                            Text("Queued", style = MaterialTheme.typography.labelSmall, color = CurrentTextMuted)
                        }
                        // Latency display with color coding
                        else profile.latency?.let { latency ->
                            Text(
                                text = " • ",
                                color = CurrentTextMuted
                            )
                            val latencyColor = when {
                                latency < 0 -> StatusError // Failed
                                latency < 100 -> StatusConnected // Green - excellent
                                latency < 200 -> StatusConnecting // Yellow - good
                                else -> StatusError // Red - poor
                            }
                            val latencyText = if (latency < 0) "✗" else "${latency}ms"
                            Text(
                                text = latencyText,
                                style = MaterialTheme.typography.labelSmall,
                                color = latencyColor,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    if (XHttpModePolicy.isXHttp(profile)) {
                        Text(
                            text = if (profile.v2rayXhttpModeOverride == "smart") "Smart · ${profile.v2raySmartXhttpMode ?: "checking on connect"}"
                            else if (profile.v2rayXhttpModeOverride != null) "${profile.v2rayType} · Custom"
                                else if (profile.v2rayOriginalXhttpMode != null) "stream-up · Forced"
                                else "XHTTP · ${profile.v2rayType.takeUnless { it.isBlank() || it == "none" } ?: "auto"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CurrentAccent,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            
            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                onShare?.let { share ->
                    IconButton(onClick = share, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Share, contentDescription = "Share ${profile.name}",
                            tint = CurrentAccent, modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit",
                        tint = CurrentTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = StatusError.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        
        // Connection status indicator
        if (isSelected && (isConnected || isConnecting)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                if (isConnected) StatusConnected else StatusConnecting,
                                Color.Transparent
                            )
                        )
                    )
            )
        }
    }
}

@Composable
fun ProfileCardCompact(
    profile: VpnProfile,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (protocolIcon, protocolColor) = when (profile.protocol) {
        VpnProtocol.OPENCONNECT -> Icons.Default.VpnLock to CurrentAccent
        VpnProtocol.SSTP -> Icons.Default.Security to SstpColor
        VpnProtocol.VLESS -> Icons.Default.Share to MaterialTheme.colorScheme.tertiary
        VpnProtocol.VMESS -> Icons.Default.Settings to MaterialTheme.colorScheme.tertiary
        VpnProtocol.TROJAN -> Icons.Default.Lock to MaterialTheme.colorScheme.error
        VpnProtocol.SHADOWSOCKS -> Icons.Default.Info to MaterialTheme.colorScheme.primary
        VpnProtocol.HYSTERIA2 -> Icons.Default.Share to MaterialTheme.colorScheme.secondary
    }

    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (isSelected) protocolColor.copy(alpha = 0.1f) else Color.Transparent)
            .appClickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = protocolIcon,
            contentDescription = null,
            tint = protocolColor,
            modifier = Modifier.size(20.dp)
        )
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.bodyMedium,
                color = CurrentTextPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(
                text = profile.serverAddress,
                style = MaterialTheme.typography.bodySmall,
                color = CurrentTextMuted
            )
        }
        
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = protocolColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}


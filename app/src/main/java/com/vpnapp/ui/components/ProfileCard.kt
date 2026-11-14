package com.vpnapp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vpnapp.data.ConnectionState
import com.vpnapp.data.VpnProfile
import com.vpnapp.data.VpnProtocol
import com.vpnapp.ui.theme.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileCard(
    profile: VpnProfile,
    isSelected: Boolean,
    connectionState: ConnectionState,
    onSelect: () -> Unit,
    onShowActions: () -> Unit,
    modifier: Modifier = Modifier,
    isActionTarget: Boolean = false,
    isTestingLatency: Boolean = false,
    isLatencyQueued: Boolean = false
) {
    val dark = isAppInDarkTheme()
    val ink = if (dark) Color.White else Color.Black
    val borderColor by animateColorAsState(
        targetValue = when {
            isActionTarget -> ink
            isSelected && connectionState == ConnectionState.CONNECTED -> StatusConnected
            isSelected && connectionState == ConnectionState.CONNECTING -> StatusConnecting
            isSelected -> CurrentAccent
            else -> if (dark) Color(0xFF414141) else Color(0xFFBDBDBD)
        },
        label = "profileBorder"
    )
    val mono = FontFamily.Monospace
    val protocolText = when (profile.protocol) {
        VpnProtocol.VLESS, VpnProtocol.VMESS, VpnProtocol.TROJAN -> {
            val network = profile.v2rayNetwork.uppercase()
            val security = when (profile.v2rayTls) {
                "tls" -> "TLS"
                "reality" -> "REALITY"
                else -> ""
            }
            "${profile.protocol.displayName} $network $security".trim()
        }
        else -> profile.protocol.displayName
    }
    val latencyLabel = when {
        isTestingLatency -> "Testing…"
        isLatencyQueued -> "Queued"
        else -> profile.latency?.let { if (it < 0) "Failed" else "${it}ms" }
    }
    val latencyColor = when {
        isTestingLatency || isLatencyQueued -> ink
        else -> profile.latency?.let {
            when {
                it < 0 -> if (dark) Color(0xFFFF6B6B) else Color(0xFFB3261E)
                it < 100 -> if (dark) Color(0xFF64B5FF) else Color(0xFF0056B3)
                it <= 160 -> if (dark) Color(0xFF62DC86) else Color(0xFF17672D)
                it <= 270 -> if (dark) Color(0xFFFFD54F) else Color(0xFF735700)
                else -> if (dark) Color(0xFFFF6B6B) else Color(0xFFB3261E)
            }
        } ?: ink
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RectangleShape,
        color = if (dark) Color.Black else Color.White,
        border = BorderStroke(if (isActionTarget || isSelected) 2.dp else 1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .semantics { selected = isSelected }
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClickLabel = "Select profile",
                    onLongClickLabel = "Show profile actions",
                    onClick = onSelect,
                    onLongClick = onShowActions
                )
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = mono,
                    fontWeight = FontWeight.SemiBold,
                    color = ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${profile.serverAddress}:${profile.port}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = mono,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = protocolText,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = mono,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (latencyLabel != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isTestingLatency) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = ink)
                    }
                    Text(
                        latencyLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = mono,
                        fontWeight = FontWeight.SemiBold,
                        color = latencyColor,
                        maxLines = 1
                    )
                }
            }
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


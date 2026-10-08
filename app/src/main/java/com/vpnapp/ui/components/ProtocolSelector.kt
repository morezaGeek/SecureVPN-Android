package com.vpnapp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vpnapp.data.VpnProtocol
import com.vpnapp.ui.theme.*

@Composable
fun ProtocolSelector(
    selectedProtocol: VpnProtocol,
    onProtocolSelected: (VpnProtocol) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "VPN Protocol",
            style = MaterialTheme.typography.titleMedium,
            color = CurrentTextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VpnProtocol.entries.forEach { protocol ->
                ProtocolChip(
                    protocol = protocol,
                    isSelected = protocol == selectedProtocol,
                    onClick = { onProtocolSelected(protocol) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun ProtocolChip(
    protocol: VpnProtocol,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (icon, color) = when (protocol) {
        VpnProtocol.OPENCONNECT -> Icons.Default.VpnLock to CurrentAccent
        VpnProtocol.SSTP -> Icons.Default.Security to SstpColor
        VpnProtocol.VLESS -> Icons.Default.Share to MaterialTheme.colorScheme.tertiary
        VpnProtocol.VMESS -> Icons.Default.Settings to MaterialTheme.colorScheme.tertiary
        VpnProtocol.TROJAN -> Icons.Default.Lock to MaterialTheme.colorScheme.error
        VpnProtocol.SHADOWSOCKS -> Icons.Default.Info to MaterialTheme.colorScheme.primary
        VpnProtocol.HYSTERIA2 -> Icons.Default.Share to MaterialTheme.colorScheme.secondary
    }

    
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) color.copy(alpha = 0.2f) else DarkSurface,
        label = "bgColor"
    )
    
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) color else DarkSurfaceVariant,
        label = "borderColor"
    )
    
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = protocol.displayName,
            tint = if (isSelected) color else CurrentTextMuted,
            modifier = Modifier.size(24.dp)
        )
        
        Text(
            text = protocol.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) color else TextSecondary,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
fun ProtocolSelectorCard(
    selectedProtocol: VpnProtocol,
    onProtocolSelected: (VpnProtocol) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = DarkCard.copy(alpha = 0.7f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Select Protocol",
                style = MaterialTheme.typography.titleMedium,
                color = CurrentTextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            
            VpnProtocol.entries.forEach { protocol ->
                ProtocolOption(
                    protocol = protocol,
                    isSelected = protocol == selectedProtocol,
                    onClick = { onProtocolSelected(protocol) }
                )
            }
        }
    }
}

@Composable
private fun ProtocolOption(
    protocol: VpnProtocol,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val (icon, color) = when (protocol) {
        VpnProtocol.OPENCONNECT -> Icons.Default.VpnLock to CurrentAccent
        VpnProtocol.SSTP -> Icons.Default.Security to SstpColor
        VpnProtocol.VLESS -> Icons.Default.Share to MaterialTheme.colorScheme.tertiary
        VpnProtocol.VMESS -> Icons.Default.Settings to MaterialTheme.colorScheme.tertiary
        VpnProtocol.TROJAN -> Icons.Default.Lock to MaterialTheme.colorScheme.error
        VpnProtocol.SHADOWSOCKS -> Icons.Default.Info to MaterialTheme.colorScheme.primary
        VpnProtocol.HYSTERIA2 -> Icons.Default.Share to MaterialTheme.colorScheme.secondary
    }

    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (isSelected) color.copy(alpha = 0.15f) else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) color else CurrentTextMuted,
                modifier = Modifier.size(24.dp)
            )
            
            Column {
                Text(
                    text = protocol.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) CurrentTextPrimary else TextSecondary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = protocol.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = CurrentTextMuted
                )
            }
        }
        
        RadioButton(
            selected = isSelected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = color,
                unselectedColor = CurrentTextMuted
            )
        )
    }
}

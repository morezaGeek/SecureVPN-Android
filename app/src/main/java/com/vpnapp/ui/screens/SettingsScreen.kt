package com.vpnapp.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import com.vpnapp.ui.components.appClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vpnapp.data.VpnProtocol
import com.vpnapp.ui.components.ProtocolSelectorCard
import com.vpnapp.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    bufferSize: Int,
    tcpNoDelay: Boolean,
    mtuSize: Int,
    onMtuSizeChange: (Int) -> Unit,
    bypassIran: Boolean,
    bypassPrivateIps: Boolean,
    forceXhttpStreamUp: Boolean,
    onForceXhttpStreamUpChange: (Boolean) -> Unit,
    excludedApps: List<String>,
    onBufferSizeChange: (Int) -> Unit,
    onTcpNoDelayChange: (Boolean) -> Unit,
    onBypassIranChange: (Boolean) -> Unit,
    onBypassPrivateIpsChange: (Boolean) -> Unit,
    onNavigateToSplitTunnel: () -> Unit,
    onManagePrivacyOptions: () -> Unit,
    onNavigateBack: () -> Unit,
    tunnelDnsUrl: String = com.vpnapp.vpn.singbox.TunnelDns.DEFAULT_URL,
    useFakeIp: Boolean = true,
    onTunnelDnsUrlChange: (String) -> Unit = {},
    onUseFakeIpChange: (Boolean) -> Unit = {},
    onNetworkTools: () -> Unit = {},
    onUsageHistory: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var notifications by remember { mutableStateOf(true) }
    var dnsDraft by remember(tunnelDnsUrl) { mutableStateOf(tunnelDnsUrl) }
    var dnsError by remember { mutableStateOf<String?>(null) }
    
    CompactSettingsTypography {
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
                    text = "Settings",
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
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent
            )
        )
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsSection(title = "Network & usage") {
                SettingsItem(title="Share VPN over LAN & Kill Switch", subtitle="Proxy port, local sharing and Android protection",
                    icon=Icons.Default.Router, onClick=onNetworkTools)
                SettingsItem(title="History of Usage", subtitle="Daily, weekly and custom date ranges",
                    icon=Icons.Default.History, onClick=onUsageHistory)
            }
            // Connection settings
            SettingsSection(title = "Connection") {
                // Always-on VPN & Kill Switch - Opens Android VPN Settings
                SettingsItem(
                    title = "Always-on VPN & Kill Switch",
                    subtitle = "Enable in system settings for best protection",
                    icon = Icons.Default.Shield,
                    onClick = onNetworkTools
                )
                
                Divider(color = CurrentSurfaceVariant)
                
                // Split Tunneling
                SettingsItem(
                    title = "Split Tunneling",
                    subtitle = if (excludedApps.isEmpty()) 
                        "Select apps to bypass VPN" 
                    else 
                        "${excludedApps.size} apps bypassing VPN",
                    icon = Icons.Default.CallSplit,
                    onClick = onNavigateToSplitTunnel
                )
                
                Divider(color = CurrentSurfaceVariant)
                
                // Bypass Iran Domains
                SettingsToggle(
                    title = "Bypass Iran Routes",
                    subtitle = if (bypassIran) "Iranian public IPs and sites go direct · LAN is separate" else "Iranian sites go through VPN · LAN is separate",
                    icon = Icons.Default.Public,
                    checked = bypassIran,
                    onCheckedChange = onBypassIranChange
                )
                
                Divider(color = CurrentSurfaceVariant)
                
                SettingsToggle(
                    title = "Bypass LAN",
                    subtitle = if (bypassPrivateIps) "Local network devices (e.g. 192.168.x.x) are reachable" else "Local network traffic is routed through VPN",
                    icon = Icons.Default.Router,
                    checked = bypassPrivateIps,
                    onCheckedChange = onBypassPrivateIpsChange
                )
                Text(
                    text = "V2Ray route changes apply to the active connection; existing connections may briefly restart. Other protocols apply changes on reconnect.",
                    color = CurrentTextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp)
                )
            }
            
            SettingsSection(title = "Tunnel DNS · V2Ray / Hysteria2") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DNS inside the VPN is separate from Android Private DNS. Saving applies it to the active tunnel and turns FakeIP off.",
                        style = MaterialTheme.typography.bodySmall, color = CurrentTextSecondary)
                    OutlinedTextField(value = dnsDraft, onValueChange = { dnsDraft = it; dnsError = null },
                        label = { Text("DNS-over-HTTPS URL") }, singleLine = true, isError = dnsError != null,
                        modifier = Modifier.fillMaxWidth())
                    dnsError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = {
                        runCatching { onTunnelDnsUrlChange(com.vpnapp.vpn.singbox.TunnelDns.validateUrl(dnsDraft)) }
                            .onSuccess { android.widget.Toast.makeText(context, "DNS saved · real DNS answers enabled", android.widget.Toast.LENGTH_SHORT).show() }
                            .onFailure { dnsError = "Enter a valid HTTPS DNS-over-HTTPS URL" }
                    }) { Text("Save DNS") }
                    Text(if (useFakeIp) "FakeIP is enabled: A/AAAA queries use synthetic addresses; the selected DoH resolver is not used for these answers. Save DNS to use it."
                        else "Using real answers from the saved DoH resolver. Anti-sanction resolvers may redirect some services; if YouTube fails, try https://1.1.1.1/dns-query.",
                        style = MaterialTheme.typography.bodySmall, color = CurrentTextSecondary)
                }
                SettingsToggle(title = "FakeIP", subtitle = if (useFakeIp) "ON · Resolve destinations at the VPN server"
                    else "OFF · Use real answers from tunnel DNS", icon = Icons.Default.Dns,
                    checked = useFakeIp, onCheckedChange = onUseFakeIpChange)
            }

            SettingsSection(title = "XHTTP") {
                SettingsToggle(
                    title = "Force XHTTP stream-up",
                    subtitle = if (forceXhttpStreamUp) "ON · Prefer stream-up; explicit packet-up and custom modes are preserved"
                        else "OFF · Use original modes unless a profile has a custom mode",
                    icon = Icons.Default.Bolt,
                    checked = forceXhttpStreamUp,
                    onCheckedChange = onForceXhttpStreamUpChange
                )
                Text(
                    text = "Includes clipboard imports and subscription updates. Original modes are preserved. Reconnect to apply a change to an active VPN.",
                    color = CurrentTextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp)
                )
            }

            // Appearance settings (Theme)
            SettingsSection(title = "Appearance") {
                val themeOptions = listOf(
                    "System" to ThemeMode.SYSTEM,
                    "Light" to ThemeMode.LIGHT,
                    "Dark" to ThemeMode.DARK
                )
                
                var expanded by remember { mutableStateOf(false) }
                val currentTheme = ThemeState.themeMode
                val currentLabel = when (currentTheme) {
                    ThemeMode.SYSTEM -> "System"
                    ThemeMode.LIGHT -> "Light"
                    ThemeMode.DARK -> "Dark"
                }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .appClickable {expanded = !expanded }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(CurrentSurfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Palette,
                                contentDescription = null,
                                tint = CurrentAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Theme",
                                style = MaterialTheme.typography.bodyLarge,
                                color = CurrentTextPrimary,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = currentLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = CurrentAccent
                            )
                        }
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = CurrentTextMuted
                    )
                }
                
                if (expanded) {
                    Divider(color = CurrentSurfaceVariant)
                    Column(modifier = Modifier.padding(start = 52.dp, end = 12.dp, bottom = 8.dp)) {
                        val context = LocalContext.current
                        themeOptions.forEach { (label, mode) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .appClickable {
                                        ThemeState.themeMode = mode
                                        ThemeState.saveTheme(context)  // Save theme permanently
                                        expanded = false
                                    }
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = label, 
                                    color = if (currentTheme == mode) CurrentAccent else CurrentTextSecondary,
                                    fontWeight = if (currentTheme == mode) FontWeight.Medium else FontWeight.Normal
                                )
                                if (currentTheme == mode) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = CurrentAccent, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
            
            SettingsSection(title = "Tunnel MTU") {
                var mtuInput by remember(mtuSize) { mutableStateOf(mtuSize.toString()) }
                val mtuValue = mtuInput.toIntOrNull()
                val valid = mtuValue != null && mtuValue in 1200..1500
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = mtuInput, onValueChange = { value ->
                        if (value.length <= 4 && value.all(Char::isDigit)) mtuInput = value
                    }, label = { Text("MTU (bytes)") }, singleLine = true,
                        isError = !valid, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f))
                    Button(onClick = { mtuValue?.let(onMtuSizeChange) },
                        enabled = valid && mtuValue != mtuSize) { Text("Apply") }
                }
                Text("$mtuSize bytes saved · Range 1200–1500. Applies on next connection, including V2Ray.",
                    style = MaterialTheme.typography.bodySmall, color = CurrentTextSecondary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
            }

            // Performance Settings - Buffer Size Selector
            SettingsSection(title = "Performance") {
                Text(
                    text = "TCP Buffer and No Delay affect OpenConnect (Native and Java) and SSTP. Android may limit socket buffers. V2Ray manages its own buffers. Applies on next connection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CurrentTextSecondary,
                    modifier = Modifier.padding(16.dp)
                )
                val bufferOptions = listOf(
                    "64 KB (Standard)" to 65535,
                    "1 MB (High Speed)" to 1 * 1024 * 1024,
                    "2 MB (Very High)" to 2 * 1024 * 1024,
                    "4 MB (Extreme)" to 4 * 1024 * 1024,
                    "8 MB (Maximum)" to 8 * 1024 * 1024
                )
                
                var expanded by remember { mutableStateOf(false) }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .appClickable {expanded = !expanded }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(CurrentSurfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = CurrentAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "TCP Buffer Size",
                                style = MaterialTheme.typography.bodyLarge,
                                color = CurrentTextPrimary,
                                fontWeight = FontWeight.Medium
                            )
                            val currentLabel = bufferOptions.find { it.second == bufferSize }?.first ?: "Custom"
                            Text(
                                text = currentLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = CurrentAccent
                            )
                        }
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = CurrentTextMuted
                    )
                }
                
                if (expanded) {
                    Divider(color = CurrentSurfaceVariant)
                    Column(modifier = Modifier.padding(start = 52.dp, end = 12.dp, bottom = 8.dp)) {
                        bufferOptions.forEach { (label, size) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .appClickable {
                                        onBufferSizeChange(size)
                                        expanded = false
                                    }
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = label, 
                                    color = if (bufferSize == size) CurrentAccent else CurrentTextSecondary,
                                    fontWeight = if (bufferSize == size) FontWeight.Medium else FontWeight.Normal
                                )
                                if (bufferSize == size) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = CurrentAccent, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                
                Divider(color = CurrentSurfaceVariant)
                
                // TCP_NODELAY Toggle
                SettingsToggle(
                    title = "TCP No Delay",
                    subtitle = if (tcpNoDelay) "Enabled (lower latency)" else "Disabled (better throughput)",
                    icon = Icons.Default.FlashOn,
                    checked = tcpNoDelay,
                    onCheckedChange = onTcpNoDelayChange
                )
            }
            
            // Notifications
            SettingsSection(title = "Notifications") {
                SettingsToggle(
                    title = "Connection Alerts",
                    subtitle = "Show notifications for connection events",
                    icon = Icons.Default.Notifications,
                    checked = notifications,
                    onCheckedChange = { notifications = it }
                )
            }

            SettingsSection(title = "Privacy") {
                SettingsItem(
                    title = "Ad privacy choices",
                    subtitle = "Review or change Google ad consent",
                    icon = Icons.Default.PrivacyTip,
                    onClick = onManagePrivacyOptions
                )
            }
            
            // About section
            SettingsSection(title = "About") {
                SettingsItem(
                    title = "App Version",
                    subtitle = com.vpnapp.BuildConfig.VERSION_NAME,
                    icon = Icons.Default.Info
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = CurrentTextSecondary,
            fontWeight = FontWeight.Medium
        )
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = CurrentCard
            )
        ) {
            Column(
                modifier = Modifier.padding(4.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val iconBackground by animateColorAsState(
        if (checked) CurrentAccent.copy(alpha = 0.16f) else CurrentSurfaceVariant,
        tween(180), label = "settingIcon"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(iconBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = CurrentAccent,
                    modifier = Modifier.size(20.dp)
                )
            }
            
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = CurrentTextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = CurrentTextMuted
                )
            }
        }
        
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CurrentAccent,
                checkedTrackColor = CurrentAccent.copy(alpha = 0.3f),
                uncheckedThumbColor = CurrentTextMuted,
                uncheckedTrackColor = CurrentSurfaceVariant
            )
        )
    }
}

@Composable
private fun SettingsItem(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) 
                    Modifier.appClickable(onClick = onClick)
                else 
                    Modifier
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(CurrentSurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = CurrentTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = CurrentTextPrimary,
                    fontWeight = FontWeight.Medium
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = CurrentTextMuted
                    )
                }
            }
        }
        
        if (onClick != null) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = CurrentTextMuted
            )
        }
    }
}

@Composable
private fun clickable(onClick: () -> Unit): Modifier {
    return Modifier.appClickable(onClick = onClick)
}



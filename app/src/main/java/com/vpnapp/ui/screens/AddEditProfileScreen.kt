package com.vpnapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpnapp.data.AuthType
import com.vpnapp.data.VpnProfile
import com.vpnapp.data.VpnProtocol
import com.vpnapp.ui.theme.*
import com.vpnapp.data.V2RayLinkParser
import androidx.compose.ui.platform.LocalClipboardManager // Import Clipboard
import androidx.compose.ui.text.AnnotatedString
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditProfileScreen(
    existingProfile: VpnProfile? = null,
    initialProtocol: VpnProtocol = VpnProtocol.OPENCONNECT,
    onSave: (VpnProfile) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isEditing = existingProfile != null
    
    // Form state
    var name by remember { mutableStateOf(existingProfile?.name ?: "") }
    var serverAddress by remember { mutableStateOf(existingProfile?.serverAddress ?: "") }
    var port by remember { mutableStateOf(existingProfile?.port?.toString() ?: "443") }
    var protocol by remember { mutableStateOf(existingProfile?.protocol ?: initialProtocol) }
    var username by remember { mutableStateOf(existingProfile?.username ?: "") }
    var password by remember { mutableStateOf(existingProfile?.password ?: "") }
    var authType by remember { mutableStateOf(existingProfile?.authType ?: AuthType.PASSWORD) }
    
    // Advanced options
    var showAdvanced by remember { mutableStateOf(false) }
    
    // Protocol-specific (OpenConnect only)
    var disableDtls by remember { mutableStateOf(existingProfile?.disableDtls ?: false) }
    var mtu by remember { mutableStateOf(existingProfile?.mtu?.toString() ?: "1400") }
    var useNative by remember { mutableStateOf(existingProfile?.useNative ?: true) }

    // V2Ray State
    var v2rayUuid by remember { mutableStateOf(existingProfile?.v2rayUuid ?: "") }
    var v2rayAlterId by remember { mutableStateOf(existingProfile?.v2rayAlterId?.toString() ?: "0") }
    var v2raySecurity by remember { mutableStateOf(existingProfile?.v2raySecurity ?: "auto") }
    var vlessEncryption by remember { mutableStateOf(existingProfile?.vlessEncryption ?: "none") }
    var v2rayNetwork by remember { mutableStateOf(existingProfile?.v2rayNetwork ?: "tcp") }
    var v2rayType by remember { mutableStateOf(existingProfile?.v2rayType ?: "none") }
    var xhttpModeOverride by remember { mutableStateOf(existingProfile?.v2rayXhttpModeOverride) }
    var v2rayHost by remember { mutableStateOf(existingProfile?.v2rayHost ?: "") }
    var v2rayPath by remember { mutableStateOf(existingProfile?.v2rayPath ?: "") }
    var v2rayTls by remember { mutableStateOf(existingProfile?.v2rayTls ?: "none") }
    var v2raySni by remember { mutableStateOf(existingProfile?.v2raySni ?: "") }
    var v2rayAlpn by remember { mutableStateOf(existingProfile?.v2rayAlpn ?: "") }
    var v2rayFp by remember { mutableStateOf(existingProfile?.v2rayFp ?: "") }
    var v2rayFlow by remember { mutableStateOf(existingProfile?.v2rayFlow ?: "") }
    var v2rayPbk by remember { mutableStateOf(existingProfile?.v2rayPbk ?: "") }
    var v2rayShortId by remember { mutableStateOf(existingProfile?.v2rayShortId ?: "") }
    var v2rayXhttpExtra by remember { mutableStateOf(existingProfile?.v2rayXhttpExtra ?: "") }
    var v2rayImportText by remember { mutableStateOf("") }
    
    val clipboardManager = LocalClipboardManager.current
    
    // Country for flag display
    var countryCode by remember { mutableStateOf(existingProfile?.countryCode ?: "") }
    var showCountryDropdown by remember { mutableStateOf(false) }
    
    // Common countries
    val countries = listOf(
        "" to "Auto-detect",
        "US" to "🇺🇸 United States",
        "DE" to "🇩🇪 Germany",
        "GB" to "🇬🇧 United Kingdom",
        "FR" to "🇫🇷 France",
        "NL" to "🇳🇱 Netherlands",
        "JP" to "🇯🇵 Japan",
        "SG" to "🇸🇬 Singapore",
        "AU" to "🇦🇺 Australia",
        "CA" to "🇨🇦 Canada",
        "CH" to "🇨🇭 Switzerland",
        "SE" to "🇸🇪 Sweden",
        "NO" to "🇳🇴 Norway",
        "FI" to "🇫🇮 Finland",
        "RU" to "🇷🇺 Russia",
        "HK" to "🇭🇰 Hong Kong",
        "KR" to "🇰🇷 South Korea",
        "IN" to "🇮🇳 India",
        "BR" to "🇧🇷 Brazil"
    )
    
    var passwordVisible by remember { mutableStateOf(false) }
    
    // Validation
    val isValid = name.isNotBlank() && serverAddress.isNotBlank() && port.toIntOrNull() != null
    
    CompactSettingsTypography {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundGradient)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        val uiContext = LocalContext.current
        // Top bar
        TopAppBar(
            title = {
                Text(
                    text = if (isEditing) "Edit Profile" else "Add Profile",
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
                TextButton(
                    onClick = {
                        val profile = (existingProfile ?: VpnProfile(
                            name = name,
                            serverAddress = serverAddress
                        )).copy(
                            name = name,
                            serverAddress = serverAddress,
                            port = port.toIntOrNull() ?: 443,
                            protocol = protocol,
                            username = username,
                            password = password,
                            authType = authType,
                            disableDtls = disableDtls,
                            mtu = mtu.toIntOrNull() ?: 1400,
                            useNative = useNative,

                            countryCode = countryCode,
                            // V2Ray fields
                            v2rayUuid = v2rayUuid,
                            v2rayAlterId = v2rayAlterId.toIntOrNull() ?: 0,
                            v2raySecurity = v2raySecurity,
                            vlessEncryption = vlessEncryption.trim().ifEmpty { "none" },
                            v2rayNetwork = v2rayNetwork,
                            v2rayType = v2rayType,
                            v2rayXhttpModeOverride = xhttpModeOverride,
                            v2rayHost = v2rayHost,
                            v2rayPath = v2rayPath,
                            v2rayTls = v2rayTls,
                            v2raySni = v2raySni,
                            v2rayAlpn = v2rayAlpn,
                            v2rayFp = v2rayFp,
                            v2rayFlow = v2rayFlow,
                            v2rayPbk = v2rayPbk,
                            v2rayShortId = v2rayShortId,
                            v2rayXhttpExtra = v2rayXhttpExtra
                        )
                        onSave(profile)
                    },
                    enabled = isValid
                ) {
                    Text(
                        "Save",
                        color = if (isValid) CurrentAccent else CurrentTextMuted,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            )
        )
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Quick Import from Clipboard - Always visible at the top
            if (!isEditing) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = CurrentAccent.copy(alpha = 0.15f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = null,
                                tint = CurrentAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Quick Import",
                                style = MaterialTheme.typography.titleSmall,
                                color = CurrentAccent,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        
                        Text(
                            text = "Paste a V2ray link (VLESS, VMess, Trojan, Shadowsocks) from your clipboard",
                            style = MaterialTheme.typography.bodySmall,
                            color = CurrentTextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        
                        Button(
                            onClick = {
                                clipboardManager.getText()?.text?.let { text ->
                                    val parsed = V2RayLinkParser.parseLink(text)
                                    if (parsed != null) {
                                        name = parsed.name
                                        serverAddress = parsed.serverAddress
                                        port = parsed.port.toString()
                                        protocol = parsed.protocol
                                        v2rayUuid = parsed.v2rayUuid
                                        v2raySecurity = parsed.v2raySecurity
                                        vlessEncryption = parsed.vlessEncryption
                                        v2rayNetwork = parsed.v2rayNetwork
                                        v2rayType = parsed.v2rayType
                                        v2rayHost = parsed.v2rayHost
                                        v2rayPath = parsed.v2rayPath
                                        v2rayTls = parsed.v2rayTls
                                        v2raySni = parsed.v2raySni
                                        v2rayAlpn = parsed.v2rayAlpn
                                        v2rayFp = parsed.v2rayFp
                                        v2rayFlow = parsed.v2rayFlow
                                        v2rayPbk = parsed.v2rayPbk
                                        v2rayShortId = parsed.v2rayShortId
                                        v2rayXhttpExtra = parsed.v2rayXhttpExtra
                                        username = parsed.username
                                        password = parsed.password
                                        Toast.makeText(uiContext, "✅ Config imported successfully!", Toast.LENGTH_SHORT).show()
                                    } else if (text.isNotEmpty()) {
                                        Toast.makeText(uiContext, "❌ Invalid V2ray link format", Toast.LENGTH_SHORT).show()
                                    }
                                } ?: run {
                                    Toast.makeText(uiContext, "📋 Clipboard is empty", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = CurrentAccent),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Paste V2ray Config from Clipboard", fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            
            // Basic Info Section
            SectionTitle("Basic Information")
            
            // Profile Name (Required)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Profile Name *") },
                placeholder = { Text("My VPN Server") },
                leadingIcon = {
                    Icon(Icons.Default.Badge, contentDescription = null, tint = CurrentAccent)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = textFieldColors(),
                singleLine = true
            )
            
            // Server Address (Required)
            OutlinedTextField(
                value = serverAddress,
                onValueChange = { serverAddress = it },
                label = { Text("Server Address *") },
                placeholder = { Text("vpn.example.com or 192.168.1.1") },
                leadingIcon = {
                    Icon(Icons.Default.Dns, contentDescription = null, tint = CurrentAccent)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = textFieldColors(),
                singleLine = true
            )
            
            // Port
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() } },
                label = { Text("Port") },
                placeholder = { Text("443") },
                leadingIcon = {
                    Icon(Icons.Default.Numbers, contentDescription = null, tint = CurrentAccent)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = textFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            
            // Protocol Selection
            SectionTitle("Protocol")
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CurrentCard)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    VpnProtocol.entries.forEach { proto ->
                        val (icon, color) = when (proto) {
                            VpnProtocol.OPENCONNECT -> Icons.Default.VpnLock to OpenConnectColor
                            VpnProtocol.SSTP -> Icons.Default.Security to SstpColor
                            VpnProtocol.VLESS -> Icons.Default.Bolt to Color(0xFFFF9800) // Orange
                            VpnProtocol.VMESS -> Icons.Default.Bolt to Color(0xFFFF9800)
                            VpnProtocol.TROJAN -> Icons.Default.Shield to Color(0xFF4CAF50) // Green
                            VpnProtocol.SHADOWSOCKS -> Icons.Default.Send to Color(0xFF2196F3) // Blue
                            VpnProtocol.HYSTERIA2 -> Icons.Default.Bolt to Color(0xFF9C27B0) // Purple
                        }
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = protocol == proto,
                                onClick = { protocol = proto },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = color,
                                    unselectedColor = CurrentTextMuted
                                )
                            )
                            Spacer(Modifier.width(12.dp))
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (protocol == proto) color else CurrentTextMuted,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = proto.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (protocol == proto) CurrentTextPrimary else TextSecondary
                                )
                                Text(
                                    text = proto.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CurrentTextMuted
                                )
                            }
                        }
                    }
                }
            }
            


            // V2Ray Import Section
            if (protocol == VpnProtocol.VLESS || protocol == VpnProtocol.VMESS || protocol == VpnProtocol.TROJAN || protocol == VpnProtocol.SHADOWSOCKS) {
                SectionTitle("Import Configuration")
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkCard)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = v2rayImportText,
                            onValueChange = { v2rayImportText = it },
                            label = { Text("Configuration / Link") },
                            placeholder = { Text("vless://... or vmess://...") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = textFieldColors(),
                            singleLine = false,
                            maxLines = 3
                        )
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                             Button(
                                onClick = { 
                                    // Parse Import Text
                                    if (v2rayImportText.isNotEmpty()) {
                                        val parsed = V2RayLinkParser.parseLink(v2rayImportText)
                                        if (parsed != null) {
                                            name = parsed.name
                                            serverAddress = parsed.serverAddress
                                            port = parsed.port.toString()
                                            protocol = parsed.protocol
                                            v2rayUuid = parsed.v2rayUuid
                                            v2raySecurity = parsed.v2raySecurity
                                            vlessEncryption = parsed.vlessEncryption
                                            v2rayNetwork = parsed.v2rayNetwork
                                            v2rayType = parsed.v2rayType
                                            v2rayHost = parsed.v2rayHost
                                            v2rayPath = parsed.v2rayPath
                                            v2rayTls = parsed.v2rayTls
                                            v2raySni = parsed.v2raySni
                                            v2rayAlpn = parsed.v2rayAlpn
                                            v2rayFp = parsed.v2rayFp
                                            v2rayFlow = parsed.v2rayFlow
                                            v2rayPbk = parsed.v2rayPbk
                                            v2rayShortId = parsed.v2rayShortId
                                        v2rayXhttpExtra = parsed.v2rayXhttpExtra
                                            username = parsed.username
                                            password = parsed.password
                                            v2rayImportText = "" // Clear after import
                                            Toast.makeText(uiContext, "Imported successfully", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(uiContext, "Invalid V2Ray link", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = CurrentAccent)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Import")
                            }
                            
                            OutlinedButton(
                                onClick = { 
                                    clipboardManager.getText()?.text?.let { text ->
                                        val parsed = V2RayLinkParser.parseLink(text)
                                        if (parsed != null) {
                                            name = parsed.name
                                            serverAddress = parsed.serverAddress
                                            port = parsed.port.toString()
                                            protocol = parsed.protocol
                                            v2rayUuid = parsed.v2rayUuid
                                            v2raySecurity = parsed.v2raySecurity
                                            vlessEncryption = parsed.vlessEncryption
                                            v2rayNetwork = parsed.v2rayNetwork
                                            v2rayType = parsed.v2rayType
                                            v2rayHost = parsed.v2rayHost
                                            v2rayPath = parsed.v2rayPath
                                            v2rayTls = parsed.v2rayTls
                                            v2raySni = parsed.v2raySni
                                            v2rayAlpn = parsed.v2rayAlpn
                                            v2rayFp = parsed.v2rayFp
                                            v2rayFlow = parsed.v2rayFlow
                                            v2rayPbk = parsed.v2rayPbk
                                            v2rayShortId = parsed.v2rayShortId
                                        v2rayXhttpExtra = parsed.v2rayXhttpExtra
                                            username = parsed.username
                                            password = parsed.password
                                            Toast.makeText(uiContext, "Imported from clipboard", Toast.LENGTH_SHORT).show()
                                        } else {
                                            v2rayImportText = text
                                            Toast.makeText(uiContext, "Text pasted. Click 'Import' to try parsing.", Toast.LENGTH_SHORT).show()
                                        }
                                    } ?: run {
                                        Toast.makeText(uiContext, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = CurrentAccent)
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Clipboard")
                            }
                        }
                    }
                }
            }
            
            // V2Ray Configuration Section (Advanced/Stream Settings)
            if (protocol == VpnProtocol.VLESS || protocol == VpnProtocol.VMESS || protocol == VpnProtocol.TROJAN || protocol == VpnProtocol.SHADOWSOCKS) {
                SectionTitle("Stream Settings")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = CurrentCard)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // UUID / Password
                        OutlinedTextField(
                            value = v2rayUuid,
                            onValueChange = { v2rayUuid = it },
                            label = { Text(if (protocol == VpnProtocol.TROJAN || protocol == VpnProtocol.SHADOWSOCKS) "Password" else "UUID (User ID)") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = textFieldColors(),
                            singleLine = true
                        )

                        if (protocol == VpnProtocol.VLESS) {
                            OutlinedTextField(
                                value = vlessEncryption,
                                onValueChange = { vlessEncryption = it },
                                label = { Text("VLESS encryption") },
                                supportingText = { Text("Preserved from the link. Keep the server's value; none disables this layer.") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = textFieldColors(),
                                singleLine = true
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Security Type
                            var securityExpanded by remember { mutableStateOf(false) }
                            val securityOptions = listOf("none", "tls", "reality", "auto")
                            ExposedDropdownMenuBox(
                                expanded = securityExpanded,
                                onExpandedChange = { securityExpanded = it },
                                modifier = Modifier.weight(1f)
                            ) {
                                OutlinedTextField(
                                    value = v2raySecurity,
                                    onValueChange = { v2raySecurity = it },
                                    label = { Text("Security") },
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    colors = textFieldColors(),
                                    singleLine = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = securityExpanded) }
                                )
                                ExposedDropdownMenu(
                                    expanded = securityExpanded,
                                    onDismissRequest = { securityExpanded = false },
                                    modifier = Modifier.background(CurrentSurface)
                                ) {
                                    securityOptions.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option, color = CurrentTextPrimary) },
                                            onClick = {
                                                v2raySecurity = option
                                                securityExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            
                            // Network Type
                            var networkExpanded by remember { mutableStateOf(false) }
                            val networkOptions = listOf("tcp", "ws", "grpc", "h2", "http", "xhttp")
                            ExposedDropdownMenuBox(
                                expanded = networkExpanded,
                                onExpandedChange = { networkExpanded = it },
                                modifier = Modifier.weight(1f)
                            ) {
                                OutlinedTextField(
                                    value = v2rayNetwork,
                                    onValueChange = { v2rayNetwork = it },
                                    label = { Text("Network") },
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    colors = textFieldColors(),
                                    singleLine = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = networkExpanded) }
                                )
                                ExposedDropdownMenu(
                                    expanded = networkExpanded,
                                    onDismissRequest = { networkExpanded = false },
                                    modifier = Modifier.background(CurrentSurface)
                                ) {
                                    networkOptions.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option, color = CurrentTextPrimary) },
                                            onClick = {
                                                v2rayNetwork = option
                                                networkExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        if (v2rayNetwork.equals("xhttp", true) || v2rayNetwork.equals("splithttp", true)) {
                            var modeExpanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(expanded = modeExpanded, onExpandedChange = { modeExpanded = it }) {
                                OutlinedTextField(
                                    value = if (xhttpModeOverride == "smart") "Smart" else xhttpModeOverride ?: "Follow app setting", onValueChange = {}, readOnly = true,
                                    label = { Text("XHTTP mode") }, modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    colors = textFieldColors(), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeExpanded) }
                                )
                                ExposedDropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                                    listOf("Smart", "Follow app setting", "auto", "packet-up", "stream-up", "stream-one").forEach { mode ->
                                        DropdownMenuItem(text = { Text(mode) }, onClick = {
                                            xhttpModeOverride = when (mode) { "Smart" -> "smart"; "Follow app setting" -> null; else -> mode }; modeExpanded = false
                                        })
                                    }
                                }
                            }
                            Text(if (xhttpModeOverride == "smart") "Smart checks real traffic before connecting, tries up to four modes, and remembers a working choice. Encryption stays unchanged."
                                else "Choose a manual mode or Smart. Changes apply on reconnect.",
                                color = CurrentTextMuted, fontSize = 12.sp)
                        }
                        // SNI & Host
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = v2raySni,
                                onValueChange = { v2raySni = it },
                                label = { Text("SNI") },
                                modifier = Modifier.weight(1f),
                                colors = textFieldColors(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = v2rayHost,
                                onValueChange = { v2rayHost = it },
                                label = { Text("Header Host") },
                                modifier = Modifier.weight(1f),
                                colors = textFieldColors(),
                                singleLine = true
                            )
                        }

                        // Path & ALPN
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = v2rayPath,
                                onValueChange = { v2rayPath = it },
                                label = { Text("Path / Service Name") },
                                modifier = Modifier.weight(1.5f),
                                colors = textFieldColors(),
                                singleLine = true
                            )
                            var alpnExpanded by remember { mutableStateOf(false) }
                            val alpnOptions = listOf("", "h2,http/1.1", "h2", "http/1.1", "h3")
                            ExposedDropdownMenuBox(
                                expanded = alpnExpanded,
                                onExpandedChange = { alpnExpanded = it },
                                modifier = Modifier.weight(1f)
                            ) {
                                OutlinedTextField(
                                    value = v2rayAlpn,
                                    onValueChange = { v2rayAlpn = it },
                                    label = { Text("ALPN") },
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    colors = textFieldColors(),
                                    singleLine = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = alpnExpanded) }
                                )
                                ExposedDropdownMenu(
                                    expanded = alpnExpanded,
                                    onDismissRequest = { alpnExpanded = false },
                                    modifier = Modifier.background(CurrentSurface)
                                ) {
                                    alpnOptions.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(if (option.isEmpty()) "None" else option, color = CurrentTextPrimary) },
                                            onClick = {
                                                v2rayAlpn = option
                                                alpnExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Fingerprint & Flow
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = v2rayFp,
                                onValueChange = { v2rayFp = it },
                                label = { Text("uTLS Fingerprint") },
                                modifier = Modifier.weight(1f),
                                colors = textFieldColors(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = v2rayFlow,
                                onValueChange = { v2rayFlow = it },
                                label = { Text("Flow") },
                                modifier = Modifier.weight(1f),
                                colors = textFieldColors(),
                                singleLine = true
                            )
                        }

                        // Reality Fields
                        if (v2raySecurity == "reality") {
                            Divider(color = CurrentSurfaceVariant, thickness = 1.dp)
                            Text("Reality Settings", style = MaterialTheme.typography.labelMedium, color = CurrentAccent)
                            
                            OutlinedTextField(
                                value = v2rayPbk,
                                onValueChange = { v2rayPbk = it },
                                label = { Text("Public Key (pbk)") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = textFieldColors(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = v2rayShortId,
                                onValueChange = { v2rayShortId = it },
                                label = { Text("Short ID (sid)") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = textFieldColors(),
                                singleLine = true
                            )
                        }
                    }
                }
            }
            
            // Authentication Section
            SectionTitle("Authentication (Optional)")
            
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                placeholder = { Text("user@example.com") },
                leadingIcon = {
                    Icon(Icons.Default.Person, contentDescription = null, tint = CurrentAccent)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = textFieldColors(),
                singleLine = true
            )
            
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                placeholder = { Text("••••••••") },
                leadingIcon = {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = CurrentAccent)
                },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) "Hide" else "Show",
                            tint = CurrentTextMuted
                        )
                    }
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = textFieldColors(),
                singleLine = true
            )
            
            // Country Selection (Optional)
            SectionTitle("Country Appearance")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CurrentCard),
                onClick = { showCountryDropdown = true }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val currentCountry = countries.find { it.first == countryCode }
                    Icon(Icons.Default.Flag, contentDescription = null, tint = CurrentAccent)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = currentCountry?.second ?: "Select Country",
                        color = CurrentTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = CurrentTextSecondary)
                }
                
                DropdownMenu(
                    expanded = showCountryDropdown,
                    onDismissRequest = { showCountryDropdown = false },
                    modifier = Modifier.background(CurrentSurface)
                ) {
                    countries.forEach { (code, label) ->
                        DropdownMenuItem(
                            text = { Text(label, color = CurrentTextPrimary) },
                            onClick = {
                                countryCode = code
                                showCountryDropdown = false
                            }
                        )
                    }
                }
            }
            
            // Advanced Options Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (protocol == VpnProtocol.OPENCONNECT) {
                    Text(
                        "Advanced Options",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextSecondary
                    )
                    Switch(
                        checked = showAdvanced,
                        onCheckedChange = { showAdvanced = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CurrentAccent,
                            checkedTrackColor = CurrentAccent.copy(alpha = 0.3f)
                        )
                    )
                }
            }
            
            // Advanced Options Content (OpenConnect only)
            if (showAdvanced && protocol == VpnProtocol.OPENCONNECT) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = CurrentCard)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("OpenConnect Options", color = CurrentTextSecondary, style = MaterialTheme.typography.labelMedium)
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Disable DTLS (UDP)", color = CurrentTextPrimary)
                            Switch(
                                checked = disableDtls,
                                onCheckedChange = { disableDtls = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = OpenConnectColor,
                                    checkedTrackColor = OpenConnectColor.copy(alpha = 0.3f),
                                    uncheckedTrackColor = CurrentSurfaceVariant
                                )
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Use Native Engine", color = CurrentTextPrimary)
                                if (!useNative) {
                                    Text(
                                        "⚠️ Does not support DTLS",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StatusError.copy(alpha = 0.8f)
                                    )
                                }
                            }
                            Switch(
                                checked = useNative,
                                onCheckedChange = { useNative = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = OpenConnectColor,
                                    checkedTrackColor = OpenConnectColor.copy(alpha = 0.3f),
                                    uncheckedTrackColor = CurrentSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }

            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = CurrentAccent,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = CurrentCard,
    unfocusedContainerColor = CurrentCard,
    focusedBorderColor = CurrentAccent,
    unfocusedBorderColor = CurrentSurfaceVariant,
    focusedTextColor = CurrentTextPrimary,
    unfocusedTextColor = CurrentTextPrimary,
    focusedLabelColor = CurrentAccent,
    unfocusedLabelColor = CurrentTextSecondary,
    cursorColor = CurrentAccent
)

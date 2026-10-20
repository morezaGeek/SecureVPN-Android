package com.vpnapp.ui.screens

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.vpnapp.ui.theme.*
import com.vpnapp.vpn.LanShareSettings
import com.vpnapp.vpn.LanProxyProtocol
import com.vpnapp.vpn.LanProxyStatus
import com.vpnapp.vpn.VpnConnectionService
import com.vpnapp.util.LocalNetworkInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkToolsScreen(alwaysOn: Boolean, lockdown: Boolean, connected: Boolean, onBack: () -> Unit) {
    val context=LocalContext.current
    var share by remember { mutableStateOf(LanShareSettings.read(context)) }
    var port by remember { mutableStateOf(share.port.toString()) }
    var protocol by remember { mutableStateOf(share.protocol) }
    var socksAuthentication by remember { mutableStateOf(share.socksAuthentication) }
    var username by remember { mutableStateOf(share.username) }
    var password by remember { mutableStateOf(share.password) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    var showProtection by remember { mutableStateOf(false) }
    var runtimeStatus by remember { mutableStateOf<LanProxyStatus?>(null) }
    var addresses by remember { mutableStateOf(LocalNetworkInfo.addresses(context,true)) }
    LaunchedEffect(Unit) { while(true) { addresses=LocalNetworkInfo.addresses(context,true);kotlinx.coroutines.delay(5000) } }
    fun requestStatus() {
        runCatching { context.startService(Intent(context,VpnConnectionService::class.java)
            .setAction(VpnConnectionService.ACTION_LAN_SHARE_STATUS_REQUEST)) }
            .onFailure { error="Could not read proxy status. Reopen Network tools while the app is visible." }
    }
    DisposableEffect(context) {
        val receiver=object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let(LanProxyStatus::read)?.let { runtimeStatus=it }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(context,receiver,
            IntentFilter(VpnConnectionService.ACTION_LAN_SHARE_STATUS),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        requestStatus()
        onDispose { context.unregisterReceiver(receiver) }
    }
    fun save(enabled: Boolean, applyEdits: Boolean = true) {
        runCatching {
            val settings = if (applyEdits) LanShareSettings(enabled,
                port.toIntOrNull() ?: throw IllegalArgumentException("Choose a port from 1024 to 65535"), username, password,
                protocol, socksAuthentication) else share.copy(enabled = enabled)
            LanShareSettings.save(context, settings)
            share=LanShareSettings.read(context)
            // The VPN is a separate process; always send the snapshot even when UI connection state is stale.
            context.startService(Intent(context,VpnConnectionService::class.java).setAction(VpnConnectionService.ACTION_LAN_SHARE_CONFIG).also { share.writeTo(it) })
        }.onSuccess { error=null }.onFailure { error=it.message ?: "Enter a valid port" }
    }
    Scaffold(containerColor=CurrentBackground,topBar={ TopAppBar(title={ Text("Network tools") },
        navigationIcon={ IconButton(onClick=onBack) { Icon(Icons.Default.ArrowBack,"Back") } },
        colors=TopAppBarDefaults.topAppBarColors(containerColor=CurrentBackground)) }) { padding ->
        Column(Modifier.padding(padding).padding(horizontal=16.dp).imePadding().verticalScroll(rememberScrollState()),
            verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Card(colors=CardDefaults.cardColors(containerColor=CurrentCard)) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("Share VPN over LAN",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                        Switch(checked=share.enabled,onCheckedChange={ save(it, applyEdits = it) },
                            modifier=Modifier.testTag("lan_enabled"))
                    }
                    Text("Choose the same proxy type on the other device. TCP traffic uses this phone's active VPN. Use the same Wi-Fi/LAN or this phone's hotspot if your device allows it. Sharing stops when VPN disconnects.",style=MaterialTheme.typography.bodySmall)
                    Text("Proxy type",style=MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        LanProxyProtocol.entries.forEach { choice ->
                            FilterChip(selected=protocol==choice,onClick={ protocol=choice;error=null },
                                label={ Text(choice.label) },modifier=Modifier.testTag("lan_protocol_${choice.name}"))
                        }
                    }
                    Text("Only the selected type runs on this port. HTTP and SOCKS5 are alternatives, not two simultaneous listeners.",style=MaterialTheme.typography.bodySmall)
                    if(protocol==LanProxyProtocol.HTTP) {
                        Text("HTTP proxy · no username or password. For Android/Windows network settings, select Manual/HTTP and enter the address and port. HTTPS websites also work through this proxy.",style=MaterialTheme.typography.bodySmall)
                    } else {
                        Text("SOCKS5 proxy · choose SOCKS5 in an app that supports it. Use proxy/remote DNS when available.",style=MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("Require username and password",modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                            Switch(checked=socksAuthentication,onCheckedChange={ socksAuthentication=it;error=null },
                                modifier=Modifier.testTag("lan_socks_auth"))
                        }
                        if(socksAuthentication) {
                            OutlinedTextField(value=username,onValueChange={ username=it;error=null },
                                label={ Text("SOCKS5 username") },singleLine=true,
                                keyboardOptions=KeyboardOptions(autoCorrectEnabled=false),
                                modifier=Modifier.fillMaxWidth().testTag("lan_username"))
                            OutlinedTextField(value=password,onValueChange={ password=it;error=null },
                                label={ Text("SOCKS5 password") },singleLine=true,
                                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),
                                visualTransformation=if(showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon={ IconButton(onClick={ showPassword=!showPassword }) {
                                    Icon(if(showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        if(showPassword) "Hide password" else "Show password")
                                } },modifier=Modifier.fillMaxWidth().testTag("lan_password"))
                            Text("Choose your own credentials, then enter BOTH the username and password on the other device.",style=MaterialTheme.typography.bodySmall)
                        } else Text("SOCKS5 authentication is off. Leave username and password empty on the other device.",style=MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(value=port,onValueChange={ port=it.filter(Char::isDigit).take(5) },
                        label={ Text("Proxy port · 1024–65535") },singleLine=true,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
                        modifier=Modifier.fillMaxWidth().testTag("lan_port"))
                    Button(onClick={ save(share.enabled) },modifier=Modifier.testTag("lan_save")) { Text("Save LAN settings") }
                    error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                    Text("Saved proxy: ${share.protocol.label} · port ${share.port}",style=MaterialTheme.typography.labelLarge)
                    Text(if(addresses.isEmpty()) "Connect to Wi-Fi/Ethernet or enable a hotspot to get a LAN address"
                        else addresses.joinToString("\n") { "Server IP: $it" },style=MaterialTheme.typography.bodyMedium)
                    Text("Enter the IP only in Server/Address, without http:// or :port. Enter ${share.port} separately in Port.",style=MaterialTheme.typography.bodySmall)
                    Text(when(runtimeStatus?.state) {
                        "LISTENING" -> "Listening · ${runtimeStatus!!.protocol} · port ${runtimeStatus!!.port}"
                        "ERROR" -> "Proxy could not start"
                        "WAITING" -> "Waiting for the VPN network"
                        "OFF" -> "Sharing is off"
                        else -> if(share.enabled) "Checking proxy status…" else "Sharing is off"
                    },style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("lan_runtime_status"))
                    runtimeStatus?.takeIf { it.state=="LISTENING" }?.let { status ->
                        Text("Incoming connections: ${status.accepted} · Forwarded: ${status.forwarded}",style=MaterialTheme.typography.bodySmall,
                            modifier=Modifier.testTag("lan_runtime_counts"))
                        if(status.accepted==0) Text("No device has reached this proxy yet. Check the address, port and Wi-Fi client isolation on the other device.",style=MaterialTheme.typography.bodySmall)
                    }
                    runtimeStatus?.lastError?.let { Text(it,color=MaterialTheme.colorScheme.error,
                        style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("lan_runtime_error")) }
                    TextButton(onClick={ requestStatus() }) { Text("Refresh proxy status") }
                    Text("Tap Save to apply type, port and credential changes. Without authentication, anyone on your private LAN can use the proxy; use a trusted network. TCP only; this is a proxy, not a transparent hotspot router.",style=MaterialTheme.typography.bodySmall,color=CurrentTextSecondary)
                }
            }
            Card(colors=CardDefaults.cardColors(containerColor=CurrentCard)) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("Kill Switch",style=MaterialTheme.typography.titleMedium)
                    Text(if(lockdown) "ON · Android blocks connections outside VPN" else "Not enabled in Android settings")
                    Text(if(alwaysOn) "Always-on VPN is enabled" else "Always-on VPN is not enabled",style=MaterialTheme.typography.bodySmall)
                    Text("Android can block Internet traffic if the VPN drops, the app stops or the phone restarts. Enable both Always-on VPN and Block connections without VPN for SecureVPN. Save a profile by connecting it once first.",style=MaterialTheme.typography.bodySmall)
                    Text("While blocking is enabled, disconnecting the VPN blocks Internet access. Apps excluded from VPN may also lose access. Turn this off in Android VPN settings when you want to use the Internet directly.",style=MaterialTheme.typography.bodySmall,color=CurrentTextSecondary)
                    Button(onClick={ showProtection=true }) { Text("Configure Android Kill Switch") }
                }
            }
            Card(colors=CardDefaults.cardColors(containerColor=CurrentCard)) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("VPN notification",style=MaterialTheme.typography.titleMedium)
                    Text("Keep SecureVPN notifications allowed. Some phones also require allowing background activity in battery settings.",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={ context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)) }) { Text("Notification settings") }
                    TextButton(onClick={ context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Battery settings") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if(showProtection) AlertDialog(onDismissRequest={ showProtection=false },title={ Text("Enable system protection") },
        text={ Text("Select the gear next to SecureVPN, then enable Always-on VPN and Block connections without VPN. Android requires you to change these settings yourself.") },
        confirmButton={ TextButton(onClick={ showProtection=false;context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }) { Text("Open VPN settings") } },
        dismissButton={ TextButton(onClick={ showProtection=false }) { Text("Cancel") } })
}

package com.vpnapp.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import com.vpnapp.ui.components.appClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.style.TextOverflow
import com.vpnapp.BuildConfig
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.vpnapp.data.*
import com.vpnapp.ui.components.*
import com.vpnapp.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    connectionInfo: VpnConnectionInfo,
    profiles: List<VpnProfile>,
    selectedProfile: VpnProfile?,
    logs: List<ConnectionLog>,
    subscriptions: List<VpnSubscription>,
    onToggleConnection: () -> Unit,
    onSelectProfile: (VpnProfile) -> Unit,
    onEditProfile: (VpnProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onAddProfile: () -> Unit,
    onMeasureLatency: () -> Unit,
    onTestCurrentLatency: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onAddSubscription: (String, String?) -> Unit,
    onRefreshSubscription: (String) -> Unit,
    onRefreshAllSubscriptions: () -> Unit,
    onDeleteSubscription: (String) -> Unit,
    onUpdateSubscription: (VpnSubscription) -> Unit,
    localAddresses: String = "",
    selectedSubscriptionId: String? = null,
    onSelectSubscription: (String?) -> Unit = {},
    refreshingSubscriptions: Set<String> = emptySet(),
    onShareProfile: (VpnProfile) -> Unit = {},
    onShareSubscription: (VpnSubscription) -> Unit = {},
    onScanQr: () -> Unit = {},
    latencyTestState: LatencyTestState = LatencyTestState(),
    profileSort: ProfileSort = ProfileSort.DEFAULT,
    onProfileSort: (ProfileSort) -> Unit = {},
    onStopLatency: () -> Unit = {},
    onUsageHistory: () -> Unit = {},
    smartPreparing: Boolean = false,
    onCancelSmartConnection: () -> Unit = {},
    adBanner: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showDeleteDialog by remember { mutableStateOf<VpnProfile?>(null) }
    var showAddSubDialog by remember { mutableStateOf(false) }
    var subUrlInput by remember { mutableStateOf("") }
    var subNameInput by remember { mutableStateOf("") }
    var showDeleteSubDialog by remember { mutableStateOf<VpnSubscription?>(null) }
    var showEditSubDialog by remember { mutableStateOf<VpnSubscription?>(null) }
    val selectedSubscriptionFilter = selectedSubscriptionId
    var expandedLogs by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    val subscriptionListState = rememberLazyListState()
    LaunchedEffect(selectedSubscriptionId, subscriptions.map { it.id }) {
        val index = subscriptions.indexOfFirst { it.id == selectedSubscriptionId }
        subscriptionListState.scrollToItem(if (index < 0) 0 else index + 1)
    }
    
    // Extract connection state ONCE to prevent full object comparison triggering recomposition
    val connectionState = connectionInfo.state
    
    // Show logs when connecting or connected
    val showInlineLogs = connectionState == ConnectionState.CONNECTING ||
                         connectionState == ConnectionState.CONNECTED ||
                         connectionState == ConnectionState.DISCONNECTING
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CurrentBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            // Top bar
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("SecureVPN", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, color = CurrentTextPrimary)
                        Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = CurrentTextSecondary)
                    }
                },
                actions = {
                    IconButton(onClick = onUsageHistory) {
                        Icon(Icons.Default.History, contentDescription = "History of usage", tint = CurrentTextSecondary)
                    }
                    IconButton(onClick = onNavigateToLogs) {
                        Icon(
                            imageVector = Icons.Default.Article,
                            contentDescription = "Logs",
                            tint = CurrentTextSecondary
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = CurrentTextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )

            
            // Main content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val connected = connectionState == ConnectionState.CONNECTED
                val activeProfile = connectionInfo.profile.takeIf {
                    connectionState in listOf(ConnectionState.CONNECTED, ConnectionState.CONNECTING, ConnectionState.DISCONNECTING)
                }
                val displayProfile = activeProfile ?: selectedProfile
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ConnectionButton(connectionState = connectionState, onClick = onToggleConnection,
                        enabled = selectedProfile != null || connected, modifier = Modifier.size(108.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ConnectionStatusIndicator(connectionState = connectionState, compact = true)
                        Text(if (connected) "Connected to" else "Selected profile",
                            style = MaterialTheme.typography.labelSmall, color = CurrentTextSecondary)
                        Text(displayProfile?.name ?: "Select a profile", style = MaterialTheme.typography.titleSmall,
                            color = CurrentTextPrimary, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val activeSubId = profiles.find { it.id == displayProfile?.id }?.subscriptionId
                            ?: displayProfile?.subscriptionId
                        subscriptions.find { it.id == activeSubId }?.let {
                            Text(it.name, style = MaterialTheme.typography.labelSmall, color = CurrentAccent,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (connected) ConnectionTimer(connectedTime = connectionInfo.stats.connectedTime)
                    }
                }
                if (smartPreparing) {
                    Text("Checking XHTTP modes...", color = CurrentTextSecondary,
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCancelSmartConnection) { Text("Cancel Smart check") }
                }
                connectionInfo.errorMessage?.takeIf { connectionState == ConnectionState.ERROR }?.let {
                    Text(it, color = DisconnectedRed, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                Spacer(Modifier.height(6.dp))
                IpAddressDisplay(
                    publicIp = if (connected) connectionInfo.stats.publicIp else "",
                    originalIp = connectionInfo.stats.originalIp,
                    localAddresses = localAddresses,
                    countryCode = if (connected) connectionInfo.stats.countryCode else "",
                    mtu = if (connected) connectionInfo.stats.mtu else 0,
                    ping = if (connected) connectionInfo.stats.ping else 0,
                    connected = connected,
                    onPingClick = onTestCurrentLatency
                )
                if (connected) {
                    Spacer(Modifier.height(6.dp))
                    CompactTrafficRow(stats = connectionInfo.stats)
                }
                if (showInlineLogs && logs.isNotEmpty()) {
                    TextButton(onClick = { expandedLogs = !expandedLogs }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (expandedLogs) "Hide connection log" else "Connection log",
                            style = MaterialTheme.typography.labelMedium)
                        Icon(if (expandedLogs) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    AnimatedVisibility(expandedLogs, enter = fadeIn(tween(180)), exit = fadeOut(tween(120))) {
                        InlineLogViewer(logs = logs.take(3), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))

                // Subscriptions section header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Subscriptions",
                        style = MaterialTheme.typography.titleMedium,
                        color = CurrentTextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onRefreshAllSubscriptions, enabled = refreshingSubscriptions.isEmpty()) {
                            if (refreshingSubscriptions.isNotEmpty()) {
                                CircularProgressIndicator(Modifier.size(18.dp), color = CurrentAccent, strokeWidth = 2.dp)
                            } else Icon(Icons.Default.Refresh, contentDescription = "Refresh subscriptions", tint = CurrentAccent)
                        }
                        TextButton(onClick = { 
                            subUrlInput = ""
                            subNameInput = ""
                            showAddSubDialog = true 
                        }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = CurrentAccent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Add Sub",
                                color = CurrentAccent,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                androidx.compose.foundation.lazy.LazyRow(
                    state = subscriptionListState,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedSubscriptionFilter == null,
                            onClick = { onSelectSubscription(null) },
                            label = { Text("All") },
                            shape = RoundedCornerShape(16.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CurrentAccent,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                    items(subscriptions, key = { it.id }) { sub ->
                        FilterChip(
                            selected = selectedSubscriptionFilter == sub.id,
                            onClick = { onSelectSubscription(sub.id) },
                            label = { Text(sub.name) },
                            shape = RoundedCornerShape(16.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CurrentAccent,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            trailingIcon = {
                                IconButton(
                                    onClick = { 
                                        subUrlInput = sub.url
                                        subNameInput = sub.name
                                        showEditSubDialog = sub
                                    },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(14.dp))
                                }
                            }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                // Profiles section header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "VPN Profiles",
                        style = MaterialTheme.typography.titleMedium,
                        color = CurrentTextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Row {
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Default.Sort, contentDescription = "Sort profiles", tint = CurrentAccent)
                            }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                ProfileSort.entries.forEach { sort ->
                                    DropdownMenuItem(text = { Text(sort.label) },
                                        onClick = { onProfileSort(sort); showSortMenu = false },
                                        trailingIcon = { if (sort == profileSort) Icon(Icons.Default.Check, "Selected") })
                                }
                            }
                        }
                        // Ping Button
                        TextButton(
                            onClick = if (latencyTestState.isRunning) onStopLatency else onMeasureLatency,
                            modifier = Modifier.semantics { contentDescription = if (latencyTestState.isRunning) "Stop ping tests" else "Start ping tests" },
                            enabled = latencyTestState.isRunning || connectionState !in listOf(ConnectionState.CONNECTING, ConnectionState.DISCONNECTING)) {
                            if (latencyTestState.isRunning) CircularProgressIndicator(
                                modifier = Modifier.size(18.dp), color = ConnectedGreen, strokeWidth = 2.dp)
                            else Icon(
                                imageVector = Icons.Default.NetworkCheck,
                                contentDescription = null,
                                tint = ConnectedGreen,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (latencyTestState.isRunning) "${latencyTestState.completed}/${latencyTestState.total}" else "Ping",
                                color = ConnectedGreen,
                                fontWeight = FontWeight.Medium
                            )
                            if (latencyTestState.isRunning) {
                                Spacer(Modifier.width(3.dp))
                                Icon(Icons.Default.Stop, "Stop", tint = ConnectedGreen, modifier = Modifier.size(16.dp))
                            }
                        }
                        
                        TextButton(onClick = onAddProfile) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = CurrentAccent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Add",
                                color = CurrentAccent,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (latencyTestState.isRunning) {
                    Text("${latencyTestState.activeIds.size} testing in parallel · Tap counter to stop",
                        style = MaterialTheme.typography.labelSmall, color = CurrentTextSecondary,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Profiles list
                if (profiles.isEmpty()) {
                    // Empty state
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = CurrentCard
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddCircleOutline,
                                contentDescription = null,
                                tint = CurrentTextMuted,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "No profiles yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = CurrentTextSecondary
                            )
                            Text(
                                text = "Add a VPN profile to get started",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CurrentTextMuted
                            )
                            Button(
                                onClick = onAddProfile,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CurrentAccent
                                ),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Add Profile", color = DarkBackground)
                            }
                        }
                    }
                } else {
                    val filteredProfiles = profileSort.apply(if (selectedSubscriptionFilter == null) profiles else profiles.filter { it.subscriptionId == selectedSubscriptionFilter })
                    filteredProfiles.forEach { profile ->
                        ProfileCard(
                            profile = profile,
                            onShare = { onShareProfile(profile) },
                            isTestingLatency = profile.id in latencyTestState.activeIds,
                            isLatencyQueued = profile.id in latencyTestState.pendingIds,
                            isSelected = profile.id == selectedProfile?.id,
                            connectionState = connectionInfo.state,
                            onSelect = { onSelectProfile(profile) },
                            onEdit = { onEditProfile(profile) },
                            onDelete = { showDeleteDialog = profile }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
            }
            }
            adBanner()
        }
        
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { profile ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Profile?", color = CurrentTextPrimary) },
            text = { 
                Text(
                    "Are you sure you want to delete '${profile.name}'?",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteProfile(profile.id)
                        showDeleteDialog = null
                    }
                ) {
                    Text("Delete", color = StatusError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkCard,
            shape = RoundedCornerShape(28.dp)
        )
    }
    
    // Add Subscription Dialog
    if (showAddSubDialog) {
        AlertDialog(
            onDismissRequest = { showAddSubDialog = false },
            title = { Text("Add Subscription", color = CurrentTextPrimary) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Name (Optional):", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = subNameInput,
                        onValueChange = { subNameInput = it },
                        placeholder = { Text("My Subscription") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = textFieldColors(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("URL:", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = subUrlInput,
                        onValueChange = { subUrlInput = it },
                        placeholder = { Text("https://example.com/sub/...") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = textFieldColors(),
                        singleLine = true
                    )
                    OutlinedButton(onClick = { showAddSubDialog = false; onScanQr() },
                        modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(8.dp)); Text("Scan QR")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (subUrlInput.isNotBlank()) {
                            val name = subNameInput.trim().ifEmpty { null }
                            onAddSubscription(subUrlInput.trim(), name)
                            subUrlInput = ""
                            subNameInput = ""
                            showAddSubDialog = false
                        }
                    },
                    enabled = subUrlInput.isNotBlank()
                ) {
                    Text("Add", color = CurrentAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddSubDialog = false; subUrlInput = ""; subNameInput = "" }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkCard,
            shape = RoundedCornerShape(28.dp)
        )
    }

    // Edit Subscription Dialog
    showEditSubDialog?.let { sub ->
        AlertDialog(
            onDismissRequest = { showEditSubDialog = null },
            title = { Text("Edit Subscription", color = CurrentTextPrimary) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Name:", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = subNameInput,
                        onValueChange = { subNameInput = it },
                        placeholder = { Text("My Subscription") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = textFieldColors(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("URL:", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = subUrlInput,
                        onValueChange = { subUrlInput = it },
                        placeholder = { Text("https://example.com/sub/...") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = textFieldColors(),
                        singleLine = true
                    )
                    OutlinedButton(onClick = {
                        showEditSubDialog = null
                        onShareSubscription(sub)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.width(8.dp)); Text("Share subscription")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (subUrlInput.isNotBlank()) {
                            onUpdateSubscription(
                                sub.copy(
                                    name = subNameInput.trim().ifEmpty { sub.name },
                                    url = subUrlInput.trim()
                                )
                            )
                            subUrlInput = ""
                            subNameInput = ""
                            showEditSubDialog = null
                        }
                    },
                    enabled = subUrlInput.isNotBlank()
                ) {
                    Text("Save", color = CurrentAccent)
                }
            },
            dismissButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { 
                            showEditSubDialog = null
                            showDeleteSubDialog = sub
                        }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = DisconnectedRed)
                    }
                    Row {
                        TextButton(onClick = { showEditSubDialog = null; subUrlInput = ""; subNameInput = "" }) {
                            Text("Cancel", color = TextSecondary)
                        }
                    }
                }
            },
            containerColor = DarkCard,
            shape = RoundedCornerShape(28.dp)
        )
    }

    // Delete Subscription Dialog
    showDeleteSubDialog?.let { sub ->
        AlertDialog(
            onDismissRequest = { showDeleteSubDialog = null },
            title = { Text("Delete Subscription?", color = CurrentTextPrimary) },
            text = {
                Text(
                    "Are you sure you want to delete '${sub.name}'? This will also delete all associated profiles.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSubscription(sub.id)
                        showDeleteSubDialog = null
                    }
                ) {
                    Text("Delete", color = StatusError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSubDialog = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkCard,
            shape = RoundedCornerShape(28.dp)
        )
    }
}

@Composable
private fun InlineLogViewer(
    logs: List<ConnectionLog>,
    modifier: Modifier = Modifier
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = CurrentCard.copy(alpha = 0.9f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = CurrentAccent,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Connection Log",
                    style = MaterialTheme.typography.labelSmall,
                    color = CurrentAccent,
                    fontWeight = FontWeight.Medium
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            logs.forEach { log ->
                val color = when (log.level) {
                    LogLevel.INFO -> CurrentAccent
                    LogLevel.WARNING -> ConnectingAmber
                    LogLevel.ERROR -> DisconnectedRed
                    LogLevel.DEBUG -> CurrentTextMuted
                    LogLevel.SUCCESS -> ConnectedGreen
                }
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = timeFormat.format(Date(log.timestamp)),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp
                        ),
                        color = CurrentTextMuted
                    )
                    Text(
                        text = log.message,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        ),
                        color = color,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun IpAddressDisplay(
    publicIp: String,
    originalIp: String,
    localAddresses: String = "",
    countryCode: String? = null,
    mtu: Int = 0,
    ping: Long = -1L,
    connected: Boolean,
    onPingClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CurrentCard)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (connected) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Public IP", style = MaterialTheme.typography.labelSmall, color = CurrentTextSecondary)
                        Text(publicIp.ifEmpty { "Checking..." }, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = CurrentTextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    countryCode?.takeIf { it.length == 2 }?.let {
                        Text(getFlagEmoji(it.uppercase(Locale.ROOT)), style = MaterialTheme.typography.titleMedium)
                    }
                    Column(Modifier.widthIn(min = 68.dp).heightIn(min = 48.dp).appClickable(onClick = onPingClick),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Ping", style = MaterialTheme.typography.labelSmall, color = CurrentTextSecondary)
                        Text(if (ping > 0) "${ping}ms" else if (ping == -1L) "Checking..." else "Fail",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (ping in 1..200) ConnectedGreen else if (ping > 200) ConnectingAmber else DisconnectedRed)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = CurrentAccent, modifier = Modifier.size(13.dp))
                Text("Origin IP:", style = MaterialTheme.typography.labelSmall, color = CurrentTextSecondary)
                Text(originalIp.ifEmpty { "—" }, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = CurrentTextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (connected && mtu > 0) Text("MTU $mtu", style = MaterialTheme.typography.labelSmall, color = CurrentTextSecondary)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("Local IP (before VPN):",style=MaterialTheme.typography.labelSmall,color=CurrentTextSecondary)
                Text(localAddresses.ifEmpty { "No physical IPv4 address" },style=MaterialTheme.typography.labelSmall,
                    color=CurrentTextPrimary, maxLines=2)
            }
        }
    }
}

fun getFlagEmoji(countryCode: String): String {
    if (countryCode.length != 2) return ""
    val firstLetter = Character.codePointAt(countryCode, 0) - 0x41 + 0x1F1E6
    val secondLetter = Character.codePointAt(countryCode, 1) - 0x41 + 0x1F1E6
    return String(Character.toChars(firstLetter)) + String(Character.toChars(secondLetter))
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0.00B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.US, "%.2f%s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

fun formatExpire(expireSeconds: Long): String {
    if (expireSeconds <= 0L) return "infinity Days left"
    val nowSeconds = System.currentTimeMillis() / 1000L
    val diffSeconds = expireSeconds - nowSeconds
    if (diffSeconds <= 0) return "Expired"
    val days = diffSeconds / (24 * 3600)
    return if (days > 0) "$days Days left" else "Expires today"
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





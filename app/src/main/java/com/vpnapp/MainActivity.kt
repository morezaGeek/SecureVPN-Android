package com.vpnapp

import android.Manifest
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.vpnapp.data.LinkImport
import com.vpnapp.data.ShareLinkCodec
import com.vpnapp.data.TunnelEncryptionPolicy
import com.vpnapp.sharing.QrScannerActivity
import com.vpnapp.ui.components.LinkShareDialog
import com.vpnapp.ui.components.LinkSharePayload
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vpnapp.data.ConnectionState
import com.vpnapp.data.VpnProfile
import com.vpnapp.data.VpnProtocol
import com.vpnapp.ui.screens.*
import com.vpnapp.ui.theme.VPNAppTheme
import com.vpnapp.viewmodel.VpnViewModel
import com.vpnapp.vpn.VpnConnectionService

class MainActivity : ComponentActivity() {
    private val vpnViewModel by lazy { ViewModelProvider(this)[VpnViewModel::class.java] }

    override fun onStart() {
        super.onStart()
        vpnViewModel.onAppForeground()
    }

    override fun onStop() {
        if (!isChangingConfigurations) vpnViewModel.onAppBackground()
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Request notification permission for Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        
        setContent {
            VPNAppTheme {
                VpnApp(vpnViewModel)
            }
        }
        window.decorView.post {
            if (!isFinishing) com.vpnapp.ads.AdsConsent.refresh(this)
        }
    }
}

@Composable
fun VpnApp(viewModel: VpnViewModel = viewModel()) {
    val navController = rememberNavController()
    val context = LocalContext.current
    
    // Load saved theme on app start
    LaunchedEffect(Unit) {
        com.vpnapp.ui.theme.ThemeState.loadTheme(context)
    }
    
    val localAddresses by viewModel.localAddresses.collectAsState()
    val alwaysOn by viewModel.alwaysOn.collectAsState()
    val killSwitch by viewModel.killSwitch.collectAsState()
    val connectionInfo by viewModel.connectionInfo.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val selectedProfile by viewModel.selectedProfile.collectAsState()
    val smartPreparing by viewModel.smartPreparing.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val subscriptionImport by viewModel.subscriptionImport.collectAsState()
    val profileSort by viewModel.profileSort.collectAsState()
    val selectedSubscriptionId by viewModel.selectedSubscriptionId.collectAsState()
    val refreshingSubscriptions by viewModel.refreshingSubscriptions.collectAsState()
    val latencyTestState by viewModel.latencyTestState.collectAsState()
    
    // State for editing profile
    var profileToEdit by remember { mutableStateOf<VpnProfile?>(null) }
    
    // State for Add Profile dialog
    var showAddProfileDialog by remember { mutableStateOf(false) }
    var showV2rayOptionsDialog by remember { mutableStateOf(false) }
    val disclosurePreferences = remember(context) {
        context.getSharedPreferences("privacy_disclosures", android.content.Context.MODE_PRIVATE)
    }
    var vpnDisclosureAccepted by rememberSaveable {
        mutableStateOf(disclosurePreferences.getBoolean("vpn_traffic_v1", false))
    }
    var showVpnDisclosure by rememberSaveable { mutableStateOf(false) }
    var selectedProtocolForNew by remember { mutableStateOf<VpnProtocol?>(null) }
    var importFromClipboard by remember { mutableStateOf(false) }
    
    var pendingImport by rememberSaveable { mutableStateOf<String?>(null) }
    var sharePayload by remember { mutableStateOf<LinkSharePayload?>(null) }
    var showCameraPermissionHelp by remember { mutableStateOf(false) }
    fun previewImport(text: String) {
        if (ShareLinkCodec.parseImport(text) != null) pendingImport = com.vpnapp.data.LinkImportInput.normalize(text)
        else Toast.makeText(context, "QR or clipboard does not contain a supported VPN link or subscription URL", Toast.LENGTH_LONG).show()
    }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { previewImport(it) }
    }
    fun launchScanner() {
        scanLauncher.launch(ScanOptions().setCaptureActivity(QrScannerActivity::class.java)
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE).setOrientationLocked(false)
            .setBeepEnabled(false).setBarcodeImageEnabled(false)
            .setPrompt("Scan a VPN configuration or subscription QR code"))
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchScanner() else showCameraPermissionHelp = true
    }
    fun scanQr() {
        showAddProfileDialog = false; showV2rayOptionsDialog = false
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Toast.makeText(context, "This device has no camera", Toast.LENGTH_SHORT).show()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchScanner()
        } else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
    fun importClipboard() {
        showAddProfileDialog = false; showV2rayOptionsDialog = false
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context)?.toString() else null
        if (text.isNullOrBlank()) Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        else previewImport(text)
    }

    // Function to start VPN service
    fun startVpnService(profile: VpnProfile) {
        viewModel.refreshLocalAddresses()
        val intent = Intent(context, VpnConnectionService::class.java).apply {
            action = VpnConnectionService.ACTION_CONNECT
            com.vpnapp.vpn.singbox.TunnelDnsSettings(viewModel.tunnelDnsUrl.value,viewModel.useFakeIp.value).writeTo(this)
            com.vpnapp.vpn.LanShareSettings.read(context).writeTo(this)
            putExtra(VpnConnectionService.EXTRA_SERVER_IP_INPUT, profile.serverAddress)
            putExtra(VpnConnectionService.EXTRA_SERVER_PORT, profile.port)
            putExtra(VpnConnectionService.EXTRA_PROTOCOL, profile.protocol.name)
            putExtra(VpnConnectionService.EXTRA_USERNAME, profile.username)
            putExtra(VpnConnectionService.EXTRA_PASSWORD, profile.password)
            putExtra(VpnConnectionService.EXTRA_SKIP_CERT_VERIFY, profile.skipCertificateVerification)
            // Pass profile name for notification
            putExtra(VpnConnectionService.EXTRA_PROFILE_NAME, profile.name)
            putExtra(VpnConnectionService.EXTRA_PROFILE_ID, profile.id)

            // Pass V2ray specific fields
            android.util.Log.d("MainActivity", "Starting VPN: ${profile.protocol}")
            putExtra(VpnConnectionService.EXTRA_V2RAY_UUID, profile.v2rayUuid)
            putExtra(VpnConnectionService.EXTRA_V2RAY_ALTERID, profile.v2rayAlterId)
            putExtra(VpnConnectionService.EXTRA_V2RAY_SECURITY, profile.v2raySecurity)
            putExtra(VpnConnectionService.EXTRA_VLESS_ENCRYPTION, profile.vlessEncryption)
            putExtra(VpnConnectionService.EXTRA_V2RAY_NETWORK, profile.v2rayNetwork)
            putExtra(VpnConnectionService.EXTRA_V2RAY_TYPE, profile.v2rayType)
            putExtra("v2ray_xhttp_extra", profile.v2rayXhttpExtra)
            putExtra(VpnConnectionService.EXTRA_V2RAY_HOST, profile.v2rayHost)
            putExtra(VpnConnectionService.EXTRA_V2RAY_PATH, profile.v2rayPath)
            putExtra(VpnConnectionService.EXTRA_V2RAY_TLS, profile.v2rayTls)
            putExtra(VpnConnectionService.EXTRA_V2RAY_SNI, profile.v2raySni)
            putExtra(VpnConnectionService.EXTRA_V2RAY_ALPN, profile.v2rayAlpn)
            putExtra(VpnConnectionService.EXTRA_V2RAY_FP, profile.v2rayFp)
            putExtra(VpnConnectionService.EXTRA_V2RAY_FLOW, profile.v2rayFlow)
            putExtra(VpnConnectionService.EXTRA_V2RAY_PBK, profile.v2rayPbk)
            putExtra(VpnConnectionService.EXTRA_V2RAY_SHORT_ID, profile.v2rayShortId)
            putExtra("v2ray_ech", profile.v2rayEch)
            putExtra("hysteria_obfs_min", profile.hysteriaObfsMinPacketSize)
            putExtra("hysteria_obfs_max", profile.hysteriaObfsMaxPacketSize)
            putExtra("hysteria_obfs", profile.hysteriaObfs)
            putExtra("hysteria_obfs_password", profile.hysteriaObfsPassword)

            // Pass global performance settings
            putExtra(VpnConnectionService.EXTRA_BUFFER_SIZE, viewModel.bufferSize.value)
            putExtra(VpnConnectionService.EXTRA_TCP_NODELAY, viewModel.tcpNoDelay.value)
            // Pass DTLS disable setting from profile
            putExtra(VpnConnectionService.EXTRA_DISABLE_DTLS, profile.disableDtls)
            putExtra(VpnConnectionService.EXTRA_MTU, viewModel.mtuSize.value)
            putExtra(VpnConnectionService.EXTRA_USE_NATIVE, profile.useNative)
            // Pass split tunneling apps
            putStringArrayListExtra(VpnConnectionService.EXTRA_EXCLUDED_APPS, ArrayList(viewModel.excludedApps.value))
            // Pass bypass Iran setting
            putExtra("bypass_iran", viewModel.bypassIran.value)
            // Pass bypass Private IPs setting
            putExtra("bypass_private_ips", viewModel.bypassPrivateIps.value)
        }
        context.startService(intent)
    }

    // ... (rest of code) ...

    // Function to stop VPN service
    fun stopVpnService() {
        val intent = Intent(context, VpnConnectionService::class.java).apply {
            action = VpnConnectionService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }

    // Broadcast Receiver to update ViewModel from Service
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                if (intent.action == VpnConnectionService.ACTION_PROTECTION_STATE) {
                    viewModel.alwaysOn.value=intent.getBooleanExtra("always_on",false)
                    viewModel.killSwitch.value=intent.getBooleanExtra("lockdown",false)
                }
                if (intent.action == VpnConnectionService.ACTION_VPN_STATUS) {
                    val status = intent.getStringExtra(VpnConnectionService.EXTRA_STATUS)
                    if (status == "CONNECTED") {
                        com.vpnapp.vpn.singbox.ProbeEndpoint.active = com.vpnapp.vpn.singbox.ProbeEndpoint.readFrom(intent)
                    } else if (status == "DISCONNECTING" || status == "DISCONNECTED" || status == "ERROR") {
                        com.vpnapp.vpn.singbox.ProbeEndpoint.active = null
                    }
                    val privateIp = intent.getStringExtra(VpnConnectionService.EXTRA_PRIVATE_IP)
                    val serverIp = intent.getStringExtra(VpnConnectionService.EXTRA_SERVER_IP)
                    val mtu = intent.getIntExtra(VpnConnectionService.EXTRA_MTU, 1400)
                    val message = intent.getStringExtra("message")
                    val profileName = intent.getStringExtra(VpnConnectionService.EXTRA_PROFILE_NAME)
                    
                    // If connected and profile name is provided, restore the correct profile selection
                    if (status == "CONNECTED" && profileName != null) {
                        viewModel.restoreActiveProfile(profileName,
                            intent.getStringExtra(VpnConnectionService.EXTRA_PROFILE_ID),
                            intent.getStringExtra(VpnConnectionService.EXTRA_PROTOCOL))
                    }
                    
                    viewModel.updateConnectionStatus(status, privateIp, serverIp, mtu, message,
                        intent.getIntExtra("closed_worker_pid", 0))
                } else if (intent.action == VpnConnectionService.ACTION_VPN_STATS) {
                    val rx = intent.getLongExtra(VpnConnectionService.EXTRA_RX_BYTES, 0L)
                    val tx = intent.getLongExtra(VpnConnectionService.EXTRA_TX_BYTES, 0L)
                    val transport = intent.getStringExtra(VpnConnectionService.EXTRA_TRANSPORT) ?: "TCP"
                    val connectionTime = intent.getLongExtra(VpnConnectionService.EXTRA_CONNECTION_TIME, 0L)
                    
                    // Log stats receipt for debugging
                    android.util.Log.d("MainActivity", "Stats received: Time=$connectionTime RX=$rx TX=$tx Type=$transport")
                    
                    viewModel.updateTrafficStats(rx, tx, transport, connectionTime)
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(VpnConnectionService.ACTION_PROTECTION_STATE)
            addAction(VpnConnectionService.ACTION_VPN_STATUS)
            addAction(VpnConnectionService.ACTION_VPN_STATS)
        }
        // For SDK 34, usually requires flag. Using ContextCompat or just 0/EXPORTED for global broadcast from service?
        // Service uses sendBroadcast(), which is global. RECEIVER_EXPORTED allows receiving from other apps.
        // But intent is implicit? No, explicit action.
        // We'll use 4 (RECEIVER_NOT_EXPORTED) if possible, but simpler:
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
    
    // Request current state from service when app resumes (fixes UI sync issue)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        fun requestState() {
            try {
                context.startService(Intent(context, VpnConnectionService::class.java).apply {
                    action = VpnConnectionService.ACTION_REQUEST_STATE
                })
            } catch (e: Exception) {
                android.util.Log.d("MainActivity", "Service state request unavailable")
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) requestState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) requestState()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    
    // VPN Permission launcher
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Permission granted, start VPN service and update UI
            selectedProfile?.let { profile ->
                viewModel.prepareConnection(::startVpnService)
            }
        } else {
            Toast.makeText(context, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    val connectWithSystemPermission: () -> Unit = {
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            viewModel.prepareConnection(::startVpnService)
        }
    }
    
    // Function to request VPN permission and connect
    val requestVpnPermissionAndConnect: () -> Unit = {
        val profile = selectedProfile
        if (connectionInfo.state == ConnectionState.CONNECTED) {
            stopVpnService()
            viewModel.toggleConnection()
        } else if (profile == null) {
            Toast.makeText(context, "Select a profile first", Toast.LENGTH_SHORT).show()
        } else if (connectionInfo.state == ConnectionState.CONNECTING || connectionInfo.state == ConnectionState.DISCONNECTING) {
            // Ignore taps until the service has completed its transition.
        } else if (!vpnDisclosureAccepted) {
            showVpnDisclosure = true
        } else {
            val encryptionIssue = TunnelEncryptionPolicy.blockingReason(profile)
            if (encryptionIssue != null) {
                viewModel.rejectInsecureProfile(profile, encryptionIssue)
            } else {
                connectWithSystemPermission()
            }
        }
    }
    
    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = Modifier.fillMaxSize(),
        enterTransition = { fadeIn(tween(180)) },
        exitTransition = { fadeOut(tween(120)) },
        popEnterTransition = { fadeIn(tween(180)) },
        popExitTransition = { fadeOut(tween(120)) }
    ) {
        composable("home") {
            HomeScreen(
                adBanner = { com.vpnapp.ads.ConnectionBanner(connectionInfo.state) },
                smartPreparing = smartPreparing,
                onCancelSmartConnection = viewModel::cancelSmartConnection,
                localAddresses = localAddresses,
                connectionInfo = connectionInfo,
                profiles = profiles,
                selectedProfile = selectedProfile,
                logs = logs,
                subscriptions = subscriptions,
                selectedSubscriptionId = selectedSubscriptionId,
                onSelectSubscription = viewModel::selectSubscription,
                refreshingSubscriptions = refreshingSubscriptions,
                latencyTestState = latencyTestState,
                profileSort = profileSort,
                onProfileSort = viewModel::setProfileSort,
                onScanQr = ::scanQr,
                onShareProfile = { profile ->
                    try { sharePayload = LinkSharePayload(profile.name, ShareLinkCodec.export(profile)) }
                    catch (_: Exception) { Toast.makeText(context, "This configuration cannot be exported", Toast.LENGTH_SHORT).show() }
                },
                onShareSubscription = { sub -> sharePayload = LinkSharePayload(sub.name, sub.url) },
                onToggleConnection = requestVpnPermissionAndConnect,
                onSelectProfile = { viewModel.selectProfile(it) },
                onEditProfile = { profile ->
                    profileToEdit = profile
                    navController.navigate("add_edit_profile")
                },
                onDeleteProfile = { viewModel.deleteProfile(it) },
                onAddProfile = { 
                    showAddProfileDialog = true
                },
                onMeasureLatency = { viewModel.measureAllLatencies() },
                onStopLatency = viewModel::stopLatencyTests,
                onUsageHistory = { navController.navigate("usage_history") },
                onTestCurrentLatency = { viewModel.testCurrentLatency() },
                onNavigateToLogs = { navController.navigate("logs") },
                onNavigateToSettings = { navController.navigate("settings") },
                onAddSubscription = { url, name -> viewModel.addSubscription(url, name) },
                onRefreshSubscription = { viewModel.refreshSubscription(it) },
                onRefreshAllSubscriptions = { viewModel.refreshAllSubscriptions() },
                onDeleteSubscription = { viewModel.deleteSubscription(it) },
                onUpdateSubscription = { viewModel.updateSubscription(it) }
            )
        }
        
        composable("add_edit_profile") {
            AddEditProfileScreen(
                existingProfile = profileToEdit,
                initialProtocol = selectedProtocolForNew ?: VpnProtocol.OPENCONNECT,
                onSave = { profile ->
                    if (profileToEdit != null) {
                        viewModel.updateProfile(profile)
                    } else {
                        viewModel.addProfile(profile)
                    }
                    navController.popBackStack()
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable("logs") {
            LogsScreen(
                logs = logs,
                onClearLogs = { viewModel.clearLogs() },
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable("settings") {
            val bufferSize by viewModel.bufferSize.collectAsState()
            val tcpNoDelay by viewModel.tcpNoDelay.collectAsState()
            val mtuSize by viewModel.mtuSize.collectAsState()
            val tunnelDnsUrl by viewModel.tunnelDnsUrl.collectAsState()
            val useFakeIp by viewModel.useFakeIp.collectAsState()
            val excludedApps by viewModel.excludedApps.collectAsState()
            val bypassIran by viewModel.bypassIran.collectAsState()
            val bypassPrivateIps by viewModel.bypassPrivateIps.collectAsState()
            val forceXhttpStreamUp by viewModel.forceXhttpStreamUp.collectAsState()
            
            SettingsScreen(
                bufferSize = bufferSize,
                tcpNoDelay = tcpNoDelay,
                mtuSize = mtuSize,
                onMtuSizeChange = viewModel::setMtuSize,
                tunnelDnsUrl = tunnelDnsUrl,
                useFakeIp = useFakeIp,
                onTunnelDnsUrlChange = viewModel::setTunnelDnsUrl,
                onUseFakeIpChange = viewModel::setUseFakeIp,
                bypassIran = bypassIran,
                bypassPrivateIps = bypassPrivateIps,
                forceXhttpStreamUp = forceXhttpStreamUp,
                onForceXhttpStreamUpChange = viewModel::setForceXhttpStreamUp,
                excludedApps = excludedApps,
                onBufferSizeChange = { viewModel.setBufferSize(it) },
                onTcpNoDelayChange = { viewModel.setTcpNoDelay(it) },
                onBypassIranChange = { viewModel.setBypassIran(it) },
                onBypassPrivateIpsChange = { viewModel.setBypassPrivateIps(it) },
                onNavigateToSplitTunnel = { navController.navigate("split_tunnel") },
                onManagePrivacyOptions = {
                    (context as? Activity)?.let(com.vpnapp.ads.AdsConsent::showPrivacyOptions)
                },
                onNetworkTools = { navController.navigate("network_tools") },
                onUsageHistory = { navController.navigate("usage_history") },
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("network_tools") {
            com.vpnapp.ui.screens.NetworkToolsScreen(alwaysOn,killSwitch,
                connectionInfo.state == ConnectionState.CONNECTED) { navController.popBackStack() }
        }
        composable("usage_history") {
            com.vpnapp.ui.screens.UsageHistoryScreen { navController.popBackStack() }
        }

        composable("split_tunnel") {
            val excludedApps by viewModel.excludedApps.collectAsState()
            
            SplitTunnelScreen(
                excludedApps = excludedApps,
                onExcludedAppsChange = { viewModel.setExcludedApps(it) },
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
    
    LaunchedEffect(subscriptionImport.imported) {
        subscriptionImport.imported?.let {
            Toast.makeText(context, "$it configurations imported", Toast.LENGTH_LONG).show()
            viewModel.clearSubscriptionImportResult()
        }
    }
    if (subscriptionImport.loading || subscriptionImport.error != null) AlertDialog(
        onDismissRequest = { if (!subscriptionImport.loading) viewModel.clearSubscriptionImportResult() },
        title = { Text(if (subscriptionImport.loading) "Importing subscription" else "Import failed") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (subscriptionImport.loading) CircularProgressIndicator()
            Text(subscriptionImport.error ?: "Downloading and reading VPN configurations…")
        } },
        confirmButton = { TextButton(onClick = {
            if (subscriptionImport.loading) viewModel.cancelSubscriptionImport() else viewModel.clearSubscriptionImportResult()
        }) { Text(if (subscriptionImport.loading) "Cancel" else "OK") } })

    sharePayload?.let { LinkShareDialog(it) { sharePayload = null } }
    pendingImport?.let { text ->
        val parsed = remember(text) { ShareLinkCodec.parseImport(text) }
        var subscriptionName by rememberSaveable(text) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { pendingImport = null },
            title = { Text(if (parsed is LinkImport.Subscription) "Add subscription" else "Import configuration") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (parsed) {
                        is LinkImport.Profile -> {
                            Text(parsed.profile.name)
                            Text("${parsed.profile.protocol.displayName} · ${parsed.profile.serverAddress}:${parsed.profile.port}")
                        }
                        is LinkImport.Subscription -> {
                            Text(parsed.url, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            OutlinedTextField(value = subscriptionName, onValueChange = { subscriptionName = it },
                                label = { Text("Subscription name (optional)") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth())
                        }
                        null -> Text("Unsupported link")
                    }
                }
            }, confirmButton = {
                TextButton(enabled = parsed != null, onClick = {
                    when (parsed) {
                        is LinkImport.Profile -> {
                            viewModel.addProfile(parsed.profile)
                            Toast.makeText(context, "Configuration imported", Toast.LENGTH_SHORT).show()
                        }
                        is LinkImport.Subscription -> viewModel.addSubscription(parsed.url, subscriptionName.trim().ifEmpty { null })
                        null -> Unit
                    }
                    pendingImport = null
                }) { Text("Import") }
            }, dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } })
    }
    if (showCameraPermissionHelp) AlertDialog(onDismissRequest = { showCameraPermissionHelp = false },
        title = { Text("Camera permission") },
        text = { Text("Allow camera access in app settings to scan QR codes. You can also import a link from the clipboard.") },
        confirmButton = {
            TextButton(onClick = {
                showCameraPermissionHelp = false
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text("Open settings") }
        }, dismissButton = { TextButton(onClick = { showCameraPermissionHelp = false }) { Text("Cancel") } })

    if (showVpnDisclosure) AlertDialog(
        onDismissRequest = { showVpnDisclosure = false },
        title = { Text("Before you connect") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "SecureVPN requires an encrypted connection from your device to the selected VPN server. Profiles without TLS, REALITY, or protocol encryption cannot connect. Android sends routed traffic through that server, whose operator may process or log it under their own terms. SecureVPN does not operate those servers."
                )
                Text(
                    "IP and latency checks contact external providers, which can receive your public IP and technical request data. Apps you exclude in Split Tunneling use your regular network."
                )
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://securevpn.rahanetmci.com/privacy/")))
                    }.onFailure {
                        Toast.makeText(context, "Could not open the privacy policy", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Read Privacy Policy") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                disclosurePreferences.edit().putBoolean("vpn_traffic_v1", true).apply()
                vpnDisclosureAccepted = true
                showVpnDisclosure = false
                connectWithSystemPermission()
            }) { Text("Agree and continue") }
        },
        dismissButton = {
            TextButton(onClick = { showVpnDisclosure = false }) { Text("Cancel") }
        }
    )

    // Protocol Selection Dialog
    if (showAddProfileDialog) {
        AlertDialog(
            onDismissRequest = { showAddProfileDialog = false },
            title = { 
                Text(
                    "Select Protocol",
                    color = com.vpnapp.ui.theme.TextPrimary
                )
            },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = ::scanQr, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(8.dp)); Text("Scan QR")
                    }
                    OutlinedButton(onClick = ::importClipboard, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null)
                        Spacer(Modifier.width(8.dp)); Text("Import from Clipboard")
                    }
                    // OpenConnect
                    OutlinedButton(
                        onClick = {
                            showAddProfileDialog = false
                            selectedProtocolForNew = VpnProtocol.OPENCONNECT
                            importFromClipboard = false
                            profileToEdit = null
                            navController.navigate("add_edit_profile")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = com.vpnapp.ui.theme.OpenConnectColor
                        )
                    ) {
                        Icon(Icons.Default.VpnLock, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("OpenConnect")
                    }
                    
                    // V2ray
                    Button(
                        onClick = {
                            showAddProfileDialog = false
                            showV2rayOptionsDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF9800)
                        )
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("V2ray (VLESS/VMess/Trojan)")
                    }
                    
                    // SSTP
                    OutlinedButton(
                        onClick = {
                            showAddProfileDialog = false
                            selectedProtocolForNew = VpnProtocol.SSTP
                            importFromClipboard = false
                            profileToEdit = null
                            navController.navigate("add_edit_profile")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = com.vpnapp.ui.theme.SstpColor
                        )
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("SSTP")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddProfileDialog = false }) {
                    Text("Cancel", color = com.vpnapp.ui.theme.TextSecondary)
                }
            },
            containerColor = com.vpnapp.ui.theme.DarkCard,
            shape = RoundedCornerShape(16.dp)
        )
    }
    
    // V2ray Options Dialog
    if (showV2rayOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showV2rayOptionsDialog = false },
            title = { 
                Text(
                    "Add V2ray Config",
                    color = com.vpnapp.ui.theme.TextPrimary
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Import from Clipboard
                    Button(
                        onClick = ::importClipboard,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = com.vpnapp.ui.theme.CurrentAccent
                        )
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import from Clipboard")
                    }
                    
                    OutlinedButton(onClick = ::scanQr, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(8.dp)); Text("Scan QR")
                    }
                    // Add Manually
                    OutlinedButton(
                        onClick = {
                            showV2rayOptionsDialog = false
                            selectedProtocolForNew = VpnProtocol.VLESS
                            importFromClipboard = false
                            profileToEdit = null
                            navController.navigate("add_edit_profile")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = com.vpnapp.ui.theme.CurrentAccent
                        )
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add Config Manually")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showV2rayOptionsDialog = false }) {
                    Text("Cancel", color = com.vpnapp.ui.theme.TextSecondary)
                }
            },
            containerColor = com.vpnapp.ui.theme.DarkCard,
            shape = RoundedCornerShape(16.dp)
        )
    }
}



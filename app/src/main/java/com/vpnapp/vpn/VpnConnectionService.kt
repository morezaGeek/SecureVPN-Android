package com.vpnapp.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.vpnapp.MainActivity
import com.vpnapp.R
import com.vpnapp.data.ConnectionState
import com.vpnapp.data.LogLevel
import com.vpnapp.data.VpnProfile
import com.vpnapp.data.VpnProtocol
import com.vpnapp.vpn.singbox.SingBoxConfigGenerator
import com.vpnapp.vpn.singbox.SingBoxEngine
import com.vpnapp.vpn.sstp.SstpEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import android.content.pm.ServiceInfo
import com.vpnapp.util.FileLogger
import android.net.TrafficStats
import android.os.Process

/**
 * VPN Connection Service
 * 
 * Creates a VPN tunnel and routes traffic through the VPN server.
 */
class VpnConnectionService : VpnService() { //, V2RayVPNServiceSupportsSet { // Implement interface
    
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnOutputStream: FileOutputStream? = null  // Cached output stream for writing to VPN
    private var currentProtocol: VpnProtocol = VpnProtocol.OPENCONNECT
    private var sstpEngine: SstpEngine? = null
    private var openConnectEngine: com.vpnapp.vpn.openconnect.OpenConnectEngine? = null
    private var nativeOpenConnectEngine: com.vpnapp.vpn.openconnect.NativeOpenConnectEngine? = null
    private var serviceScope: CoroutineScope? = null
    private var useNativeEngine = true
    private val routingLock = Any()
    private val tunnelLock = Any()
    private var adSessionRecorded = false
    private fun recordSuccessfulAdCycleSession() {
        if (adSessionRecorded || notificationSession <= 0) return
        runCatching { usage.record(usageSession,0,0,connectionStartTime) }
        runCatching { com.vpnapp.ads.AdCycleStore(this).connected(notificationSession) }
            .onSuccess { adSessionRecorded = true }
            .onFailure { Log.w(TAG, "Could not record local ad cycle; VPN unaffected", it) }
    }
    private val notificationLock = Any()
    private var notificationMonitor: Job? = null
    private var sessionMonitor: Job? = null
    private val uiVisible = MutableStateFlow(false)
    private var lanCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var lastLanStatus: LanProxyStatus? = null
    private fun observeLanNetwork() {
        if (lanCallback != null) return
        val manager = getSystemService(android.net.ConnectivityManager::class.java)
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            private fun changed() {
                if (runtimeLan?.enabled == true && !isDisconnecting.get())
                    serviceScope?.launch { runCatching { syncLanProxy() } }
            }
            override fun onAvailable(network: android.net.Network) = changed()
            override fun onLost(network: android.net.Network) = changed()
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) = changed()
        }
        runCatching {
            manager.registerNetworkCallback(android.net.NetworkRequest.Builder().clearCapabilities()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_VPN).build(), callback)
            lanCallback = callback
        }.onFailure { Log.w(TAG, "LAN network observation unavailable", it) }
    }
    private fun stopObservingLanNetwork() {
        lanCallback?.let { callback ->
            runCatching { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        }
        lanCallback = null
    }
    private var lanProxy: LanVpnProxy? = null
    private var lanNetwork: android.net.Network? = null
    private var lanConfig: LanShareSettings? = null
    private var lanStartError: String? = null
    private val lanLock=Any()
    @Volatile private var runtimeLan: LanShareSettings? = null
    @Volatile private var runtimeDns: com.vpnapp.vpn.singbox.TunnelDnsSettings? = null
    private val usage by lazy { com.vpnapp.data.UsageHistory(this) }
    private var systemManaged = false
    private var usageSession = java.util.UUID.randomUUID().toString()
    private fun counters(): Pair<Long,Long> = singBoxEngine?.trafficTotals() ?: (rxBytes.get() to txBytes.get())
    private fun recordUsage() {
        if(connectionStartTime <= 0) return
        val (rx,tx)=counters()
        runCatching { usage.record(usageSession,rx,tx) }.onFailure { Log.w(TAG,"Usage checkpoint failed",it) }
    }
    private fun syncLanProxy(): Unit = synchronized(lanLock) {
        val config=runtimeLan ?: LanShareSettings.read(this)
        val cm=getSystemService(android.net.ConnectivityManager::class.java)
        val network=if (_isRunning.value && config.enabled && !isDisconnecting.get() && !isCancellationRequested.get() && !serviceDestroyed) cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)==true
        } else null
        if(config==lanConfig && network==lanNetwork && (network==null || lanProxy!=null)) {
            publishLanStatus(config)
            return
        }
        runCatching { lanProxy?.close() };lanProxy=null;lanNetwork=network;lanConfig=config
        lanStartError=null
        if(network!=null) runCatching { lanProxy=LanVpnProxy(config,network,protectClient={ protect(it) }) }
            .onFailure { Log.w(TAG,"LAN proxy could not start",it)
                lanStartError="Could not open this proxy port. Choose another port and save, or reconnect VPN."
                sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS,"LOG")
                    .putExtra("message","LAN sharing could not start: ${it.message}")) }
        publishLanStatus(config)
    }
    private fun publishLanStatus(config: LanShareSettings = runtimeLan ?: LanShareSettings.read(this), force: Boolean = false) {
        val proxy=lanProxy
        val status=when {
            !config.enabled -> LanProxyStatus("OFF",config.protocol.name,config.port)
            isDisconnecting.get() || isCancellationRequested.get() || serviceDestroyed -> LanProxyStatus("OFF",config.protocol.name,config.port)
            proxy!=null -> proxy.status()
            lanStartError!=null -> LanProxyStatus("ERROR",config.protocol.name,config.port,lastError=lanStartError)
            else -> LanProxyStatus("WAITING",config.protocol.name,config.port)
        }
        if (force || status != lastLanStatus) {
            lastLanStatus = status
            sendBroadcast(Intent(ACTION_LAN_SHARE_STATUS).setPackage(packageName).also(status::writeTo))
        }
    }
    private fun startSessionMonitor() {
        sessionMonitor?.cancel()
        observeLanNetwork()
        sessionMonitor=serviceScope?.launch {
            while(isActive) {
                delay(30_000)
                recordUsage()
            }
        }
    }

    private val isConnecting = java.util.concurrent.atomic.AtomicBoolean(false)
    private val isCancellationRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var serviceDestroyed = false
    private val shutdown = BoundedShutdown()
    
    // Wake locks to prevent disconnect when screen is off
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    
    // Connection start time for timer display
    @Volatile private var connectionStartTime: Long = 0L
    private val sessionClock = SessionClock(android.os.SystemClock::elapsedRealtime, System::currentTimeMillis)
    @Volatile private var workerProcessVerified = false
    private fun markConnectionStarted() {
        sessionClock.start()
        connectionStartTime = sessionClock.startedWallMillis
    }
    private fun terminateWorkerNow() {
        check(workerProcessVerified) { "Refusing to terminate the application process" }
        Process.killProcess(Process.myPid())
    }
    private var currentMtu = 1400
    
    // Stats broadcasting job for native engine
    private var statsBroadcasterJob: Job? = null
    
    // Traffic baseline for TrafficStats API
    private var baselineRxBytes: Long = 0L
    private var baselineTxBytes: Long = 0L
    
    // Public IP address (received from server)
    private var currentPublicIp: String = ""
    private var currentCountryCode: String? = null
    
    // V2Ray Profile Holder
    private var v2rayProfile: VpnProfile? = null
    private var singBoxEngine: SingBoxEngine? = null
    
    // Packet counters for logging
    private var packetsSent = 0
    private var packetsReceived = 0
    
    // ...

    // ...
    
    private fun startStatsBroadcaster() {
        stopStatsBroadcaster() // clear any existing job
        
        val isV2Ray = singBoxEngine != null
        serviceScope?.launch { runCatching { syncLanProxy() } }
        statsBroadcasterJob = serviceScope?.launch {
            uiVisible.collectLatest { visible ->
                if (!visible) return@collectLatest
                while (_isRunning.value && !isDisconnecting.get()) {
                
                    val rx: Long
                    val tx: Long
                    val transport: String
                
                    if (isV2Ray) {
                        val totals=counters()
                        rx=totals.first
                        tx=totals.second
                        rxBytes.set(rx);txBytes.set(tx)
                        transport = when (v2rayProfile?.v2rayNetwork?.lowercase()) {
                            "ws", "websocket" -> "WebSocket"
                            "grpc" -> "gRPC"
                            "h2", "http" -> "HTTP/2"
                            else -> "TCP"
                        }
                    } else {
                        // For OpenConnect/SSTP, use our tracked bytes
                        rx = rxBytes.get()
                        tx = txBytes.get()
                        transport = nativeOpenConnectEngine?.getActiveTransport() ?: "TCP"
                    }
                
                    val duration = sessionClock.durationSeconds()
                
                    sendBroadcast(Intent(ACTION_VPN_STATS).setPackage(packageName).apply {
                        putExtra(EXTRA_RX_BYTES, rx)
                        putExtra(EXTRA_TX_BYTES, tx)
                        putExtra(EXTRA_TRANSPORT, transport)
                        putExtra(EXTRA_CONNECTION_TIME, duration)
                    })
                    delay(1000)
                }
            }
        }
    }
    
    private fun stopStatsBroadcaster() {
        try {
            statsBroadcasterJob?.cancel()
            statsBroadcasterJob = null
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling stats job", e)
        }
    }
    
    /**
     * Convert subnet mask (e.g., "255.255.0.0") to prefix length (e.g., 16)
     * Also handles direct prefix length strings (e.g., "24")
     */
    private fun netmaskToPrefixLength(netmask: String): Int {
        // First, try to parse as integer (already prefix length)
        netmask.toIntOrNull()?.let { return it }
        
        // Parse as dotted decimal netmask
        return try {
            val parts = netmask.split(".")
            if (parts.size != 4) return -1
            
            var bits = 0
            for (part in parts) {
                val octet = part.toIntOrNull() ?: return -1
                // Count set bits in each octet
                var b = octet
                while (b != 0) {
                    bits += b and 1
                    b = b shr 1
                }
            }
            bits
        } catch (e: Exception) {
            -1
        }
    }

    private fun startPacketForwarding() {
        serviceScope?.launch(Dispatchers.IO) {
            val vpnFd = vpnInterface?.fileDescriptor ?: return@launch
            val inputStream = FileInputStream(vpnFd)
            val buffer = ByteArray(32767)
            
            Log.d(TAG, "Packet forwarding started - reading from VPN interface")
            
            
            // Stats Broadcaster moved to startStatsBroadcaster()
            
            try {
                while (_isRunning.value && vpnInterface != null) {
                    // Blocking read: waits until 1+ bytes are available
                    val length = inputStream.read(buffer)
                    if (length > 0) {
                        val packet = ByteArray(length)
                        System.arraycopy(buffer, 0, packet, 0, length)
                        
                        // TX (Upload to server)
                        txBytes.addAndGet(length.toLong())
                        packetsSent++
                        
                        // Log first few packets for debugging
                        if (packetsSent <= 5) {
                            Log.d(TAG, "TX Packet #$packetsSent: $length bytes")
                        }
                        
                        // Forward to active engine
                        sstpEngine?.sendDataPacket(packet)
                        // Use DTLS-aware send (auto-fallback to TLS if DTLS not active)
                        openConnectEngine?.sendDataPacketDtls(packet)
                        
                    } else if (length < 0) {
                        break // EOF
                    }
                }
            } catch (e: Exception) {
                if (_isRunning.value) Log.e(TAG, "Packet forwarding error", e)
            }
            Log.d(TAG, "Packet forwarding stopped. Total sent: $packetsSent")
        }
    }

    private val isDisconnecting = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun disconnect() {
        if (!isDisconnecting.compareAndSet(false, true)) return
        isCancellationRequested.set(true)
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        beginShutdown()
    }

    private fun beginShutdown() {
        shutdown.begin(cleanup = {
            sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                putExtra(EXTRA_STATUS, "DISCONNECTING")
                putExtra("closed_worker_pid", Process.myPid())
            })
            runBlocking { sessionMonitor?.cancelAndJoin() }; sessionMonitor = null
            stopObservingLanNetwork()
            uiVisible.value = false
            notificationMonitor?.cancel(); notificationMonitor = null
            recordUsage()
            synchronized(lanLock) {
                runCatching { lanProxy?.close() }; lanProxy = null
                publishLanStatus()
            }
            if (adSessionRecorded) runCatching {
                com.vpnapp.ads.AdCycleStore(this).disconnected(notificationSession)
            }.onFailure { Log.w(TAG, "Could not complete local ad cycle; VPN unaffected", it) }
            synchronized(tunnelLock) {
                try { vpnInterface?.close() } catch (e: Exception) { Log.w(TAG, "TUN cleanup failed", e) }
                vpnInterface = null
            }
            releaseConnectionResources()
        }, terminate = {
            _isRunning.value = false
            isConnecting.set(false)
            serviceScope?.cancel()
            com.vpnapp.vpn.singbox.ProbeEndpoint.active = null
            try {
                synchronized(notificationLock) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                }
                sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                    putExtra(EXTRA_STATUS, STATUS_DISCONNECTED)
                    putExtra("closed_worker_pid", Process.myPid())
                })
                stopSelf()
            } finally { terminateWorkerNow() }
        }, forceTerminate = { terminateWorkerNow() })
    }

    private fun releaseConnectionResources() = synchronized(routingLock) {
        _isRunning.value = false
        connectionStartTime = 0L
        sessionClock.clear()
        stopStatsBroadcaster()
        // Each resource gets its own cleanup so one failure cannot skip the others.
        try { sstpEngine?.disconnect() } catch (e: Exception) { Log.w(TAG, "SSTP cleanup failed", e) }
        try { openConnectEngine?.disconnect() } catch (e: Exception) { Log.w(TAG, "OpenConnect cleanup failed", e) }
        try { nativeOpenConnectEngine?.disconnect() } catch (e: Exception) { Log.w(TAG, "Native cleanup failed", e) }
        try { singBoxEngine?.stop() } catch (e: Exception) { Log.w(TAG, "Sing-box cleanup failed", e) }
        sstpEngine = null
        openConnectEngine = null
        nativeOpenConnectEngine = null
        singBoxEngine = null
        try { vpnOutputStream?.close() } catch (e: Exception) { Log.w(TAG, "Stream cleanup failed", e) }
        synchronized(tunnelLock) {
            try { vpnInterface?.close() } catch (e: Exception) { Log.w(TAG, "TUN cleanup failed", e) }
            vpnInterface = null
        }
        vpnOutputStream = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { Log.w(TAG, "Wake lock cleanup failed", e) }
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (e: Exception) { Log.w(TAG, "Wi-Fi lock cleanup failed", e) }
        wakeLock = null
        wifiLock = null
        _privateIp.value = ""
        _serverIp.value = ""
        currentPublicIp = ""
    }
    companion object {
        private const val TAG = "VpnConnectionService"
        
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning
        
        private val _privateIp = MutableStateFlow("")
        val privateIp: StateFlow<String> = _privateIp
        
        private val _serverIp = MutableStateFlow("")
        val serverIp: StateFlow<String> = _serverIp
        
        // Traffic Stats
        val rxBytes = java.util.concurrent.atomic.AtomicLong(0)
        val txBytes = java.util.concurrent.atomic.AtomicLong(0)
        
        const val ACTION_CONNECT = "com.vpnapp.CONNECT"
        const val ACTION_DISCONNECT = "com.vpnapp.DISCONNECT"
        const val ACTION_VPN_STATUS = "com.vpnapp.VPN_STATUS"
        const val ACTION_VPN_STATS = "com.vpnapp.VPN_STATS" // New stats broadcast
        const val ACTION_PROTECTION_STATE = "com.vpnapp.PROTECTION_STATE"
        const val ACTION_LAN_SHARE_CONFIG = "com.vpnapp.LAN_SHARE_CONFIG"
        const val ACTION_LAN_SHARE_STATUS = "com.vpnapp.LAN_SHARE_STATUS"
        const val ACTION_LAN_SHARE_STATUS_REQUEST = "com.vpnapp.LAN_SHARE_STATUS_REQUEST"
        const val ACTION_REQUEST_STATE = "com.vpnapp.REQUEST_STATE"
        const val ACTION_UI_VISIBILITY = "com.vpnapp.UI_VISIBILITY"
        const val EXTRA_UI_VISIBLE = "ui_visible"
        const val ACTION_UPDATE_ROUTING = "com.vpnapp.UPDATE_ROUTING"
        const val ACTION_RESTORE_NOTIFICATION = "com.vpnapp.RESTORE_NOTIFICATION"
        
        const val EXTRA_STATUS = "status"
        const val EXTRA_PRIVATE_IP = "private_ip"
        const val EXTRA_SERVER_IP = "server_ip" 
        
        const val EXTRA_RX_BYTES = "rx_bytes"
        const val EXTRA_TX_BYTES = "tx_bytes"
        const val EXTRA_MTU = "mtu"
        const val EXTRA_TRANSPORT = "transport"
        const val EXTRA_BUFFER_SIZE = "buffer_size"
        const val EXTRA_TCP_NODELAY = "tcp_nodelay"
        const val EXTRA_USE_NATIVE = "use_native"
        const val EXTRA_EXCLUDED_APPS = "excluded_apps"
        
        const val STATUS_CONNECTED = "CONNECTED"
        const val STATUS_DISCONNECTED = "DISCONNECTED"
        const val STATUS_ERROR = "ERROR"
        const val STATUS_CONNECTING = "CONNECTING"
        
        const val EXTRA_SERVER_IP_INPUT = "server_ip" 
        const val EXTRA_SERVER_PORT = "server_port"
        const val EXTRA_PROTOCOL = "protocol"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_SKIP_CERT_VERIFY = "skip_cert_verify"
        const val EXTRA_PROFILE_NAME = "profile_name"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_DISABLE_DTLS = "disable_dtls"
        const val EXTRA_CONNECTION_TIME = "connection_time"
        const val EXTRA_PUBLIC_IP = "public_ip"
        
        private const val NOTIFICATION_CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_ID = 1
        
        // New Action for GeoIP Updates
        const val ACTION_UPDATE_GEO_INFO = "com.vpnapp.UPDATE_GEO_INFO"
        const val EXTRA_COUNTRY_CODE = "country_code"
        
        // V2Ray Extras
        const val EXTRA_V2RAY_UUID = "v2ray_uuid"
        const val EXTRA_V2RAY_ALTERID = "v2ray_alterid"
        const val EXTRA_V2RAY_SECURITY = "v2ray_security"
        const val EXTRA_VLESS_ENCRYPTION = "vless_encryption"
        const val EXTRA_V2RAY_NETWORK = "v2ray_network"
        const val EXTRA_V2RAY_TYPE = "v2ray_type"
        const val EXTRA_V2RAY_HOST = "v2ray_host"
        const val EXTRA_V2RAY_PATH = "v2ray_path"
        const val EXTRA_V2RAY_TLS = "v2ray_tls"
        const val EXTRA_V2RAY_SNI = "v2ray_sni"
        const val EXTRA_V2RAY_ALPN = "v2ray_alpn"
        const val EXTRA_V2RAY_FP = "v2ray_fp"
        const val EXTRA_V2RAY_FLOW = "v2ray_flow"
        const val EXTRA_V2RAY_PBK = "v2ray_pbk"
        const val EXTRA_V2RAY_SHORT_ID = "v2ray_sid"
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize FileLogger
        FileLogger.init(applicationContext)
        FileLogger.write("Service onCreate")
        
        // Set global exception handler for this service process
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            FileLogger.logException(e)
            defaultHandler?.uncaughtException(t, e)
        }
        
        createNotificationChannel()
        val processName = if (Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
            else (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                .runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
        workerProcessVerified = processName == "$packageName:vpn"
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.hasExtra(EXTRA_UI_VISIBLE) == true) uiVisible.value = intent.getBooleanExtra(EXTRA_UI_VISIBLE, false)
        sendBroadcast(Intent(ACTION_PROTECTION_STATE).setPackage(packageName)
            .putExtra("always_on", Build.VERSION.SDK_INT >= 29 && isAlwaysOn)
            .putExtra("lockdown", Build.VERSION.SDK_INT >= 29 && isLockdownEnabled))
        if (intent == null || intent.action == SERVICE_INTERFACE) {
            systemManaged=true
            val restored=LastVpnConnection.restore(this)
            if(restored!=null) return onStartCommand(restored,flags,startId)
            // Android's lockdown remains active. Ask for setup rather than connecting a guessed profile.
            val notice=createNotification("SecureVPN","","Open app and connect a profile")
            if(Build.VERSION.SDK_INT>=34) startForeground(NOTIFICATION_ID,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID,notice)
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_CONNECT -> {
                runtimeDns=com.vpnapp.vpn.singbox.TunnelDnsSettings.read(intent)
                runtimeLan=LanShareSettings.fromIntent(intent)
                // CRITICAL: Guard against double-tap at the EARLIEST point
                if (isConnecting.get() || _isRunning.value || isDisconnecting.get()) {
                    Log.w(TAG, "Connection already in progress or running. Ignoring duplicate ACTION_CONNECT.")
                    return START_NOT_STICKY
                }
                isConnecting.set(true)
                runCatching { LastVpnConnection.save(this,intent) }
                currentProfileId = intent.getStringExtra(EXTRA_PROFILE_ID)
                notificationSession = android.os.SystemClock.elapsedRealtimeNanos()
                
                val serverIp = intent.getStringExtra(EXTRA_SERVER_IP_INPUT) ?: run {
                    isConnecting.set(false)
                    return START_NOT_STICKY
                }
                val serverPort = intent.getIntExtra(EXTRA_SERVER_PORT, 443)
                val protocolName = intent.getStringExtra(EXTRA_PROTOCOL) ?: VpnProtocol.SSTP.name
                val username = intent.getStringExtra(EXTRA_USERNAME) ?: ""
                val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
                val skipCertVerify = intent.getBooleanExtra(EXTRA_SKIP_CERT_VERIFY, true)
                
                currentProtocol = try {
                    VpnProtocol.valueOf(protocolName)
                } catch (e: Exception) {
                    VpnProtocol.SSTP
                }
                
                val prefs = applicationContext.getSharedPreferences("vpn_app_prefs", Context.MODE_PRIVATE)
                val bypassIran = intent.getBooleanExtra("bypass_iran", prefs.getBoolean("bypass_iran", true))
                val bypassPrivateIps = intent.getBooleanExtra("bypass_private_ips", prefs.getBoolean("bypass_private_ips", true))
                currentBypassIran = bypassIran
                currentBypassPrivateIps = bypassPrivateIps

                // Extract V2Ray params if applicable
                if (currentProtocol in listOf(VpnProtocol.VLESS, VpnProtocol.VMESS, VpnProtocol.TROJAN, VpnProtocol.SHADOWSOCKS, VpnProtocol.HYSTERIA2)) {
                    val v2rayAlpn = intent.getStringExtra(EXTRA_V2RAY_ALPN) ?: ""
                    val v2rayFp = intent.getStringExtra(EXTRA_V2RAY_FP) ?: ""
                    val v2rayFlow = intent.getStringExtra(EXTRA_V2RAY_FLOW) ?: ""
                    val v2rayPbk = intent.getStringExtra(EXTRA_V2RAY_PBK) ?: ""
                    val v2raySid = intent.getStringExtra(EXTRA_V2RAY_SHORT_ID) ?: ""

                    v2rayProfile = VpnProfile(
                        id = currentProfileId ?: java.util.UUID.randomUUID().toString(),
                        name = intent.getStringExtra(EXTRA_PROFILE_NAME) ?: "V2Ray",
                        serverAddress = serverIp,
                        port = serverPort,
                        protocol = currentProtocol!!,
                        username = username,
                        password = password,
                        skipCertificateVerification = skipCertVerify,
                        v2rayUuid = intent.getStringExtra(EXTRA_V2RAY_UUID) ?: "",
                        v2rayAlterId = intent.getIntExtra(EXTRA_V2RAY_ALTERID, 0),
                        v2raySecurity = intent.getStringExtra(EXTRA_V2RAY_SECURITY) ?: "auto",
                        vlessEncryption = intent.getStringExtra(EXTRA_VLESS_ENCRYPTION) ?: "none",
                        v2rayNetwork = intent.getStringExtra(EXTRA_V2RAY_NETWORK) ?: "tcp",
                        v2rayType = intent.getStringExtra(EXTRA_V2RAY_TYPE) ?: "none",
                        v2rayXhttpExtra = intent.getStringExtra("v2ray_xhttp_extra") ?: "",
                        v2rayHost = intent.getStringExtra(EXTRA_V2RAY_HOST) ?: "",
                        v2rayPath = intent.getStringExtra(EXTRA_V2RAY_PATH) ?: "",
                        v2rayTls = intent.getStringExtra(EXTRA_V2RAY_TLS) ?: "none",
                        v2raySni = intent.getStringExtra(EXTRA_V2RAY_SNI) ?: "",
                        v2rayAlpn = v2rayAlpn,
                        v2rayFp = v2rayFp,
                        v2rayFlow = v2rayFlow,
                        v2rayPbk = v2rayPbk,
                        v2rayShortId = v2raySid,
                        v2rayEch = intent.getStringExtra("v2ray_ech") ?: "",
                        hysteriaObfsMinPacketSize = intent.getIntExtra("hysteria_obfs_min", 512),
                        hysteriaObfsMaxPacketSize = intent.getIntExtra("hysteria_obfs_max", 1200),
                        hysteriaObfs = intent.getStringExtra("hysteria_obfs") ?: "none",
                        hysteriaObfsPassword = intent.getStringExtra("hysteria_obfs_password") ?: "",
                        mtu = intent.getIntExtra(EXTRA_MTU, 1400),
                        bypassIranRoutes = bypassIran,
                        bypassPrivateIps = bypassPrivateIps
                    )
                    Log.d(TAG, "V2Ray profile: Proto=$currentProtocol, BypassIran=$bypassIran, BypassPrivateIps=$bypassPrivateIps")
                }
                
                val bufferSize = intent.getIntExtra(EXTRA_BUFFER_SIZE, 2 * 1024 * 1024)
                val tcpNoDelay = intent.getBooleanExtra(EXTRA_TCP_NODELAY, true)
                val disableDtls = intent.getBooleanExtra(EXTRA_DISABLE_DTLS, false)
                val mtu = intent.getIntExtra(EXTRA_MTU, 1400)
                val useNative = intent.getBooleanExtra(EXTRA_USE_NATIVE, true)
                
                currentExcludedApps = intent.getStringArrayListExtra(EXTRA_EXCLUDED_APPS) ?: emptyList()
                val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME) ?: "SecureVPN"
                
                Log.d(TAG, "Split Tunneling: ${currentExcludedApps.size} apps excluded")
                FileLogger.write("ACTION_CONNECT received. Starting connection...")
                
                connect(profileName, serverIp, serverPort, username, password, skipCertVerify, bufferSize, tcpNoDelay, disableDtls, mtu, useNative)
            }
            ACTION_LAN_SHARE_CONFIG -> {
                runtimeLan=LanShareSettings.fromIntent(intent) ?: runtimeLan
                serviceScope?.launch { syncLanProxy() }
                if(!_isRunning.value && !isConnecting.get()) stopSelf(startId)
            }
            ACTION_UI_VISIBILITY -> {
                if (!_isRunning.value && !isConnecting.get()) stopSelf(startId)
            }
            ACTION_LAN_SHARE_STATUS_REQUEST -> {
                // Read-only status request must never reconnect or keep an idle worker alive.
                if(_isRunning.value) { syncLanProxy(); publishLanStatus(force = true) } else {
                    publishLanStatus(force = true)
                    if(!isConnecting.get()) stopSelf(startId)
                }
            }
            ACTION_DISCONNECT -> {
                disconnect()
            }
            ACTION_RESTORE_NOTIFICATION -> {
                // Android 14+ permits dismissing ongoing notifications on unlocked devices.
                // Restore only this live session; never connect or resurrect a stopped service.
                if (VpnNotificationPolicy.shouldRestore(intent.getLongExtra("notification_session", -1),
                    notificationSession, _isRunning.value, isConnecting.get(),
                    serviceDestroyed || isDisconnecting.get() || isCancellationRequested.get())) {
                    restoreMissingNotification(intent.getLongExtra("notification_session", -1))
                } else if (!_isRunning.value && !isConnecting.get() && !isDisconnecting.get()) {
                    stopSelf(startId)
                }
            }
            ACTION_UPDATE_ROUTING -> {
                runtimeDns=com.vpnapp.vpn.singbox.TunnelDnsSettings.read(intent) ?: runtimeDns
                serviceScope?.launch {
                    // Reload operations run serially with engine start/stop.
                    synchronized(routingLock) {
                        if (isDisconnecting.get() || isCancellationRequested.get()) return@synchronized
                        val prefs = getSharedPreferences("vpn_app_prefs", Context.MODE_PRIVATE)
                        currentBypassIran = intent.getBooleanExtra("bypass_iran", currentBypassIran)
                        currentBypassPrivateIps = intent.getBooleanExtra("bypass_private_ips", currentBypassPrivateIps)
                        val profile = v2rayProfile ?: return@synchronized
                        val updated = profile.copy(bypassIranRoutes = currentBypassIran, bypassPrivateIps = currentBypassPrivateIps)
                        v2rayProfile = updated
                        try {
                            if (singBoxEngine?.isRunning() == true) {
                                singBoxEngine?.runtimeDns=runtimeDns
                                singBoxEngine?.reloadRouting(updated)
                            }
                        } catch (e: Exception) {
                            sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                                putExtra(EXTRA_STATUS, STATUS_ERROR)
                                putExtra("message", "Could not apply routing settings: ${e.message}")
                            })
                            disconnect()
                        }
                    }
                }
            }
            ACTION_REQUEST_STATE -> {
                // Respond with current VPN state (for UI sync when app resumes)
                if (isDisconnecting.get()) {
                    sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                        putExtra(EXTRA_STATUS, "DISCONNECTING")
                    })
                } else if (isConnecting.get()) {
                    sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                        putExtra(EXTRA_STATUS, "CONNECTING")
                    })
                } else if (_isRunning.value) {
                    sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                        putExtra(EXTRA_STATUS, STATUS_CONNECTED)
                        putExtra(EXTRA_PROFILE_ID, currentProfileId)
                        putExtra(EXTRA_PROFILE_NAME, currentProfileName)
                        putExtra(EXTRA_PROTOCOL, currentProtocol.name)
                        putExtra(EXTRA_PRIVATE_IP, _privateIp.value)
                        putExtra(EXTRA_SERVER_IP, _serverIp.value)
                        putExtra(EXTRA_PROFILE_NAME, currentProfileName)
                        putExtra(EXTRA_MTU, currentMtu)
                        com.vpnapp.vpn.singbox.ProbeEndpoint.active?.writeTo(this)
                        // Send persisted GeoIP data
                        putExtra(EXTRA_PUBLIC_IP, currentPublicIp)
                        putExtra(EXTRA_COUNTRY_CODE, currentCountryCode)
                    })
                    // Also send current stats
                    val totals = counters()
                    sendBroadcast(Intent(ACTION_VPN_STATS).setPackage(packageName).apply {
                        putExtra(EXTRA_RX_BYTES, totals.first)
                        putExtra(EXTRA_TX_BYTES, totals.second)
                        putExtra(EXTRA_TRANSPORT, nativeOpenConnectEngine?.getActiveTransport() ?: "TCP")
                        putExtra(EXTRA_CONNECTION_TIME, sessionClock.durationSeconds())
                    })
                } else {
                    sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                        putExtra(EXTRA_STATUS, STATUS_DISCONNECTED)
                    })
                }
            }
            ACTION_UPDATE_GEO_INFO -> {
                if (isDisconnecting.get() || isCancellationRequested.get() || !_isRunning.value) return START_NOT_STICKY
                val ip = intent.getStringExtra(EXTRA_PUBLIC_IP)
                val country = intent.getStringExtra(EXTRA_COUNTRY_CODE)
                if (ip != null) currentPublicIp = ip
                if (country != null) currentCountryCode = country
                
                // Update Notification
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.notify(NOTIFICATION_ID, createNotification(currentProfileName, currentPublicIp, "Connected", currentCountryCode))
                
                Log.d(TAG, "GeoInfo updated in Service: IP=$currentPublicIp, Country=$currentCountryCode")
            }
        }
        return if (systemManaged || (Build.VERSION.SDK_INT >= 29 && isAlwaysOn)) START_STICKY else START_NOT_STICKY
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VPN Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when VPN is connected"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(profileName: String, serverIp: String, status: String = "Connected", countryCode: String? = null): Notification =
        VpnNotificationFactory.create(this, NOTIFICATION_CHANNEL_ID, profileName, serverIp, status, countryCode, notificationSession)

    private var currentProfileName: String = "SecureVPN"
    private var currentProfileId: String? = null
    private var notificationSession = 0L

    // Some devices drop an ongoing notice without delivering its delete PendingIntent.
    // All protocols share this watchdog; only a live, permitted session may post again.
    private fun startNotificationMonitor() {
        notificationMonitor?.cancel()
        val session = notificationSession
        notificationMonitor = serviceScope?.launch {
            while (isActive) {
                delay(15_000)
                runCatching { restoreMissingNotification(session) }.onFailure { Log.w(TAG,"Notification watchdog retry",it) }
            }
        }
    }

    private fun restoreMissingNotification(session: Long) = synchronized(notificationLock) {
        if (!VpnNotificationPolicy.shouldRestore(session, notificationSession,
                _isRunning.value, isConnecting.get(),
                serviceDestroyed || isDisconnecting.get() || isCancellationRequested.get())) return@synchronized
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return@synchronized
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE)
            return@synchronized
        if (manager.activeNotifications.any { it.id == NOTIFICATION_ID &&
                it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 && it.isOngoing }) return@synchronized
        try {
            val notice=createNotification(currentProfileName,currentPublicIp,
                if (_isRunning.value) "Connected" else "Connecting",currentCountryCode)
            if(Build.VERSION.SDK_INT>=34) startForeground(NOTIFICATION_ID,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID,notice)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not restore ongoing VPN notification", error)
        }
    }

    private var connectionTimeoutJob: Job? = null
    
    private fun connect(profileName: String, serverIp: String, serverPort: Int, username: String, password: String, skipCertVerify: Boolean, bufferSize: Int, tcpNoDelay: Boolean, disableDtls: Boolean = false, mtu: Int = 1200, useNative: Boolean = true) {
        usageSession=java.util.UUID.randomUUID().toString()
        rxBytes.set(0);txBytes.set(0)
        currentMtu = mtu
        currentProfileName = profileName
        currentPublicIp = serverIp // Use server IP as public IP until we get actual gateway
        currentCountryCode = null
        
        // Reset cancellation flag at start of new connection attempt
        isCancellationRequested.set(false)
        
        // Start 60-second connection timeout (increased from 15s for debugging)
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = serviceScope?.launch {
            delay(60_000)
            if (isConnecting.get() && !_isRunning.value && !isCancellationRequested.get()) {
                Log.w(TAG, "Connection timeout (60s) - forcing disconnect")
                FileLogger.write("Connection timeout (60s) - forcing disconnect")
                sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                    putExtra(EXTRA_STATUS, STATUS_ERROR)
                    putExtra("message", "Connection timeout - server not responding")
                })
                disconnect()
            }
        }
        
        // Acquire WakeLock to prevent disconnection when screen is off
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SecureVPN::VpnWakeLock"
            )
            wakeLock?.acquire()
            val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)
            Log.d(TAG, "WakeLock acquired: ${wakeLock?.isHeld}, Battery Optimization Ignored: $isIgnoring")
            FileLogger.write("WakeLock acquired: ${wakeLock?.isHeld}, Battery Optimization Ignored: $isIgnoring")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
        
        // Acquire WiFiLock for stable WiFi connection
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SecureVPN::VpnWifiLock")
            wifiLock?.acquire()
            Log.d(TAG, "WiFiLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WiFiLock", e)
        }
        
        // Start foreground service first
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                 startForeground(NOTIFICATION_ID, createNotification(profileName, serverIp, "Connecting..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                 startForeground(NOTIFICATION_ID, createNotification(profileName, serverIp, "Connecting..."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            disconnect()
            return 
        }
        
        startNotificationMonitor()
        startSessionMonitor()

        // Initialize connection timer (will be set to current time when connected)
        connectionStartTime = 0L
        sessionClock.clear()
        
        serviceScope?.launch {
            try {
                when (currentProtocol) {
                    VpnProtocol.SSTP -> connectSstp(serverIp, serverPort, username, password, skipCertVerify, bufferSize, tcpNoDelay)
                    VpnProtocol.OPENCONNECT -> connectOpenConnect(serverIp, serverPort, username, password, skipCertVerify, bufferSize, tcpNoDelay, disableDtls, mtu, useNative)
                    VpnProtocol.VLESS, VpnProtocol.VMESS, VpnProtocol.TROJAN, VpnProtocol.SHADOWSOCKS, VpnProtocol.HYSTERIA2 -> {
                        if (v2rayProfile != null) {
                            connectV2Ray(v2rayProfile!!)
                        } else {
                            Log.e(TAG, "V2Ray Profile missing")
                            disconnect()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                disconnect()
            }
        }
    }
    
    private fun connectSstp(serverIp: String, serverPort: Int, username: String, password: String, skipCertVerify: Boolean, bufferSize: Int, tcpNoDelay: Boolean) {
        Log.d(TAG, "Starting SSTP connection to $serverIp:$serverPort with buffer ${bufferSize/1024}KB, tcpNoDelay=$tcpNoDelay")
        
        // Broadcast Connecting
        sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
            putExtra(EXTRA_STATUS, STATUS_CONNECTING)
        })
        
        sstpEngine = SstpEngine(
            serverAddress = serverIp,
            serverPort = serverPort,
            username = username,
            password = password,
            skipCertificateVerification = skipCertVerify,
            bufferSize = bufferSize,
            tcpNoDelay = tcpNoDelay,
            requestedMtu = currentMtu
        )
        
        val callback = object : SstpEngine.SstpCallback {
            override fun onLog(message: String) {
                Log.d(TAG, "SSTP: $message")
                // Broadcast LOG to UI
                sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                    putExtra(EXTRA_STATUS, "LOG")
                    putExtra("message", message)
                })
            }
            
            override fun onError(message: String) {
                Log.e(TAG, "SSTP Error: $message")
                sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                    putExtra(EXTRA_STATUS, STATUS_ERROR)
                    putExtra("message", message)
                })
                disconnect()
            }
            
            override fun onConnected(privateIp: String, serverIpAddr: String, dnsServers: List<String>) {
                Log.d(TAG, "SSTP Connected! Private IP: $privateIp, Server: $serverIpAddr")
                val effectiveMtu = sstpEngine?.tunnelMtu ?: currentMtu
                currentMtu = effectiveMtu
                
                // CRITICAL: Cancel the connection timeout immediately
                connectionTimeoutJob?.cancel()
                connectionTimeoutJob = null
                isConnecting.set(false)
                
                _privateIp.value = privateIp
                _serverIp.value = serverIpAddr
                
                // Broadcast Connected
                sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                    putExtra(EXTRA_STATUS, STATUS_CONNECTED)
                        putExtra(EXTRA_PROFILE_ID, currentProfileId)
                        putExtra(EXTRA_PROFILE_NAME, currentProfileName)
                        putExtra(EXTRA_PROTOCOL, currentProtocol.name)
                    putExtra(EXTRA_PRIVATE_IP, privateIp)
                    putExtra(EXTRA_SERVER_IP, serverIpAddr)
                    putExtra(EXTRA_MTU, effectiveMtu)
                })
                
                // Establish VPN Interface
                sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                    putExtra(EXTRA_STATUS, "LOG")
                    putExtra("message", "TRACE: Launching establishVpnInterface coroutine...")
                })
                
                serviceScope?.launch {
                    sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                        putExtra(EXTRA_STATUS, "LOG")
                        putExtra("message", "TRACE: Coroutine started. Calling establishVpnInterface...")
                    })
                    establishVpnInterface(privateIp, serverIpAddr, dnsServers, effectiveMtu)
                }
            }
            
            override fun onDataPacket(data: ByteArray) {
                // Write received data to VPN interface
                try {
                    rxBytes.addAndGet(data.size.toLong())
                    val fd = vpnInterface?.fileDescriptor
                    if (fd != null && fd.valid()) {
                        FileOutputStream(fd).write(data)
                    }
                } catch (e: Exception) {
                    if (_isRunning.value) Log.e(TAG, "Error writing to VPN", e)
                }
            }
            
            override fun onDisconnected() {
                Log.d(TAG, "SSTP Disconnected")
                sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                    putExtra(EXTRA_STATUS, STATUS_DISCONNECTED)
                })
                sstpEngine = null // Prevent recursion
                disconnect()
            }
        }
        
        // Connect blocks, so launch it
        serviceScope?.launch {
            sstpEngine?.connect(callback) { socket ->
                 // Critical: Protect the socket from VPN routing to avoid loops
                 val protected = protectSocket(socket)
                 if (!protected) {
                     Log.w(TAG, "Failed to protect SSTP socket!")
                     FileLogger.write("CRITICAL: Failed to protect SSTP socket! Routing loop imminent.")
                 }
                 protected
            }
        }
    }
    private var currentExcludedApps: List<String> = emptyList()
    private var currentBypassIran: Boolean = false
    private var currentBypassPrivateIps: Boolean = true
    
    private suspend fun establishVpnInterface(privateIp: String, serverAddr: String, dnsServers: List<String>, mtu: Int = 1400) {
        // Broadcast that we are establishing interface
        sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
            putExtra(EXTRA_STATUS, "LOG")
            putExtra("message", "Establishing VPN Interface...")
        })
        try {
            val builder = Builder()
                .setSession("SecureVPN - $serverAddr")
                .addAddress(privateIp, 24)
                .addRoute("0.0.0.0", 0)
                .setMtu(mtu) // Use server's MTU directly
                .setBlocking(true) // Blocking mode forces read() to wait for data (0 latnecy)
            
            // Add DNS servers from server
            dnsServers.forEach { dns ->
                try {
                    builder.addDnsServer(dns)
                    Log.d(TAG, "Added DNS: $dns")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add DNS $dns: ${e.message}")
                }
            }
            
            // ALWAYS add public DNS as fallback to ensure name resolution works
            try {
                builder.addDnsServer("8.8.8.8")
                builder.addDnsServer("1.1.1.1")
                Log.d(TAG, "Added fallback DNS: 8.8.8.8, 1.1.1.1")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add fallback DNS: ${e.message}")
            }
            
            // NOTE: We no longer exclude our own app from VPN.
            // VPN control sockets are protected via protectSocket() and protect(fd),
            // so routing loops are prevented. This allows GeoIP queries to go through
            // the VPN tunnel for accurate public IP detection.
            
            // Split Tunneling: Exclude user-selected apps
            currentExcludedApps.forEach { appPackage ->
                try {
                    builder.addDisallowedApplication(appPackage)
                    Log.d(TAG, "Excluded app from VPN: $appPackage")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not exclude app: $appPackage")
                }
            }

            // Exclude private IP ranges (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16) on Android 13+ if the LAN switch is enabled
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && currentBypassPrivateIps) {
                listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16").forEach { route ->
                    try {
                        val parts = route.split("/")
                        val ipPrefix = android.net.IpPrefix(java.net.InetAddress.getByName(parts[0]), parts[1].toInt())
                        builder.excludeRoute(ipPrefix)
                        Log.d(TAG, "Excluded private route: $route")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to exclude private route $route: ${e.message}")
                    }
                }
            }
            
            FileLogger.write("Builder configured. Calling establish()...")
            sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                putExtra(EXTRA_STATUS, "LOG")
                putExtra("message", "TRACE: Calling builder.establish()...")
            })
            
            vpnInterface = builder.establish()
            
            FileLogger.write("builder.establish() returned: $vpnInterface")
            sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                putExtra(EXTRA_STATUS, "LOG")
                putExtra("message", "TRACE: builder.establish() returned.")
            })
            
            if (vpnInterface != null) {
                _isRunning.value = true
                markConnectionStarted()
                recordSuccessfulAdCycleSession()
                
                // Initialize cached output stream for writing received packets to VPN
                vpnOutputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
                
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.notify(NOTIFICATION_ID, createNotification(currentProfileName, serverAddr, "Connected"))
                Log.i(TAG, "VPN interface established. Private IP: $privateIp, MTU: $mtu")
                FileLogger.write("VPN interface established successfully.") // ADDED
                
                // Start Stats Broadcaster for ALL protocols
                startStatsBroadcaster()
                
                
                // Start data receive loop (if using Java engine)
                openConnectEngine?.let { engine ->
                    // Only start packet forwarding (Java-side TUN read) if NOT using native engine
                    // Native engine reads from TUN directly via JNI
                    Log.d(TAG, "Starting Java-based packet forwarding...")
                    startPacketForwarding()
                    
                    serviceScope?.launch(Dispatchers.IO) {
                        Log.d(TAG, "Starting OpenConnect data receive loop...")
                        engine.startDataLoop()
                    }
                }
                
                // For Native engine, we pass the FD directly
                nativeOpenConnectEngine?.let { engine ->
                    Log.d(TAG, "Starting Native engine tunnel...")
                    val fd = vpnInterface?.fd ?: -1
                    FileLogger.write("Starting Native engine tunnel with FD: $fd")
                    if (fd != -1) {
                        engine.startTunnel(fd)
                    } else {
                        Log.e(TAG, "Invalid FD for native engine")
                        FileLogger.write("Invalid FD for native engine")
                    }
                }
                
                // CRITICAL: Start packet forwarding for SSTP (reads from TUN and sends to server)
                sstpEngine?.let { engine ->
                    Log.d(TAG, "Starting SSTP packet forwarding...")
                    FileLogger.write("Starting SSTP packet forwarding...")
                    startPacketForwarding()
                }
            } else {
                Log.e(TAG, "Failed to establish VPN interface (result was null)")
                FileLogger.write("Failed to establish VPN interface (result was null)")
                sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                    putExtra(EXTRA_STATUS, STATUS_ERROR)
                    putExtra("message", "Interface creation failed (null)")
                })
                disconnect()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error establishing VPN interface", t)
            FileLogger.write("Error establishing VPN interface: ${t.message}")
            FileLogger.logException(t)
            sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                putExtra(EXTRA_STATUS, STATUS_ERROR)
                putExtra("message", "Interface Error: ${t.message}")
            })
            disconnect()
        }
    }
    

    
    private suspend fun connectOpenConnect(serverIp: String, serverPort: Int, username: String, password: String, skipCertVerify: Boolean, bufferSize: Int, tcpNoDelay: Boolean, disableDtls: Boolean = false, mtu: Int = 1200, useNative: Boolean = true) {
        // Guard is now in onStartCommand. Just log entry.
        Log.d(TAG, "Starting OpenConnect connection to $serverIp:$serverPort (Native: $useNative) with MTU $mtu")
        FileLogger.write("connectOpenConnect() entered")
        
        try {
            // Broadcast Connecting
            sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                putExtra(EXTRA_STATUS, STATUS_CONNECTING)
            })
            
            this.useNativeEngine = useNative

            // Check protocol and route accordingly

            if (useNative) {
                val profile = VpnProfile(id=currentProfileId ?: "openconnect", name=currentProfileName,
                    serverAddress=serverIp, port=serverPort, protocol=VpnProtocol.OPENCONNECT,
                    username=username, password=password, skipCertificateVerification=skipCertVerify,
                    disableDtls=disableDtls, mtu=mtu, bypassIranRoutes=currentBypassIran,
                    bypassPrivateIps=currentBypassPrivateIps)
                v2rayProfile=profile
                connectV2Ray(profile)

            } else {
                // Legacy Java Engine
                val engine = com.vpnapp.vpn.openconnect.OpenConnectEngine(
                    serverAddress = serverIp,
                    serverPort = serverPort,
                    username = username,
                    password = password,
                    skipCertificateVerification = skipCertVerify,
                    bufferSize = bufferSize,
                    tcpNoDelay = tcpNoDelay,
                    disableDtls = disableDtls,
                    requestedMtu = mtu
                )
                this.openConnectEngine = engine
                
                val callback = object : com.vpnapp.vpn.openconnect.OpenConnectEngine.OpenConnectCallback {
                    override fun onLog(message: String) {
                        Log.d(TAG, "OpenConnect: $message")
                        sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                            putExtra(EXTRA_STATUS, "LOG")
                            putExtra("message", message)
                        })
                    }
                    
                    override fun onError(message: String) {
                        Log.e(TAG, "OpenConnect Error: $message")
                        sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                            putExtra(EXTRA_STATUS, STATUS_ERROR)
                            putExtra("message", message)
                        })
                        disconnect()
                    }
                    
                    override fun onConnected(privateIp: String, serverIpAddr: String, dnsServers: List<String>, mtu: Int, splitExcludes: List<String>, splitIncludes: List<String>) {
                        Log.d(TAG, "OpenConnect Connected. IP: $privateIp, MTU: $mtu")
                        _privateIp.value = privateIp
                        _serverIp.value = serverIpAddr
                        
                        sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                            putExtra(EXTRA_STATUS, STATUS_CONNECTED)
                        putExtra(EXTRA_PROFILE_ID, currentProfileId)
                        putExtra(EXTRA_PROFILE_NAME, currentProfileName)
                        putExtra(EXTRA_PROTOCOL, currentProtocol.name)
                            putExtra(EXTRA_PRIVATE_IP, privateIp)
                            putExtra(EXTRA_SERVER_IP, serverIpAddr)
                            putExtra(EXTRA_MTU, mtu)
                        })
                        
                        // Note: This non-native OpenConnect engine doesn't support split routing yet
                        serviceScope?.launch {
                             establishVpnInterface(privateIp, serverIpAddr, dnsServers, mtu)
                        }
                    }
                    
                    override fun onDataPacket(data: ByteArray) {
                        try {
                            rxBytes.addAndGet(data.size.toLong())
                            vpnOutputStream?.write(data)
                        } catch (e: Exception) {
                            if (_isRunning.value) Log.e(TAG, "Error writing to VPN interface", e)
                        }
                    }
                    
                    override fun onDisconnected() {
                        Log.d(TAG, "OpenConnect Disconnected")
                        sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                            putExtra(EXTRA_STATUS, STATUS_DISCONNECTED)
                        })
                        openConnectEngine = null
                        disconnect()
                    }
                }
                
                engine.connect(callback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in connection flow", e)
            disconnect()
        } finally {
            isConnecting.set(false)
        }
    }
    
    private suspend fun connectSoftEther(serverIp: String, serverPort: Int, username: String, password: String, bufferSize: Int = 2 * 1024 * 1024, tcpNoDelay: Boolean = true) {
        // SoftEther implementation
        // For now, fallback to SSTP (SoftEther server supports SSTP)
        Log.w(TAG, "SoftEther native not yet implemented, using SSTP")
        connectSstp(serverIp, serverPort, username, password, true, bufferSize, tcpNoDelay)
    }
    

    
    private fun protectSocket(socket: java.net.Socket): Boolean {
        // 1. Try standard VpnService.protect()
        if (protect(socket)) {
            Log.d(TAG, "Socket protected via VpnService.protect()")
            return true
        }
        
        // 2. Fallback: Bind to active network (Android M+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            val activeNetwork = cm.activeNetwork
            if (activeNetwork != null) {
                try {
                    activeNetwork.bindSocket(socket)
                    Log.d(TAG, "Socket protected via Network.bindSocket($activeNetwork)")
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to bind socket to network", e)
                }
            } else {
                 Log.w(TAG, "No active network to bind socket to.")
            }
        }
        
        return false
    }

    override fun onDestroy() {
        serviceDestroyed = true
        isCancellationRequested.set(true)
        isDisconnecting.set(true)
        serviceScope?.cancel()
        // Never perform a potentially blocking native close on Android's main thread.
        beginShutdown()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching { restoreMissingNotification(notificationSession) }
        super.onTaskRemoved(rootIntent)
    }
    override fun onRevoke() { disconnect() }



    // Helper methods for V2Ray
    private fun updateState(state: ConnectionState, message: String? = null) {
        val statusString = when(state) {
            ConnectionState.CONNECTING -> "Connecting"
            ConnectionState.CONNECTED -> "Connected"
            ConnectionState.DISCONNECTED -> "Disconnected"
            ConnectionState.ERROR -> "Error"
            else -> state.name
        }
        val intent = Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
            putExtra(EXTRA_STATUS, statusString)
            if (message != null) putExtra("message", message)
        }
        sendBroadcast(intent)
    }

    private fun log(level: LogLevel, message: String) {
        when(level) {
            LogLevel.INFO -> Log.i(TAG, "V2Ray: $message")
            LogLevel.ERROR -> Log.e(TAG, "V2Ray: $message")
            else -> Log.d(TAG, "V2Ray: $message")
        }
        FileLogger.write("V2Ray: $message")
    }

    // ============================================================================================
    // Sing-box Integration for V2Ray Protocols (VLESS, VMess, Trojan, Shadowsocks)
    // ============================================================================================

    private fun connectV2Ray(profile: VpnProfile) {
        serviceScope?.launch(Dispatchers.IO) {
            if (serviceDestroyed || isCancellationRequested.get() || isDisconnecting.get()) return@launch
            try {
                updateState(ConnectionState.CONNECTING)
                log(LogLevel.INFO, "Starting sing-box connection for ${profile.protocol.displayName}...")

                val engine = SingBoxEngine(applicationContext, object : SingBoxEngine.SingBoxCallback {
                    override fun onStarted() {
                        if (isCancellationRequested.get() || isDisconnecting.get()) return
                        _privateIp.value = "172.19.0.1"
                        _serverIp.value = profile.serverAddress
                        Log.i(TAG, "Sing-box started successfully")
                        FileLogger.write("Sing-box started successfully")
                        
                        markConnectionStarted()
                        _isRunning.value = true
                        recordSuccessfulAdCycleSession()
                        
                        // Cancel connection timeout
                        connectionTimeoutJob?.cancel()
                        connectionTimeoutJob = null
                        isConnecting.set(false)
                        
                        // Broadcast connected state
                        sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                            putExtra(EXTRA_STATUS, STATUS_CONNECTED)
                        putExtra(EXTRA_PROFILE_ID, currentProfileId)
                        putExtra(EXTRA_PROFILE_NAME, currentProfileName)
                        putExtra(EXTRA_PROTOCOL, currentProtocol.name)
                            putExtra(EXTRA_PRIVATE_IP, "172.19.0.1")
                            putExtra(EXTRA_SERVER_IP, profile.serverAddress)
                            putExtra(EXTRA_PROFILE_NAME, profile.name)
                            putExtra(EXTRA_MTU, currentMtu)
                            com.vpnapp.vpn.singbox.ProbeEndpoint.active?.writeTo(this)
                        })
                        
                        // Update notification
                        val notificationManager = getSystemService(NotificationManager::class.java)
                        notificationManager.notify(NOTIFICATION_ID, createNotification(
                            profile.name, 
                            profile.serverAddress, 
                            "Connected"
                        ))
                        
                        // Start stats broadcaster
                        startStatsBroadcaster()
                    }

                    override fun onStopped() {
                        Log.d(TAG, "Sing-box stopped")
                        FileLogger.write("Sing-box stopped")
                        if (!isDisconnecting.get()) disconnect()
                    }

                    override fun onError(message: String) {
                        if (isCancellationRequested.get() || isDisconnecting.get()) return
                        Log.e(TAG, "Sing-box error: $message")
                        FileLogger.write("Sing-box error: $message")
                        
                        sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                            putExtra(EXTRA_STATUS, STATUS_ERROR)
                            putExtra("message", message)
                        })
                        
                        updateState(ConnectionState.ERROR, message)
                        disconnect()
                    }

                    override fun onLog(message: String) {
                        Log.d(TAG, "Sing-box: $message")
                        FileLogger.write("Sing-box: $message")
                        
                        sendBroadcast(Intent(ACTION_VPN_STATUS).setPackage(packageName).apply {
                            putExtra(EXTRA_STATUS, "LOG")
                            putExtra("message", message)
                        })
                    }

                    override fun requestVpnInterface(
                        address: String,
                        prefix: Int,
                        dnsServers: List<String>,
                        mtu: Int
                    ): ParcelFileDescriptor? {
                        if (serviceDestroyed || isCancellationRequested.get() || isDisconnecting.get()) return null
                        if (isCancellationRequested.get() || isDisconnecting.get()) return null
                        currentMtu = mtu
                        Log.d(TAG, "Sing-box requesting VPN interface: $address/$prefix, MTU=$mtu")
                        FileLogger.write("Sing-box requesting VPN interface: $address/$prefix, MTU=$mtu")
                        
                        try {
                            val builder = Builder()
                                .setSession("RahaVPN - ${profile.serverAddress}")
                                .addAddress(address, prefix)
                                .addRoute("0.0.0.0", 0)
                                .setMtu(mtu)
                                .setBlocking(true)

                            // Add DNS servers
                            val effectiveDns = if (dnsServers.isNotEmpty()) dnsServers else listOf("8.8.8.8", "1.1.1.1")
                            effectiveDns.forEach { dns ->
                                try {
                                    builder.addDnsServer(dns)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to add DNS $dns: ${e.message}")
                                }
                            }

                            // Split Tunneling: Exclude user-selected apps
                            currentExcludedApps.forEach { appPackage ->
                                try {
                                    builder.addDisallowedApplication(appPackage)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Could not exclude app: $appPackage")
                                }
                            }

                            // Exclude private IP ranges (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16) on Android 13+ if the LAN switch is enabled
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && currentBypassPrivateIps) {
                                listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16").forEach { route ->
                                    try {
                                        val parts = route.split("/")
                                        val ipPrefix = android.net.IpPrefix(java.net.InetAddress.getByName(parts[0]), parts[1].toInt())
                                        builder.excludeRoute(ipPrefix)
                                        Log.d(TAG, "Excluded private route for sing-box: $route")
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to exclude private route $route: ${e.message}")
                                    }
                                }
                            }

                            // Note: VPN app is NOT excluded so that GeoIP checks go through the tunnel.
                            // Sing-box uses protectSocket() to bypass the tunnel for its own outbound connections.

                            synchronized(tunnelLock) {
                                if (serviceDestroyed || isCancellationRequested.get() || isDisconnecting.get()) return null
                                val nextInterface = builder.establish()
                                if (serviceDestroyed || isCancellationRequested.get() || isDisconnecting.get()) {
                                    nextInterface?.close()
                                    return null
                                }
                                val previousInterface = vpnInterface
                                vpnInterface = nextInterface
                                previousInterface?.close()
                            }
                            
                            if (vpnInterface != null) {
                                Log.i(TAG, "VPN interface established for sing-box")
                                FileLogger.write("VPN interface established for sing-box")
                            }
                            
                            return vpnInterface
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to create VPN interface", e)
                            FileLogger.logException(e)
                            return null
                        }
                    }

                    override fun protectSocket(fd: Int): Boolean {
                        val result = protect(fd)
                        Log.d(TAG, "protectSocket($fd) = $result")
                        return result
                    }
                })

                synchronized(routingLock) {
                    if (!serviceDestroyed && !isCancellationRequested.get() && !isDisconnecting.get()) {
                        singBoxEngine = engine
                        engine.runtimeDns=runtimeDns
                        engine.start(v2rayProfile ?: profile)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Sing-box connection failed", e)
                FileLogger.logException(e)
                log(LogLevel.ERROR, "Sing-box connection failed: ${e.message}")
                updateState(ConnectionState.ERROR, e.message)
                disconnect()
            }
        }
    }
}


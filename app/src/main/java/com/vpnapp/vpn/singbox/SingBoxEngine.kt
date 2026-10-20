package com.vpnapp.vpn.singbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.util.Log
import com.vpnapp.data.VpnProfile
import com.vpnapp.util.FileLogger
import io.nekohasekai.libbox.*
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SingBoxEngine - Manages sing-box core for V2Ray protocol connections.
 * Implements PlatformInterface and CommandServerHandler for the new libbox API.
 */
class SingBoxEngine(
    private val context: Context,
    private val callback: SingBoxCallback
) : PlatformInterface, CommandServerHandler {

    companion object {
        private const val TAG = "SingBoxEngine"
    }

    interface SingBoxCallback {
        fun onStarted()
        fun onStopped()
        fun onError(message: String)
        fun onLog(message: String)
        
        /**
         * Request VPN interface from VpnService
         * @return ParcelFileDescriptor for the TUN interface, or null on failure
         */
        fun requestVpnInterface(
            address: String,
            prefix: Int,
            dnsServers: List<String>,
            mtu: Int
        ): ParcelFileDescriptor?
        
        /**
         * Protect a socket from VPN routing
         */
        fun protectSocket(fd: Int): Boolean
    }

    private var commandServer: CommandServer? = null
    private var probeEndpoint: ProbeEndpoint? = null
    private val isRunning = AtomicBoolean(false)
    
    // Store the interface update listener for sing-box to know about network changes
    @Volatile private var interfaceListener: InterfaceUpdateListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Start sing-box service with the given profile
     */
    @Synchronized
    fun start(profile: VpnProfile) {
        protectOpenConnectSockets = profile.protocol == com.vpnapp.data.VpnProtocol.OPENCONNECT
        if (isRunning.getAndSet(true)) {
            callback.onError("Already running")
            return
        }

        try {
            callback.onLog("Generating sing-box configuration...")
            
            // Generate configuration
            val probe = ProbeEndpoint.create()
            probeEndpoint = probe
            val config = SingBoxConfigGenerator.generate(profile, context, probe, runtimeDns)
            logDnsMode()
            
            // Save to file for debugging purposes
            val configFile = File(context.cacheDir, "sing-box-config.json")
            configFile.writeText(config)
            callback.onLog("Configuration saved to: ${configFile.absolutePath}")
            FileLogger.write("Sing-box configuration generated (${profile.protocol.displayName})")

            // Initialize libbox
            callback.onLog("Initializing sing-box...")
            
            // Setup libbox with proper working directories to avoid cache.db permission errors
            val setupOptions = SetupOptions().apply {
                // Delay tests run in the UI process; never share their command.sock/cache files.
                setBasePath(File(context.filesDir, "vpn-core").apply { mkdirs() }.absolutePath)
                setWorkingPath(File(context.cacheDir, "vpn-core").apply { mkdirs() }.absolutePath)
                setTempPath(File(context.cacheDir, "vpn-core").absolutePath)
            }
            Libbox.setup(setupOptions)
            
            // Create CommandServer - new API replaces BoxService
            // 'this' is passed as both CommandServerHandler and PlatformInterface
            Log.i(TAG, "Creating CommandServer with PlatformInterface (this)")
            callback.onLog("Creating sing-box with socket protection enabled")
            commandServer = CommandServer(this, this)
            
            // Start the command server first
            commandServer?.start()
            
            // Then start or reload the service with the config
            val overrideOptions = OverrideOptions()
            commandServer?.startOrReloadService(config, overrideOptions)
            ProbeEndpoint.active = probe
            startTrafficClient()
            
            callback.onLog("Starting sing-box service...")
            callback.onStarted()
            
        } catch (e: Exception) {
            if (ProbeEndpoint.active == probeEndpoint) ProbeEndpoint.active = null
            isRunning.set(false)
            Log.e(TAG, "Failed to start sing-box", e)
            FileLogger.logException(e)
            callback.onError("Failed to start: ${e.message}")
        }
    }

    /**
     * Stop sing-box service
     */
    @Synchronized
    fun reloadRouting(profile: VpnProfile) {
        check(isRunning.get()) { "Sing-box is not running" }
        val config = SingBoxConfigGenerator.generate(profile, context, probeEndpoint, runtimeDns)
        val server = checkNotNull(commandServer) { "Sing-box command server is unavailable" }
        synchronized(trafficLock) { trafficGeneration++ }
        trafficClient?.disconnect();trafficClient=null
        server.startOrReloadService(config, OverrideOptions())
        synchronized(trafficLock) { nativeRx=0;nativeTx=0 }
        startTrafficClient()
        File(context.cacheDir, "sing-box-config.json").writeText(config)
        callback.onLog("Routing applied: Iran=${profile.bypassIranRoutes}, LAN=${profile.bypassPrivateIps}")
        logDnsMode()
    }

    private fun logDnsMode() {
        val prefs = context.getSharedPreferences("vpn_app_prefs", 0)
        val fakeIp = runtimeDns?.fakeIp ?: prefs.getBoolean(TunnelDns.FAKE_IP_KEY, true)
        val dnsHost = java.net.URI(runtimeDns?.url ?: prefs.getString(TunnelDns.URL_KEY, TunnelDns.DEFAULT_URL)).host
        callback.onLog(if (fakeIp) "DNS mode: FakeIP (destination A/AAAA answers do not use DoH)"
            else "DNS mode: real answers via DoH $dnsHost through VPN")
    }

    @Synchronized
    fun stop() {
        if (ProbeEndpoint.active == probeEndpoint) ProbeEndpoint.active = null
        probeEndpoint = null
        isRunning.set(false)

        callback.onLog("Stopping sing-box service...")
        
        networkCallback?.let {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback: ${e.message}")
            }
            networkCallback = null
        }
        interfaceListener = null

        // The service calls this on its IO scope and waits before announcing DISCONNECTED.
        runCatching { trafficClient?.disconnect() };trafficClient=null
        val server = commandServer
        commandServer = null
        try {
            server?.closeService()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing sing-box service", e)
            FileLogger.logException(e)
        } finally {
            // Closing the command server must not be skipped if closing its service fails.
            try { server?.close() } catch (e: Exception) { Log.e(TAG, "Error closing command server", e) }
            callback.onStopped()
        }
    }

    private var trafficClient: CommandClient? = null
    var runtimeDns: TunnelDnsSettings? = null
    private val trafficLock = Any()
    private var nativeRx=0L; private var nativeTx=0L
    private var aggregateRx=0L; private var aggregateTx=0L
    private var trafficGeneration=0L
    fun trafficTotals(): Pair<Long,Long> = synchronized(trafficLock) { aggregateRx to aggregateTx }
    private fun startTrafficClient() {
        val generation=synchronized(trafficLock) { ++trafficGeneration }
        val handler = object : CommandClientHandler {
            override fun clearLogs() {}; override fun connected() {}; override fun disconnected(message: String?) {}
            override fun initializeClashMode(modes: StringIterator?, current: String?) {}
            override fun setDefaultLogLevel(level: Int) {}; override fun updateClashMode(mode: String?) {}
            override fun writeConnectionEvents(events: ConnectionEvents?) {}; override fun writeDNSQuery(query: DnsQuery?) {}
            override fun writeGroups(groups: OutboundGroupIterator?) {}; override fun writeLogs(logs: LogIterator?) {}
            override fun writeOutbounds(outbounds: OutboundGroupItemIterator?) {}
            override fun writeStatus(status: StatusMessage?) {
                if(status == null || !status.trafficAvailable) return
                synchronized(trafficLock) {
                    if(generation != trafficGeneration) return
                    val rx=status.downlinkTotal.coerceAtLeast(0); val tx=status.uplinkTotal.coerceAtLeast(0)
                    aggregateRx += if(rx>=nativeRx) rx-nativeRx else rx
                    aggregateTx += if(tx>=nativeTx) tx-nativeTx else tx
                    nativeRx=rx;nativeTx=tx
                }
            }
        }
        trafficClient=CommandClient(handler,CommandClientOptions().apply {
            addCommand(Libbox.CommandStatus); setStatusInterval(1_000_000_000)
        }).also { it.connect() }
    }

    fun isRunning(): Boolean = isRunning.get()

    // =========================================================================
    // CommandServerHandler Implementation
    // =========================================================================

    override fun connectSSHAgent(): Int {
        return -1 // Not supported
    }

    override fun getSystemProxyStatus(): SystemProxyStatus {
        return SystemProxyStatus().apply {
            setAvailable(false)
            setEnabled(false)
        }
    }

    override fun serviceReload() {
        Log.d(TAG, "serviceReload called")
    }

    override fun serviceStop() {
        Log.d(TAG, "serviceStop called")
        // This is a native callback. Let the service schedule shutdown instead of re-entering Go closeService.
        callback.onStopped()
    }

    override fun setSystemProxyEnabled(enabled: Boolean) {
        Log.d(TAG, "setSystemProxyEnabled: $enabled")
    }

    override fun triggerNativeCrash() {
        Log.d(TAG, "triggerNativeCrash called (ignored)")
    }

    override fun writeDebugMessage(message: String?) {
        if (message != null) {
            Log.d(TAG, "libbox: $message")
            FileLogger.write("libbox: $message")
            callback.onLog(message)
        }
    }

    // =========================================================================
    // PlatformInterface Implementation - Called by Go runtime
    // =========================================================================

    private var protectOpenConnectSockets = false
    override fun autoDetectInterfaceControl(fd: Int) {
        // Called when sing-box needs to protect a socket for outbound connections
        Log.i(TAG, "autoDetectInterfaceControl: protecting fd=$fd")
        val result = callback.protectSocket(fd)
        check(result) { "Could not protect sing-box outbound socket" }
        if (protectOpenConnectSockets) runCatching {
            val preferences=context.getSharedPreferences("vpn_app_prefs",0)
            val size=preferences.getInt("buffer_size",2*1024*1024).coerceIn(65536,8388608)
            android.os.ParcelFileDescriptor.fromFd(fd).use { duplicate ->
                val socket=duplicate.fileDescriptor
                android.system.Os.setsockoptInt(socket,android.system.OsConstants.SOL_SOCKET,android.system.OsConstants.SO_RCVBUF,size)
                android.system.Os.setsockoptInt(socket,android.system.OsConstants.SOL_SOCKET,android.system.OsConstants.SO_SNDBUF,size)
                try {
                    android.system.Os.setsockoptInt(socket,android.system.OsConstants.IPPROTO_TCP,
                        android.system.OsConstants.TCP_NODELAY,if(preferences.getBoolean("tcp_no_delay",true)) 1 else 0)
                } catch (error: android.system.ErrnoException) {
                    if (error.errno != android.system.OsConstants.ENOPROTOOPT &&
                        error.errno != android.system.OsConstants.EOPNOTSUPP) throw error
                }
            }
        }.onFailure { Log.w(TAG,"OpenConnect socket tuning unavailable",it) }
        Log.i(TAG, "autoDetectInterfaceControl: fd=$fd protected=$result")
    }

    override fun checkPlatformShell() {
        // No-op on Android - shell features not supported
        Log.d(TAG, "checkPlatformShell called")
    }

    override fun clearDNSCache() {
        Log.d(TAG, "clearDNSCache called")
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        Log.d(TAG, "closeDefaultInterfaceMonitor called")
        networkCallback?.let {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback: ${e.message}")
            }
            networkCallback = null
        }
        interfaceListener = null
    }

    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {
        Log.d(TAG, "closeNeighborMonitor called")
    }

    override fun createBridge(options: BridgeOptions?): BridgeSession? {
        Log.d(TAG, "createBridge called - not supported")
        return null
    }

    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String?, sourcePort: Int, destinationAddress: String?, destinationPort: Int): ConnectionOwner {
        // Return an empty ConnectionOwner
        return ConnectionOwner()
    }

    private fun createStringIterator(list: List<String>): StringIterator {
        return object : StringIterator {
            private var idx = 0
            override fun hasNext(): Boolean = idx < list.size
            override fun len(): Int = list.size
            override fun next(): String = list[idx++]
        }
    }

    override fun getInterfaces(): NetworkInterfaceIterator? {
        Log.i(TAG, "getInterfaces called")
        
        return try {
            val interfaces = mutableListOf<NetworkInterface>()
            
            // Get all network interfaces from Java's NetworkInterface
            val netInterfaces = java.net.NetworkInterface.getNetworkInterfaces()
            if (netInterfaces == null) {
                Log.w(TAG, "getInterfaces: NetworkInterface.getNetworkInterfaces() returned null!")
                // Return a dummy interface so sing-box doesn't give up
                return createDummyInterfaceIterator("wlan0", 0)
            }
            
            while (netInterfaces.hasMoreElements()) {
                val netIf = netInterfaces.nextElement()
                
                Log.d(TAG, "Checking interface: ${netIf.name} isUp=${netIf.isUp} isLoopback=${netIf.isLoopback}")
                
                // Skip loopback and down interfaces
                if (netIf.isLoopback || !netIf.isUp) {
                    continue
                }
                
                // Skip our TUN interface
                if (netIf.name.startsWith("tun")) {
                    continue
                }
                
                val libboxInterface = NetworkInterface()
                libboxInterface.index = netIf.index
                libboxInterface.name = netIf.name

                var flags = 0
                if (netIf.isUp) {
                    flags = flags or 1 // IFF_UP = 0x1
                    flags = flags or 0x40 // IFF_RUNNING = 0x40
                }
                if (netIf.isLoopback) flags = flags or 0x8 // IFF_LOOPBACK = 0x8
                if (netIf.isPointToPoint) flags = flags or 0x10 // IFF_POINTOPOINT = 0x10
                if (netIf.supportsMulticast()) flags = flags or 0x1000 // IFF_MULTICAST = 0x1000
                libboxInterface.flags = flags

                val addrList = mutableListOf<String>()
                try {
                    for (addr in netIf.interfaceAddresses) {
                        val host = addr.address?.hostAddress?.substringBefore('%')
                        if (!host.isNullOrEmpty()) {
                            addrList.add("$host/${addr.networkPrefixLength}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading addresses for ${netIf.name}: ${e.message}")
                }
                if (addrList.isNotEmpty()) {
                    libboxInterface.setAddresses(createStringIterator(addrList))
                }

                interfaces.add(libboxInterface)
                Log.i(TAG, "Found interface: ${netIf.name} (index=${netIf.index}, addrs=${addrList.size}, flags=$flags)")
            }
            
            if (interfaces.isEmpty()) {
                Log.w(TAG, "No network interfaces found! Using dummy wlan0")
                // Return a dummy interface so sing-box can still try to route
                return createDummyInterfaceIterator("wlan0", 20)
            }
            
            Log.i(TAG, "Returning ${interfaces.size} network interfaces")
            
            // Return as iterator
            object : NetworkInterfaceIterator {
                private var currentIndex = 0
                override fun hasNext(): Boolean = currentIndex < interfaces.size
                override fun next(): NetworkInterface = interfaces[currentIndex++]
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get network interfaces", e)
            // Return dummy interface on error
            createDummyInterfaceIterator("wlan0", 20)
        }
    }
    
    private fun createDummyInterfaceIterator(name: String, index: Int): NetworkInterfaceIterator {
        val dummyInterface = NetworkInterface()
        dummyInterface.index = index
        dummyInterface.name = name
        dummyInterface.flags = 0x41 // IFF_UP | IFF_RUNNING
        dummyInterface.setAddresses(createStringIterator(listOf("192.168.1.100/24")))
        val list = listOf(dummyInterface)
        return object : NetworkInterfaceIterator {
            private var idx = 0
            override fun hasNext(): Boolean = idx < list.size
            override fun next(): NetworkInterface = list[idx++]
        }
    }

    override fun includeAllNetworks(): Boolean {
        return false
    }

    override fun localDNSTransport(): LocalDNSTransport? {
        return null
    }

    override fun lookupSFTPServer(): String {
        return "" // Not supported on Android
    }

    override fun lookupUser(username: String?): PlatformUser? {
        return null // Not supported on Android
    }

    override fun openShellSession(user: PlatformUser?, command: String?, args: StringIterator?, workDir: String?, cols: Int, rows: Int): ShellSession? {
        return null // Not supported on Android
    }

    override fun openTun(options: TunOptions?): Int {
        if (options == null) {
            Log.e(TAG, "openTun called with null options")
            return -1
        }
        
        Log.d(TAG, "openTun requested: mtu=${options.getMTU()}")
        callback.onLog("Opening TUN interface...")
        
        try {
            var address = "172.19.0.1"
            var prefix = 30
            val mtu = options.getMTU()

            try {
                val inet4Iterator = options.getInet4Address()
                if (inet4Iterator != null && inet4Iterator.hasNext()) {
                    val p = inet4Iterator.next()
                    address = p.address()
                    prefix = p.prefix()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract inet4 address from TunOptions: ${e.message}")
            }

            val dnsList = mutableListOf<String>()
            try {
                val dnsBox = options.getDNSServerAddress()
                while (dnsBox != null && dnsBox.hasNext()) {
                    val d = dnsBox.next()
                    if (d.isNotEmpty()) dnsList.add(d)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract DNS server from TunOptions: ${e.message}")
            }
            
            val fd = callback.requestVpnInterface(address, prefix, dnsList, mtu)
            
            if (fd != null) {
                Log.d(TAG, "TUN interface opened: fd=${fd.fd}")
                callback.onLog("TUN interface opened successfully")
                // Go owns and closes this duplicate. The service retains its own descriptor.
                return ParcelFileDescriptor.dup(fd.fileDescriptor).detachFd()
            } else {
                Log.e(TAG, "Failed to create TUN interface")
                callback.onError("Failed to create TUN interface")
                return -1
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening TUN", e)
            callback.onError("TUN error: ${e.message}")
            return -1
        }
    }

    override fun readSystemSSHHostKey(): String {
        return "" // Not supported on Android
    }

    override fun readWIFIState(): WIFIState? {
        return null
    }

    override fun registerMyInterface(name: String?) {
        Log.d(TAG, "registerMyInterface: $name")
    }

    override fun sendNotification(notification: Notification?) {
        Log.d(TAG, "sendNotification: ${notification?.title}")
    }

    override fun cancelNotification(identifier: String?, id: Int) {
        // libbox currently does not create Android notifications through this platform adapter.
    }

    private data class InterfaceInfo(val name: String, val index: Int, val networkHandle: Long = 0, val addresses: String = "")

    @Volatile
    private var lastKnownInterface: InterfaceInfo = InterfaceInfo("", -1)
    private val lostNetworkHandles = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    private fun isNetworkMetered(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val info = getDefaultInterfaceInfo()
            val network = cm.allNetworks.firstOrNull { it.networkHandle == info.networkHandle }
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } catch (e: Exception) {
            false
        }
    }

    private fun getDefaultInterfaceInfo(): InterfaceInfo {
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val candidates = cm.allNetworks.filter { network ->
                val caps = cm.getNetworkCapabilities(network)
                network.networkHandle !in lostNetworkHandles &&
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }.sortedWith(compareByDescending<Network> {
                cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }.thenByDescending { it == cm.activeNetwork }.thenByDescending {
                val caps = cm.getNetworkCapabilities(it)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true ||
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            })
            for (network in candidates) {
                val props = cm.getLinkProperties(network) ?: continue
                val name = props.interfaceName ?: continue
                if (name.startsWith("tun")) continue
                val index = java.net.NetworkInterface.getByName(name)?.index ?: continue
                if (index <= 0) continue
                // A renewed IPv4 address on the same interface also invalidates old TCP sessions.
                val addresses = props.linkAddresses.filter { it.address is java.net.Inet4Address }
                    .map { it.toString() }.sorted().joinToString(",")
                return InterfaceInfo(name, index, network.networkHandle, addresses).also { lastKnownInterface = it }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting default interface info: ${e.message}")
        }
        // libbox uses index -1 to pause traffic and invalidate the lost physical path.
        return InterfaceInfo("", -1).also { lastKnownInterface = it }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        // A reload can install a new listener without destroying the engine.
        networkCallback?.let {
            try { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
            catch (_: Exception) { }
        }
        networkCallback = null
        // Store the listener for future network changes
        interfaceListener = listener
        lostNetworkHandles.clear()
        
        // Immediately notify sing-box of the current default interface
        // This triggers socket protection mechanisms
        if (listener != null) {
            try {
                val info = getDefaultInterfaceInfo()
                val isExpensive = isNetworkMetered()
                val updates = InterfaceUpdateFilter()
                updates.changed(PhysicalInterfaceState(info.name, info.index, info.networkHandle, isExpensive, info.addresses))
                Log.i(TAG, "Notifying sing-box default interface: ${info.name} (index=${info.index}, expensive=$isExpensive)")
                listener.updateDefaultInterface(info.name, info.index, isExpensive, false)

                // Register network callback to notify sing-box when physical network changes
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { lostNetworkHandles.remove(network.networkHandle); notifyChange() }
                    override fun onLost(network: Network) { lostNetworkHandles.add(network.networkHandle); notifyChange() }
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { notifyChange() }
                    override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) { notifyChange() }
                    private fun notifyChange() {
                        try {
                            val updated = getDefaultInterfaceInfo()
                            val expensive = isNetworkMetered()
                            val next = PhysicalInterfaceState(updated.name, updated.index, updated.networkHandle, expensive, updated.addresses)
                            synchronized(updates) {
                                val reset = updates.needsReset(next)
                                if (interfaceListener === listener && updates.changed(next)) {
                                    // The core compares name/index only. Signal loss first when Android
                                    // reuses those values for a new Network or renewed IP address.
                                    if (reset) listener.updateDefaultInterface("", -1, false, false)
                                    listener.updateDefaultInterface(updated.name, updated.index, expensive, false)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in network callback notifyChange: ${e.message}")
                        }
                    }
                }
                networkCallback = callback

                // Listen for physical internet-capable network changes without being masked by VPN
                try {
                    val request = android.net.NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                        .build()
                    cm.registerNetworkCallback(request, callback)
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback to registerDefaultNetworkCallback: ${e.message}")
                    cm.registerDefaultNetworkCallback(callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying interface: ${e.message}", e)
                listener.updateDefaultInterface(lastKnownInterface.name, lastKnownInterface.index, false, false)
            }
        }
    }

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {
        Log.d(TAG, "startNeighborMonitor called")
    }

    override fun tailscaleHostname(): String {
        return ""
    }

    override fun underNetworkExtension(): Boolean {
        return false
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean {
        return true
    }

    override fun usePlatformBridge(): Boolean {
        return false
    }

    override fun usePlatformShell(): Boolean {
        return false
    }

    override fun useProcFS(): Boolean {
        return false
    }
}

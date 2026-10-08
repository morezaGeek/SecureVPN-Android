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
        if (isRunning.getAndSet(true)) {
            callback.onError("Already running")
            return
        }

        try {
            callback.onLog("Generating sing-box configuration...")
            
            // Generate configuration
            val probe = ProbeEndpoint.create()
            probeEndpoint = probe
            val config = SingBoxConfigGenerator.generate(profile, context, probe)
            
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
        val config = SingBoxConfigGenerator.generate(profile, context, probeEndpoint)
        val server = checkNotNull(commandServer) { "Sing-box command server is unavailable" }
        server.startOrReloadService(config, OverrideOptions())
        File(context.cacheDir, "sing-box-config.json").writeText(config)
        callback.onLog("Routing applied: Iran=${profile.bypassIranRoutes}, LAN=${profile.bypassPrivateIps}")
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

    override fun autoDetectInterfaceControl(fd: Int) {
        // Called when sing-box needs to protect a socket for outbound connections
        Log.i(TAG, "autoDetectInterfaceControl: protecting fd=$fd")
        val result = callback.protectSocket(fd)
        check(result) { "Could not protect sing-box outbound socket" }
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

    private data class InterfaceInfo(val name: String, val index: Int, val networkHandle: Long = 0)

    @Volatile
    private var lastKnownInterface: InterfaceInfo = InterfaceInfo("wlan0", 1)

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
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork
            if (activeNetwork != null) {
                val caps = cm.getNetworkCapabilities(activeNetwork)
                val linkProps = cm.getLinkProperties(activeNetwork)
                val ifName = linkProps?.interfaceName
                if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    !ifName.isNullOrEmpty() && !ifName.startsWith("tun")
                ) {
                    val idx = try {
                        java.net.NetworkInterface.getByName(ifName)?.index ?: 0
                    } catch (e: Exception) { 0 }
                    if (idx > 0) {
                        val info = InterfaceInfo(ifName, idx, activeNetwork.networkHandle)
                        lastKnownInterface = info
                        return info
                    }
                }
            }

            // Fallback 1: search allNetworks for physical network
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue

                val isPhysical = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!isPhysical) continue

                val linkProps = cm.getLinkProperties(network) ?: continue
                val ifName = linkProps.interfaceName ?: continue
                if (ifName.startsWith("tun")) continue

                val idx = try {
                    java.net.NetworkInterface.getByName(ifName)?.index ?: 0
                } catch (e: Exception) { 0 }
                if (idx > 0) {
                    val info = InterfaceInfo(ifName, idx, network.networkHandle)
                    lastKnownInterface = info
                    return info
                }
            }

            // Fallback 2: scan java.net.NetworkInterface for any active physical interface with IPv4
            val netInterfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (netInterfaces != null && netInterfaces.hasMoreElements()) {
                val netIf = netInterfaces.nextElement()
                if (!netIf.isLoopback && netIf.isUp && !netIf.name.startsWith("tun") && !netIf.name.startsWith("dummy")) {
                    val hasIpv4 = netIf.inetAddresses?.asSequence()?.any { !it.isLoopbackAddress && it is java.net.Inet4Address } == true
                    if (hasIpv4 && netIf.index > 0) {
                        val info = InterfaceInfo(netIf.name, netIf.index)
                        lastKnownInterface = info
                        return info
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting default interface info: ${e.message}")
        }
        return lastKnownInterface
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
        
        // Immediately notify sing-box of the current default interface
        // This triggers socket protection mechanisms
        if (listener != null) {
            try {
                val info = getDefaultInterfaceInfo()
                val isExpensive = isNetworkMetered()
                val updates = InterfaceUpdateFilter()
                updates.changed(PhysicalInterfaceState(info.name, info.index, info.networkHandle, isExpensive))
                Log.i(TAG, "Notifying sing-box default interface: ${info.name} (index=${info.index}, expensive=$isExpensive)")
                listener.updateDefaultInterface(info.name, info.index, isExpensive, false)

                // Register network callback to notify sing-box when physical network changes
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { notifyChange() }
                    override fun onLost(network: Network) { notifyChange() }
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { notifyChange() }
                    override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) { notifyChange() }
                    private fun notifyChange() {
                        try {
                            val updated = getDefaultInterfaceInfo()
                            if (updated.name.isNotEmpty() && updated.index > 0) {
                                val expensive = isNetworkMetered()
                                val next = PhysicalInterfaceState(updated.name, updated.index, updated.networkHandle, expensive)
                                if (isRunning.get() && interfaceListener === listener && updates.changed(next)) {
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

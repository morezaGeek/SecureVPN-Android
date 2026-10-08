package com.vpnapp.vpn.openconnect

import android.util.Log
import org.infradead.libopenconnect.LibOpenConnect
import java.io.FileDescriptor
import java.lang.reflect.Field

/**
 * Native OpenConnect Engine using official libopenconnect JNI bindings.
 * Provides high-performance VPN with stable DTLS support.
 */
class NativeOpenConnectEngine(
    private val serverAddress: String,
    private val serverPort: Int,
    private val username: String,
    private val password: String,
    private val skipCertVerify: Boolean = true,
    private val mtu: Int = 1400,
    private val disableDtls: Boolean = false,
    private val bufferSize: Int = 2 * 1024 * 1024,
    private val tcpNoDelay: Boolean = true,
    private val protectCallback: (Int) -> Boolean
) : LibOpenConnect("AnyConnect Android 4.10.05085") {

    private val TAG = "NativeOpenConnectEngine"
    private var callback: OpenConnectEngine.OpenConnectCallback? = null
    
    @Volatile private var isRunning = false
    private var tunnelThread: Thread? = null

    /**
     * Protect the socket from the VPN tunnel to prevent loops.
     * Use the file descriptor directly.
     */
    override fun onProtectSocket(fd: Int) {
        val protected = protectCallback(fd)
        if (protected) {
            Log.d(TAG, "Successfully protected socket FD: $fd")
        } else {
            Log.w(TAG, "Failed to protect socket FD: $fd")
        }
        try {
            // Duplicate the descriptor without closing the native library's socket.
            android.os.ParcelFileDescriptor.fromFd(fd).use { duplicate ->
                val socket = duplicate.fileDescriptor
                android.system.Os.setsockoptInt(socket, android.system.OsConstants.SOL_SOCKET,
                    android.system.OsConstants.SO_RCVBUF, bufferSize)
                android.system.Os.setsockoptInt(socket, android.system.OsConstants.SOL_SOCKET,
                    android.system.OsConstants.SO_SNDBUF, bufferSize)
                val stream = try {
                    android.system.Os.setsockoptInt(socket, android.system.OsConstants.IPPROTO_TCP,
                        android.system.OsConstants.TCP_NODELAY, if (tcpNoDelay) 1 else 0)
                    true
                } catch (error: android.system.ErrnoException) {
                    // TCP_NODELAY is not applicable to DTLS/UDP sockets.
                    if (error.errno != android.system.OsConstants.ENOPROTOOPT && error.errno != android.system.OsConstants.EOPNOTSUPP) throw error
                    false
                }
                val noDelay = if (stream) (if (tcpNoDelay) "1" else "0") else "not applicable (UDP)"
                val message = "Socket settings: buffer request=$bufferSize accepted, TCP_NODELAY=$noDelay"
                Log.d(TAG, message)
                callback?.onLog(message)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not apply native socket settings", error)
            callback?.onLog("Native socket settings unavailable: ${error.javaClass.simpleName}")
        }
    }

    /**
     * Handle authentication forms from the native library
     */
    override fun onProcessAuthForm(authForm: AuthForm): Int {
        Log.d(TAG, "onProcessAuthForm: banner=${authForm.banner}, message=${authForm.message}, authID=${authForm.authID}")
        
        var optionsFilled = 0
        for (opt in authForm.opts) {
            Log.d(TAG, "Form Option: name=${opt.name}, type=${opt.type}")
            when (opt.name) {
                "username" -> {
                    opt.value = username
                    optionsFilled++
                }
                "password" -> {
                    opt.value = password
                    optionsFilled++
                }
            }
        }
        
        // If the server requires choosing a group but we haven't selected one
        if (authForm.authgroupOpt != null && authForm.authgroupOpt!!.choices.isNotEmpty()) {
             Log.d(TAG, "Authgroup required. Selecting first choice: ${authForm.authgroupOpt!!.choices[0].name}")
             authForm.authgroupSelection = 0
             return OC_FORM_RESULT_NEWGROUP
        }

        if (optionsFilled == 0 && (authForm.banner != null || authForm.message != null)) {
            Log.d(TAG, "Informational form (Banner/Message only). Proceeding with OK.")
        }

        return OC_FORM_RESULT_OK
    }

    /**
     * Handle certificate validation
     */
    override fun onValidatePeerCert(msg: String): Int {
        Log.w(TAG, "onValidatePeerCert: $msg")
        return if (skipCertVerify) {
            Log.d(TAG, "Skipping certificate verification by user preference.")
            0 // 0 means Success/Accept in libopenconnect
        } else {
            -1 // Reject
        }
    }

    private var lastDpdLogTime = 0L

    /**
     * Handle progress logs from the native library
     */
    override fun onProgress(level: Int, msg: String) {
        // 1. CRITICAL: DETECT DTLS IMMEDIATELY (Move outside any filter)
        if (msg.contains("Established DTLS") || msg.contains("UDP session established")) {
            Log.i(TAG, "DTLS established detected in logs: $msg")
            currentTransport.set("TCP+UDP")
            callback?.onLog(msg)
            return
        }

        // 2. CRITICAL: DETECT SERVER DISCONNECT
        // Filter out "Aborted by caller" because that's a user-initiated disconnect, not a server error.
        if ((msg.contains("Received server disconnect") || msg.contains("Server request") || msg.contains("BYE packet")) 
            && !msg.contains("Aborted by caller") && isRunning) {
            Log.w(TAG, "Server requested disconnect: $msg")
            callback?.onError("Server disconnected: $msg")
            return
        }

        val levelStr = when (level) {
            PRG_ERR -> "ERROR"
            PRG_INFO -> "INFO"
            PRG_DEBUG -> "DEBUG"
            PRG_TRACE -> "TRACE"
            else -> "UNKNOWN"
        }

        // Always log to Logcat for system debugging
        Log.d(TAG, "[$levelStr] $msg")

        // 3. THROTTLE DPD/KEEPALIVE: Only send to UI once every 20 seconds
        if (msg.contains("DPD") || msg.contains("Keepalive", ignoreCase = true)) {
            val now = System.currentTimeMillis()
            if (now - lastDpdLogTime > 20000) {
                lastDpdLogTime = now
                callback?.onLog(msg)
            }
            return
        }

        // 4. SUPPRESS CHATTY CONFIGS
        if (msg.contains("X-CSTP-Split") || msg.contains("vhost-net") || msg.contains("X-DTLS")) {
            return
        }

        // 5. FILTER: Only send ERROR and INFO to the UI for performance
        // Note: libopenconnect levels: ERR=0, INFO=1, DEBUG=2
        if (level <= PRG_INFO) {
            callback?.onLog(msg)
        }
    }

    /**
     * Connect to the VPN server
     */
    fun connectSync(cb: OpenConnectEngine.OpenConnectCallback): Boolean {
        this.callback = cb
        isRunning = true

        try {
            val url = "https://$serverAddress:$serverPort"
            callback?.onLog("Parsing URL: $url")
            
            // Try to set optional environment settings safely
            try {
                setReportedOS("android")
            } catch (e: Throwable) {
                Log.w(TAG, "setReportedOS not available: ${e.message}")
            }
            
            try {
                setXMLPost(true)
            } catch (e: Throwable) {
                Log.w(TAG, "setXMLPost not available: ${e.message}")
            }

            val parseResult = parseURL(url)
            if (parseResult != 0) {
                callback?.onError("Failed to parse URL (code $parseResult)")
                return false
            }
            
            setProtocol("anyconnect") // standard anyconnect
            setReqMTU(mtu)
            
            // AGGRESSIVE DPD: Set short Dead Peer Detection interval (20s instead of default 60s)
            // This helps keep NAT sessions alive and prevents idle disconnects.
            try {
               setDPD(20)
               callback?.onLog("SUCCESS: Set DPD interval to 20 seconds")
            } catch (e: Throwable) {
               Log.w(TAG, "setDPD not available: ${e.message}")
            }
            
            // MOBILE INFO: Signal to server that this is a mobile client
            try {
               setMobileInfo("android", "generic", "1.0")
               callback?.onLog("SUCCESS: Set mobile info headers")
            } catch (e: Throwable) {
               Log.w(TAG, "setMobileInfo not available: ${e.message}")
            }
            
            if (disableDtls) {
                disableDTLS()
            }

            callback?.onLog("Authenticating with native library...")
            val cookieResult = obtainCookie()
            if (cookieResult != 0) {
                callback?.onError("Failed to obtain cookie (code $cookieResult)")
                return false
            }

            callback?.onLog("Cookie obtained. Establishing CSTP connection...")
            val cstpResult = makeCSTPConnection()
            if (cstpResult != 0) {
                callback?.onError("CSTP connection failed (code $cstpResult)")
                return false
            }

            // At this point, the native library has negotiated IP and MTU
            val ipInfo = getIPInfo()
            if (ipInfo == null || ipInfo.addr == null) {
                callback?.onError("Failed to get IP info from native library")
                return false
            }
            
            // Log split routing info if present
            if (ipInfo.splitExcludes.isNotEmpty()) {
                callback?.onLog("Server no-route (excludes): ${ipInfo.splitExcludes.joinToString(", ")}")
            }
            if (ipInfo.splitIncludes.isNotEmpty()) {
                callback?.onLog("Server split routes (includes): ${ipInfo.splitIncludes.joinToString(", ")}")
            }

            callback?.onConnected(
                privateIp = ipInfo.addr!!,
                serverIpAddr = ipInfo.gatewayAddr ?: serverAddress, // Use actual gateway IP, fallback to domain
                dnsServers = ipInfo.DNS,
                mtu = ipInfo.MTU,
                splitExcludes = ipInfo.splitExcludes,
                splitIncludes = ipInfo.splitIncludes
            )

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Native connection error", e)
            callback?.onError("Native Connect: ${e.message}")
            return false
        }
    }

    /**
     * Associate the VpnService TUN file descriptor with the native engine.
     * This should be called from the SAME thread that called connectSync(),
     * otherwise JNI will detect cross-thread access and crash.
     */
    fun startTunnel(tunFd: Int) {
        try {
            callback?.onLog("Starting native tunnel with FD: $tunFd")
            setupTunFD(tunFd)
            
            // Attempt DTLS setup (native library handles negotiation period)
            if (!disableDtls) {
                callback?.onLog("DTLS is ENABLED. Attempting native DTLS setup (30s)...")
                setupDTLS(30) // Attempt for 30 seconds
            } else {
                callback?.onLog("DTLS is DISABLED by configuration.")
            }

            // Main loop for packet processing (blocking)
            // timeout=300ms (standard), signal every 1s
            callback?.onLog("Entering native mainloop (blocking)...")
            mainloop(300, 1)
            
            Log.d(TAG, "Native mainloop returned")
            callback?.onLog("Native mainloop exited")
        } catch (e: Exception) {
            Log.e(TAG, "Native mainloop error", e)
            callback?.onError("Tunnel MainLoop: ${e.message}")
        } finally {
            isRunning = false
            callback?.onDisconnected()
            // DO NOT call destroy() here automatically, wait for Service to call cleanup() 
            // once it's sure no other threads are using this engine.
        }
    }

    fun disconnect() {
        Log.d(TAG, "Native disconnect requested (sending cancel signal)")
        isRunning = false
        try {
            cancel() // Signals the native mainloop to exit
        } catch (e: Exception) {
            Log.e(TAG, "Error during native cancel", e)
        }
    }

    @Volatile private var isDestroyed = false
    
    fun cleanup() {
        if (isDestroyed) return
        Log.d(TAG, "Native cleanup: destroying JNI object")
        isDestroyed = true
        try {
            // Give native internal threads more time to settle (300ms)
            Thread.sleep(300)
            destroy() // Free the native pointer
        } catch (e: Exception) {
            Log.e(TAG, "Error during native destroy", e)
        }
    }
    
    // Atomic stats counters updated by native callback onStatsUpdate
    val rxBytes = java.util.concurrent.atomic.AtomicLong(0L)
    val txBytes = java.util.concurrent.atomic.AtomicLong(0L)
    
    private val currentTransport = java.util.concurrent.atomic.AtomicReference<String>("TCP")

    override fun onStatsUpdate(stats: VPNStats) {
        // Just store the values. Logging here is too frequent.
        rxBytes.set(stats.rxBytes)
        txBytes.set(stats.txBytes)
    }
    
    fun getActiveTransport(): String {
        // CRITICAL: NEVER call JNI methods like getDTLSCipher() here.
        // It clashes with the mainloop thread and causes deadlocks.
        // We rely on onProgress() logs to update this atomic reference.
        return currentTransport.get()
    }
}

package com.vpnapp.vpn.openconnect

import android.util.Log

/**
 * JNI wrapper for native OpenConnect library with DTLS support.
 * This class provides the interface between Kotlin and the native
 * libopenconnect.so library compiled with GnuTLS for DTLS.
 * 
 * The native library must be placed in jniLibs directories:
 * - jniLibs/arm64-v8a/libopenconnect.so
 * - jniLibs/armeabi-v7a/libopenconnect.so
 * - jniLibs/x86/libopenconnect.so
 * - jniLibs/x86_64/libopenconnect.so
 */
object NativeOpenConnect {
    
    private const val TAG = "NativeOpenConnect"
    
    private var isLoaded = false
    private var loadError: String? = null
    
    /**
     * Try to load the native library
     * Returns true if successful, false otherwise
     */
    fun initialize(): Boolean {
        if (isLoaded) return true
        
        return try {
            // Try to load the native OpenConnect library
            System.loadLibrary("openconnect")
            isLoaded = true
            loadError = null
            Log.i(TAG, "Native OpenConnect library loaded successfully")
            true
        } catch (e: UnsatisfiedLinkError) {
            loadError = "Failed to load libopenconnect.so: ${e.message}"
            Log.e(TAG, loadError!!)
            false
        } catch (e: Exception) {
            loadError = "Error loading native library: ${e.message}"
            Log.e(TAG, loadError!!, e)
            false
        }
    }
    
    /**
     * Check if native library is available
     */
    fun isAvailable(): Boolean = isLoaded
    
    /**
     * Get the load error message if loading failed
     */
    fun getLoadError(): String? = loadError
    
    // ==== Native JNI Methods (implemented in C/C++) ====
    
    /**
     * Get the native library version
     */
    external fun getVersion(): String?
    
    /**
     * Initialize a new VPN connection context
     * @return Native context pointer (as long) or 0 on error
     */
    external fun createContext(): Long
    
    /**
     * Set the server hostname/IP and port
     */
    external fun setServer(context: Long, hostname: String, port: Int): Int
    
    /**
     * Set authentication credentials
     */
    external fun setCredentials(context: Long, username: String, password: String): Int
    
    /**
     * Perform the TLS and authentication handshake
     * @return 0 on success, error code on failure
     */
    external fun connect(context: Long): Int
    
    /**
     * Get the assigned VPN IP address after connection
     */
    external fun getVpnIpAddress(context: Long): String?
    
    /**
     * Get the VPN MTU
     */
    external fun getVpnMtu(context: Long): Int
    
    /**
     * Get the VPN file descriptor for TUN interface
     */
    external fun getVpnFd(context: Long): Int
    
    /**
     * Check if DTLS is established
     */
    external fun isDtlsConnected(context: Long): Boolean
    
    /**
     * Get DTLS cipher suite in use
     */
    external fun getDtlsCipherSuite(context: Long): String?
    
    /**
     * Send data through the VPN tunnel
     * @return Number of bytes sent, or negative on error
     */
    external fun sendData(context: Long, data: ByteArray, offset: Int, length: Int): Int
    
    /**
     * Receive data from the VPN tunnel
     * @return Number of bytes received, or negative on error
     */
    external fun receiveData(context: Long, buffer: ByteArray, offset: Int, maxLength: Int): Int
    
    /**
     * Disconnect and cleanup
     */
    external fun disconnect(context: Long): Int
    
    /**
     * Free the connection context
     */
    external fun destroyContext(context: Long)
    
    /**
     * Set whether to disable certificate verification (for testing)
     */
    external fun setInsecure(context: Long, insecure: Boolean): Int
    
    /**
     * Set the TUN file descriptor from Android VpnService
     */
    external fun setTunFd(context: Long, tunFd: Int): Int
    
    // ==== Kotlin Helper Methods ====
    
    /**
     * Wrapper class for a native connection
     */
    class Connection private constructor(private val context: Long) {
        
        companion object {
            /**
             * Create a new connection if native library is available
             */
            fun create(): Connection? {
                if (!initialize()) {
                    Log.e(TAG, "Cannot create connection: native library not loaded")
                    return null
                }
                
                val ctx = createContext()
                return if (ctx != 0L) Connection(ctx) else null
            }
        }
        
        var serverHost: String = ""
        var serverPort: Int = 443
        var username: String = ""
        var password: String = ""
        var insecure: Boolean = false
        
        fun connect(): Boolean {
            setServer(context, serverHost, serverPort)
            setCredentials(context, username, password)
            setInsecure(context, insecure)
            
            val result = connect(context)
            return result == 0
        }
        
        fun getIpAddress(): String? = getVpnIpAddress(context)
        fun getMtu(): Int = getVpnMtu(context)
        fun isDtlsActive(): Boolean = isDtlsConnected(context)
        fun getDtlsCipher(): String? = getDtlsCipherSuite(context)
        
        fun setTunDescriptor(fd: Int): Boolean = setTunFd(context, fd) == 0
        
        fun send(data: ByteArray): Int = sendData(context, data, 0, data.size)
        fun receive(buffer: ByteArray): Int = receiveData(context, buffer, 0, buffer.size)
        
        fun disconnect() {
            disconnect(context)
        }
        
        fun close() {
            disconnect(context)
            destroyContext(context)
        }
    }
}

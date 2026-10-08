package com.vpnapp.vpn.openconnect

import android.util.Log
import org.bouncycastle.tls.*
import org.bouncycastle.tls.crypto.TlsSecret
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom

/**
 * DTLS Client for OpenConnect/AnyConnect VPN
 * Uses session resumption with master secret from TLS connection
 * 
 * The OpenConnect DTLS protocol works as follows:
 * 1. Client establishes TLS connection and extracts master secret
 * 2. Server provides X-DTLS-App-ID (session ID) and X-DTLS-Port
 * 3. Client uses session resumption with the master secret to establish DTLS
 */
class DTLSClient(
    private val serverAddress: String,
    private val serverPort: Int,
    private val sessionId: ByteArray,
    private val masterSecret: ByteArray? = null
) {
    companion object {
        private const val TAG = "DTLSClient"
        private const val BUFFER_SIZE = 65535
        private const val RECEIVE_TIMEOUT_MS = 10000
    }
    
    private var socket: DatagramSocket? = null
    private var dtlsTransport: DatagramTransport? = null
    private var isConnected = false
    
    interface DTLSCallback {
        fun onConnected()
        fun onDataReceived(data: ByteArray)
        fun onError(message: String)
        fun onDisconnected()
    }
    
    private var callback: DTLSCallback? = null
    
    /**
     * Connect to the DTLS server using session resumption
     */
    fun connect(cb: DTLSCallback): Boolean {
        callback = cb
        
        try {
            Log.d(TAG, "=== DTLS Connection Attempt ===")
            Log.d(TAG, "Server: $serverAddress:$serverPort")
            Log.d(TAG, "Session ID: ${sessionId.size} bytes (${sessionId.take(8).joinToString("") { "%02x".format(it) }}...)")
            Log.d(TAG, "Master Secret: ${masterSecret?.size ?: 0} bytes")
            
            // Resolve address first
            val address = InetAddress.getByName(serverAddress)
            Log.d(TAG, "Resolved: ${address.hostAddress}")
            
            // Create UDP socket and connect
            socket = DatagramSocket().apply {
                soTimeout = RECEIVE_TIMEOUT_MS
                // Connect establishes the default destination
                connect(address, serverPort)
            }
            Log.d(TAG, "UDP socket created and connected to ${address.hostAddress}:$serverPort")
            
            // Create DTLS transport wrapper  
            val udpTransport = UDPTransport(socket!!, BUFFER_SIZE)
            
            // Create DTLS client
            val crypto = BcTlsCrypto(SecureRandom())
            val protocol = DTLSClientProtocol()
            
            // For legacy DTLS resumption, we use DefaultTlsClient with session resumption
            // The master secret from TLS session is used to resume the DTLS session
            // This matches what the official OpenConnect app does
            val resumeMasterSecret = masterSecret ?: ByteArray(48) // TLS master secret is 48 bytes
            
            Log.d(TAG, "Using LEGACY DTLS Session Resumption")
            Log.d(TAG, "Master Secret: ${resumeMasterSecret.size} bytes")
            Log.d(TAG, "Session ID: ${sessionId.size} bytes")
            Log.d(TAG, "Session ID HEX: ${sessionId.joinToString("") { "%02x".format(it) }}")
            
            val tlsClient = object : DefaultTlsClient(crypto) {
                
                override fun getSupportedCipherSuites(): IntArray {
                    // Legacy DTLS resumption uses ECDHE-RSA or RSA ciphers (not PSK)
                    val cipherSuites = intArrayOf(
                        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,  // Primary
                        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,  // Fallback
                        CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,         // RSA fallback
                        CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256          // RSA 128
                    )
                    Log.d(TAG, "Client offering ${cipherSuites.size} cipher suites (ECDHE-RSA Priority)")
                    cipherSuites.forEach { Log.d(TAG, "  Cipher: $it") }
                    return cipherSuites
                }
                
                override fun getSupportedVersions(): Array<ProtocolVersion> {
                    val versions = arrayOf(ProtocolVersion.DTLSv12, ProtocolVersion.DTLSv10)
                    Log.d(TAG, "Client allowing protocol versions: ${versions.joinToString()}")
                    return versions
                }
                
                override fun getSessionToResume(): TlsSession? {
                    // Return a TlsSession for session resumption
                    // The session ID is the X-DTLS-Session-ID from server
                    // The master secret is from the TLS connection
                    Log.d(TAG, "Providing session resumption with master secret (${resumeMasterSecret.size} bytes)")
                    
                    return object : TlsSession {
                        override fun getSessionID(): ByteArray = sessionId
                        override fun invalidate() {}
                        override fun isResumable(): Boolean = true
                        override fun exportSessionParameters(): SessionParameters? {
                            // Build SessionParameters with the TLS master secret for resumption
                            val tlsSecret = crypto.createSecret(resumeMasterSecret)
                            return SessionParameters.Builder()
                                .setCipherSuite(CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384)
                                .setMasterSecret(tlsSecret)
                                .setNegotiatedVersion(ProtocolVersion.DTLSv12)
                                .build()
                        }
                    }
                }
                
                override fun getAuthentication(): TlsAuthentication {
                    // For session resumption, we still need to handle cert auth
                    return object : TlsAuthentication {
                        override fun notifyServerCertificate(serverCertificate: TlsServerCertificate) {
                            // Accept all certificates (VPN client trusts server)
                            Log.d(TAG, "Server certificate received (chain length: ${serverCertificate.certificate.length})")
                        }
                        
                        override fun getClientCredentials(certificateRequest: CertificateRequest): TlsCredentials? {
                            // No client cert required
                            return null
                        }
                    }
                }
                
                override fun notifyAlertRaised(alertLevel: Short, alertDescription: Short, message: String?, cause: Throwable?) {
                    val level = if (alertLevel == AlertLevel.fatal) "FATAL" else "WARNING"
                    val desc = AlertDescription.getName(alertDescription)
                    Log.w(TAG, "DTLS Alert SENT: $level - $desc ($message)")
                    if (cause != null) Log.w(TAG, "Alert Cause: ", cause)
                    super.notifyAlertRaised(alertLevel, alertDescription, message, cause)
                }
                
                override fun notifyAlertReceived(alertLevel: Short, alertDescription: Short) {
                    val level = if (alertLevel == AlertLevel.fatal) "FATAL" else "WARNING"
                    val desc = AlertDescription.getName(alertDescription)
                    Log.w(TAG, "DTLS Alert RECEIVED: $level - $desc")
                    super.notifyAlertReceived(alertLevel, alertDescription)
                }
                
                override fun notifyServerVersion(serverVersion: ProtocolVersion?) {
                    Log.d(TAG, "Server agreed to version: $serverVersion")
                    super.notifyServerVersion(serverVersion)
                }
                
                override fun notifySelectedCipherSuite(selectedCipherSuite: Int) {
                    Log.d(TAG, "Cipher suite selected: $selectedCipherSuite")
                    super.notifySelectedCipherSuite(selectedCipherSuite)
                }
                
                override fun notifyHandshakeComplete() {
                    Log.d(TAG, "=== DTLS Handshake COMPLETED ===")
                    super.notifyHandshakeComplete()
                }
            }
            
            // Perform DTLS handshake
            Log.d(TAG, "Starting DTLS handshake...")
            dtlsTransport = protocol.connect(tlsClient, udpTransport)
            
            isConnected = true
            Log.d(TAG, "DTLS CONNECTED SUCCESSFULLY!")
            cb.onConnected()
            
            return true
            
        } catch (e: TlsFatalAlert) {
            val desc = AlertDescription.getText(e.alertDescription)
            Log.e(TAG, "DTLS Fatal Alert: ${e.alertDescription} ($desc)", e)
            cb.onError("DTLS Alert: $desc")
            disconnect()
            return false
        } catch (e: IOException) {
            Log.e(TAG, "DTLS IO Error: ${e.message}", e)
            cb.onError("DTLS IO: ${e.message}")
            disconnect()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "DTLS Connection failed: ${e.javaClass.simpleName}: ${e.message}", e)
            cb.onError("DTLS: ${e.message}")
            disconnect()
            return false
        }
    }
    
    /**
     * Send data packet over DTLS
     */
    fun send(data: ByteArray): Boolean {
        if (!isConnected || dtlsTransport == null) return false
        
        return try {
            dtlsTransport!!.send(data, 0, data.size)
            true
        } catch (e: Exception) {
            Log.e(TAG, "DTLS send error", e)
            false
        }
    }
    
    /**
     * Receive data packet from DTLS
     */
    fun receive(): ByteArray? {
        if (!isConnected || dtlsTransport == null) return null
        
        val buffer = ByteArray(BUFFER_SIZE)
        return try {
            val len = dtlsTransport!!.receive(buffer, 0, buffer.size, RECEIVE_TIMEOUT_MS)
            if (len > 0) {
                buffer.copyOf(len)
            } else {
                null
            }
        } catch (e: Exception) {
            if (isConnected) {
                Log.e(TAG, "DTLS receive error", e)
            }
            null
        }
    }
    
    fun disconnect() {
        isConnected = false
        try { dtlsTransport?.close() } catch (e: Exception) {}
        try { socket?.close() } catch (e: Exception) {}
        dtlsTransport = null
        socket = null
        callback?.onDisconnected()
    }
    
    fun isConnected() = isConnected
    
    /**
     * UDP Transport wrapper for BouncyCastle DTLS
     */
    /**
     * UDP transport wrapper with Session ID injection for ClientHello
     * 
     * OpenConnect requires the X-DTLS-App-ID in the ClientHello Session ID field.
     * BouncyCastle's PSKTlsClient doesn't populate this, so we inject it directly.
     */
    private inner class UDPTransport(
        private val socket: DatagramSocket,
        private val mtu: Int
    ) : DatagramTransport {
        
        override fun getReceiveLimit(): Int = mtu
        override fun getSendLimit(): Int = mtu
        
        override fun receive(buf: ByteArray, off: Int, len: Int, waitMillis: Int): Int {
            socket.soTimeout = waitMillis
            val packet = DatagramPacket(buf, off, len)
            return try {
                socket.receive(packet)
                Log.d("UDPTransport", "Received ${packet.length} bytes")
                packet.length
            } catch (e: IOException) {
                if (e.message?.contains("timed out", ignoreCase = true) == true ||
                    e.message?.contains("SocketTimeoutException", ignoreCase = true) == true) {
                    Log.d("UDPTransport", "Receive timeout")
                    -1 // Timeout - return -1 to signal no data
                } else {
                    Log.e("UDPTransport", "Receive error: ${e.message}")
                    throw e
                }
            }
        }
        
        private var packetCount = 0
        
        override fun send(buf: ByteArray, off: Int, len: Int) {
            packetCount++
            
            // CRITICAL: Inject Session ID into first ClientHello packet
            val dataToSend = if (packetCount == 1 && len > 60) {
                injectSessionIdIntoClientHello(buf, off, len)
            } else {
                buf.copyOfRange(off, off + len)
            }
            
            val packet = DatagramPacket(dataToSend, 0, dataToSend.size)
            socket.send(packet)
            Log.d("UDPTransport", "Sent ${dataToSend.size} bytes (Packet #$packetCount)")
            
            // Debug dump
            if (packetCount == 1) {
                val hexDump = dataToSend.take(100).joinToString(" ") { "%02X".format(it) }
                Log.d("UDPTransport", "ClientHello First 100 bytes: $hexDump")
                
                // Verify Session ID was injected
                if (dataToSend.size > 92) {
                    val sessionIdLen = dataToSend[59].toInt() and 0xFF
                    Log.d("UDPTransport", "SessionID Length in PATCHED packet: $sessionIdLen bytes")
                    if (sessionIdLen > 0) {
                        val sessionIdHex = dataToSend.slice(60 until 60 + minOf(sessionIdLen, 32))
                            .joinToString("") { "%02x".format(it) }
                        Log.d("UDPTransport", "SessionID in PATCHED packet: $sessionIdHex")
                    }
                }
            }
        }
        
        /**
         * Injects the App-ID (sessionId) into the ClientHello packet's Session ID field.
         * 
         * DTLS ClientHello structure:
         * - Offset 0-12: DTLS Record Layer (ContentType, Version, Epoch, SeqNo, Length)
         * - Offset 13-24: Handshake Header (Type, Length, MsgSeq, FragOff, FragLen)
         * - Offset 25-26: Client Version
         * - Offset 27-58: Client Random (32 bytes)
         * - Offset 59: Session ID Length (1 byte) - CURRENTLY 0, WE NEED TO CHANGE THIS
         * - Offset 60+: Session ID (variable, currently empty)
         * - Then: Cookie Length, Cookie, CipherSuites, etc.
         */
        private fun injectSessionIdIntoClientHello(buf: ByteArray, off: Int, len: Int): ByteArray {
            Log.d("UDPTransport", "=== Injecting Session ID into ClientHello ===")
            Log.d("UDPTransport", "Original packet length: $len")
            Log.d("UDPTransport", "Session ID to inject: ${sessionId.size} bytes")
            
            // Create new buffer with space for 32-byte Session ID
            val originalSessionIdLen = buf[off + 59].toInt() and 0xFF
            val newSessionIdLen = sessionId.size  // Should be 32
            val sizeDelta = newSessionIdLen - originalSessionIdLen
            
            val newBuf = ByteArray(len + sizeDelta)
            
            // Copy header up to and including Client Random (offset 0-58)
            System.arraycopy(buf, off, newBuf, 0, 59)
            
            // Set new Session ID length
            newBuf[59] = newSessionIdLen.toByte()
            
            // Insert Session ID
            System.arraycopy(sessionId, 0, newBuf, 60, newSessionIdLen)
            
            // Copy remaining data (cipher suites, extensions, etc.)
            val remainingOffset = off + 59 + 1 + originalSessionIdLen  // After old session ID
            val remainingLen = len - (59 + 1 + originalSessionIdLen)
            System.arraycopy(buf, remainingOffset, newBuf, 60 + newSessionIdLen, remainingLen)
            
            // Update length fields in DTLS Record Layer and Handshake Header
            // Record Length at offset 11-12 (2 bytes, big-endian)
            val oldRecordLen = ((buf[off + 11].toInt() and 0xFF) shl 8) or (buf[off + 12].toInt() and 0xFF)
            val newRecordLen = oldRecordLen + sizeDelta
            newBuf[11] = ((newRecordLen shr 8) and 0xFF).toByte()
            newBuf[12] = (newRecordLen and 0xFF).toByte()
            
            // Handshake Length at offset 14-16 (3 bytes, big-endian)
            val oldHsLen = ((buf[off + 14].toInt() and 0xFF) shl 16) or 
                           ((buf[off + 15].toInt() and 0xFF) shl 8) or 
                           (buf[off + 16].toInt() and 0xFF)
            val newHsLen = oldHsLen + sizeDelta
            newBuf[14] = ((newHsLen shr 16) and 0xFF).toByte()
            newBuf[15] = ((newHsLen shr 8) and 0xFF).toByte()
            newBuf[16] = (newHsLen and 0xFF).toByte()
            
            // Fragment Length at offset 22-24 (3 bytes, big-endian)
            val oldFragLen = ((buf[off + 22].toInt() and 0xFF) shl 16) or 
                             ((buf[off + 23].toInt() and 0xFF) shl 8) or 
                             (buf[off + 24].toInt() and 0xFF)
            val newFragLen = oldFragLen + sizeDelta
            newBuf[22] = ((newFragLen shr 16) and 0xFF).toByte()
            newBuf[23] = ((newFragLen shr 8) and 0xFF).toByte()
            newBuf[24] = (newFragLen and 0xFF).toByte()
            
            Log.d("UDPTransport", "Patched packet length: ${newBuf.size}")
            Log.d("UDPTransport", "Record Length: $oldRecordLen -> $newRecordLen")
            Log.d("UDPTransport", "Handshake Length: $oldHsLen -> $newHsLen")
            Log.d("UDPTransport", "Fragment Length: $oldFragLen -> $newFragLen")
            
            return newBuf
        }
        
        override fun close() {
            socket.close()
        }
    }
}

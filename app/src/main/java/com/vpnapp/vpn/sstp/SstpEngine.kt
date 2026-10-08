package com.vpnapp.vpn.sstp

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.io.PushbackInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * SSTP Client Engine with built-in PPP Stack (LCP, PAP, IPCP)
 */
class SstpEngine(
    private val serverAddress: String,
    private val serverPort: Int,
    private val username: String,
    private val password: String,
    private val skipCertificateVerification: Boolean = true,
    private val bufferSize: Int = 2 * 1024 * 1024,
    private val tcpNoDelay: Boolean = true,
    private val requestedMtu: Int = 1400
) {
    companion object {
        private const val TAG = "SstpEngine"
        private const val CONNECT_TIMEOUT = 30000
        private const val READ_TIMEOUT = 0 // Infinite for loop
        
        // HTTP request for SSTP
        private fun createHttpRequest(host: String): String {
            return "SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1\r\n" +
                   "Host: $host\r\n" +
                   "SSTPCORRELATIONID: {${java.util.UUID.randomUUID()}}\r\n" +
                   "Content-Length: 18446744073709551615\r\n" +
                   "\r\n"
        }
    }

    private var socket: Socket? = null
    private var sslSocket: SSLSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private val writeMutex = Mutex()
    
    private val _state = MutableStateFlow(SstpState.DISCONNECTED)
    val state: StateFlow<SstpState> = _state
    
    private val isRunning = AtomicBoolean(false)
    private val lcpId = AtomicInteger(1)
    private val papId = AtomicInteger(1)
    private val ipcpId = AtomicInteger(1)
    
    // IP Negotiation State
    private var currentIpRequest = "0.0.0.0"
    private var currentDnsRequest = "0.0.0.0"
    private var requestDns = true
    private var bindingRequest: SstpCryptoBinding.Request? = null
    private var localLcpAck = false
    private var peerLcpAck = false
    private var authenticationStarted = false
    private var authenticated = false
    private var peerIpcpAck = false
    private var localIpcpAck = false
    private var notifiedConnected = false
    var tunnelMtu: Int = requestedMtu
        private set

    // Callback reference
    private var callback: SstpCallback? = null
    private var connectionScope: CoroutineScope? = null

    enum class SstpState {
        DISCONNECTED, CONNECTING, HTTP_HANDSHAKE, SSTP_HANDSHAKE, LCP_NEGOTIATION, AUTHENTICATING, IPCP_NEGOTIATION, CONNECTED, RECONNECTING, ERROR
    }

    interface SstpCallback {
        fun onLog(message: String)
        fun onError(message: String)
        fun onConnected(privateIp: String, serverIp: String, dnsServers: List<String>)
        fun onDataPacket(data: ByteArray)
        fun onDisconnected()
    }

    /**
     * Connects to the SSTP server and runs the protocol loop.
     * This function blocks until disconnect or error.
     */
    suspend fun connect(cb: SstpCallback, protect: (Socket) -> Boolean = { true }): Boolean = withContext(Dispatchers.IO) {
        callback = cb
        connectionScope = this
        isRunning.set(true)
        
        try {
            _state.value = SstpState.CONNECTING
            cb.onLog("Connecting to $serverAddress:$serverPort...")

            // 1. Create SSL Socket
            val socketFactory = if (skipCertificateVerification) createTrustAllSocketFactory() else SSLSocketFactory.getDefault() as SSLSocketFactory
            val sock = Socket()
            socket = sock // Disconnect must also close sockets during a pending handshake.
            
            // Protect socket before connecting to avoid routing loops
            if (!protect(sock)) {
                cb.onLog("Warning: Could not protect SSTP socket")
            }
            
            sock.connect(InetSocketAddress(serverAddress, serverPort), CONNECT_TIMEOUT)
            sock.soTimeout = 30000 // 30 seconds read timeout
            sock.tcpNoDelay = tcpNoDelay
            sock.receiveBufferSize = bufferSize
            sock.sendBufferSize = bufferSize
            socket = sock
            
            val sslSock = socketFactory.createSocket(sock, serverAddress, serverPort, true) as SSLSocket
            sslSocket = sslSock
            sslSock.useClientMode = true
            if (!skipCertificateVerification) sslSock.sslParameters = sslSock.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            sslSock.startHandshake()
            sslSocket = sslSock
            
            
            // Wrap input stream to support pushback (crucial for not losing SSTP packets after HTTP body)
            val pbis = PushbackInputStream(BufferedInputStream(sslSock.inputStream, bufferSize), 8192)
            inputStream = pbis
            outputStream = java.io.BufferedOutputStream(sslSock.outputStream, bufferSize)
            
            cb.onLog("SSL Handshake complete. Buffer: ${bufferSize/1024}KB, TCP_NODELAY: $tcpNoDelay")

            // 2. HTTP Handshake
            _state.value = SstpState.HTTP_HANDSHAKE
            if (!performHttpHandshake(cb)) throw Exception("HTTP Handshake failed")

            // 3. SSTP Handshake
            _state.value = SstpState.SSTP_HANDSHAKE
            if (!performSstpHandshake(cb)) throw Exception("SSTP Handshake failed")

            // 4. Start Read Loop (Handles PPP)
            cb.onLog("Starting PPP Negotiation...")
            _state.value = SstpState.LCP_NEGOTIATION
            
            // Initiate LCP
            sendLcpConfReq()
            
            val readBuffer = ByteArray(16384)
            while (isRunning.get()) {
                // Read SSTP Packet Header
                val headerBytes = readExactly(4)
                if (headerBytes == null) {
                    cb.onLog("SSTP Read Loop: EOF reading header. Closing.")
                    break
                }
                val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
                val version = buffer.get()
                val control = buffer.get()
                val length = buffer.short.toInt() and 0xFFFF
                
                if (length < 4) throw Exception("Invalid SSTP Packet Length: $length")
                
                // Read Payload
                val payloadBytes = readExactly(length - 4)
                if (payloadBytes == null) {
                    cb.onLog("SSTP Read Loop: EOF reading payload. Closing.")
                    break
                }
                
                val isControl = (control.toInt() and 0x01) == 1
                if (isControl) {
                    processControlPacket(payloadBytes, cb)
                } else {
                    processDataPacket(payloadBytes, cb)
                }
            }
            
            return@withContext true
            
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "Connection Error", e)
                cb.onError("Connection Error: ${e.message}")
            }
            return@withContext false
        } finally {
            cb.onLog("SSTP Connection Loop Exiting.")
            disconnect()
        }
    }
    
    // --- Handshake Helpers ---

    private fun performHttpHandshake(cb: SstpCallback): Boolean {
        try {
            val req = createHttpRequest(serverAddress)
            outputStream?.write(req.toByteArray(Charsets.US_ASCII))
            outputStream?.flush()
            
            val pbin = inputStream as? PushbackInputStream ?: return false
            val buffer = ByteArray(8192)
            var totalRead = 0
            
            // Read byte-by-byte (buffered) until \r\n\r\n
            while (totalRead < buffer.size) {
                 val c = pbin.read()
                 if (c == -1) break
                 buffer[totalRead++] = c.toByte()
                 
                 // Check for \r\n\r\n (end of headers)
                 if (totalRead >= 4) {
                     if (buffer[totalRead-4] == 0x0D.toByte() &&
                         buffer[totalRead-3] == 0x0A.toByte() &&
                         buffer[totalRead-2] == 0x0D.toByte() &&
                         buffer[totalRead-1] == 0x0A.toByte()) {
                             
                         // Header Found!
                         val response = String(buffer, 0, totalRead, Charsets.US_ASCII)
                         cb.onLog("HTTP Response: ${response.substringBefore("\r\n")}")
                         return response.contains("200")
                     }
                 }
            }
            return false 
        } catch (e: Exception) {
            return false
        }
    }

    private suspend fun performSstpHandshake(cb: SstpCallback): Boolean {
        // Send Call Connect Request
        val req = createConnectRequest()
        sendControlMessage(req)
        
        // Read First Packet (Expect Ack, but handle Data)
        val header = readExactly(4) ?: return false
        val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val version = buf.get()
        val control = buf.get()
        val len = buf.short.toInt() and 0xFFFF
        
        val isControl = (control.toInt() and 0x01) == 1
        
        require(len in 8..4095 && version == 0x10.toByte()) { "Invalid SSTP handshake header" }
        val payload = readExactly(len - 4) ?: return false
        
        if (isControl) {
            val msgBuf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            val msgType = msgBuf.short
            
            if (msgType == SstpMessageType.SSTP_MSG_CALL_CONNECT_ACK) {
                bindingRequest = SstpCryptoBinding.parseRequest(payload)
                cb.onLog("SSTP Connect Ack Received.")
                return true
            }
            cb.onLog("Unexpected SSTP Handshake message: $msgType")
            return false
        } else {
             // Received Data Packet instead of Control!
             // Server skipped Ack phase. Accept and process.
             cb.onLog("Received Data Packet during Handshake (Ack skipped). Processing...")
             processDataPacket(payload, cb)
             return true
        }
    }
    
    // --- Processing Loop ---

    private suspend fun processControlPacket(payload: ByteArray, cb: SstpCallback) {
        if (payload.size < 2) return
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val msgType = buf.short
        
        when (msgType) {
            SstpMessageType.SSTP_MSG_CALL_ABORT -> {
                cb.onError("SSTP server aborted the session")
                disconnect()
            }
            SstpMessageType.SSTP_MSG_CALL_CONNECTED -> cb.onLog("SSTP Session Connected.")
            SstpMessageType.SSTP_MSG_CALL_DISCONNECT -> {
                cb.onLog("Server requested disconnect.")
                disconnect()
            }
            SstpMessageType.SSTP_MSG_ECHO_REQUEST -> {
                // Send Echo Response
                val response = SstpControlMessage(SstpMessageType.SSTP_MSG_ECHO_RESPONSE)
                sendControlMessage(response)
            }
        }
    }

    private suspend fun processDataPacket(payload: ByteArray, cb: SstpCallback) {
        if (payload.size < 2) return
        var offset = 0
        
        // Handle HDLC Address/Control field (FF 03) if present
        if (payload.size >= 2 && payload[0] == 0xFF.toByte() && payload[1] == 0x03.toByte()) {
            offset = 2
        }
        
        if (payload.size < offset + 2) {
            cb.onLog("Data Packet too short. Size=${payload.size}, Offset=$offset")
            return
        }
        
        val buf = ByteBuffer.wrap(payload, offset, payload.size - offset).order(ByteOrder.BIG_ENDIAN)
        val protocol = buf.short
        
        // Log Protocol for debugging why LCP isn't handled
        // Filter out IP (0021) to avoid spam if connected? No, we are not connected yet.
        if (protocol != PppProtocolId.IP) {
             cb.onLog("RX PPP: Proto=0x${"%04X".format(protocol)} Len=${payload.size}")
        }
        
        // Payload for PPP starts after protocol (2 bytes)
        val pppData = ByteArray(payload.size - offset - 2)
        if (pppData.isNotEmpty()) {
             System.arraycopy(payload, offset + 2, pppData, 0, pppData.size)
        }
        
        when (protocol) {
            PppProtocolId.LCP -> processLcp(pppData, cb)
            PppProtocolId.PAP -> processPap(pppData, cb)
            PppProtocolId.IPCP -> processIpcp(pppData, cb)
            PppProtocolId.IP -> cb.onDataPacket(pppData) // Forward IP packet
            else -> {
                // Ignore other protocols (IPv6 0x0057, etc)
            }
        }
    }
    
    // --- PPP Processors ---

    private suspend fun processLcp(data: ByteArray, cb: SstpCallback) {
        if (data.size < 4) return
        val code = data[0]
        val id = data[1]
        val len = ByteBuffer.wrap(data, 2, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        require(len in 4..data.size) { "Invalid PPP LCP length" }
        
        cb.onLog("LCP RX: Code=${code.toInt()} ID=${id.toInt()} Len=$len")
        
        // Extract Options
        val optionsData = ByteArray(len - 4)
        if (optionsData.size > 0 && data.size >= len) {
            System.arraycopy(data, 4, optionsData, 0, optionsData.size)
        }
        
        when (code.toByte()) {
            PppCode.CONF_REQ -> {
                cb.onLog("Handling CONF_REQ ID=${id.toInt()}. OptsLen=${optionsData.size}")
                val optsHex = optionsData.joinToString("") { "%02X".format(it) }
                cb.onLog("RX Options: $optsHex")
                
                // Parse Options to check validation
                var nakOptions = ByteArray(0)
                
                var i = 0
                while (i < optionsData.size) {
                    if (i + 1 >= optionsData.size) break
                    val type = optionsData[i]
                    val len = optionsData[i+1].toInt()
                    if (len < 2 || i + len > optionsData.size) break
                    
                    val optVal = if (len > 2) optionsData.sliceArray(i+2 until i+len) else ByteArray(0)
                    if (type == LcpOptionType.MRU && optVal.size == 2) {
                        val mru = ((optVal[0].toInt() and 255) shl 8) or (optVal[1].toInt() and 255)
                        require(mru >= 128) { "Invalid peer MRU" }
                        tunnelMtu = minOf(requestedMtu, mru)
                    }
                    
                    // Check Auth Protocol (Type 3)
                    if (type == 3.toByte()) {
                        // Value is 2 bytes Protocol
                        if (optVal.size >= 2) {
                            val authProto = ByteBuffer.wrap(optVal).short
                            // If Server requests CHAP (0xC223) or anything not PAP (0xC023)
                            if (authProto != 0xC023.toShort()) {
                                cb.onLog("Server requested Auth 0x%04X. NAKing requesting PAP.".format(authProto))
                                // Construct NAK option: Type 3, Len 4, Val C023
                                val nakOpt = ByteArray(4)
                                nakOpt[0] = 3
                                nakOpt[1] = 4
                                nakOpt[2] = 0xC0.toByte()
                                nakOpt[3] = 0x23.toByte()
                                nakOptions = nakOpt // Only send this preference
                            }
                        }
                    }
                    
                    i += len
                }
                
                if (nakOptions.isNotEmpty()) {
                    val nak = createConfRequests(PppProtocolId.LCP, id, nakOptions, PppCode.CONF_NAK)
                    sendRawPpp(nak)
                    outputStream?.flush()
                    cb.onLog("Sent CONF_NAK (prefer PAP) for ID=${id.toInt()}")
                } else {
                    // Accept everything
                    val ack = createConfRequests(PppProtocolId.LCP, id, optionsData, PppCode.CONF_ACK)
                    sendRawPpp(ack)
                    outputStream?.flush() // Flush ACK immediately
                    cb.onLog("Sent CONF_ACK for ID=${id.toInt()}")
                    peerLcpAck = true
                    maybeAuthenticate()
                }
            }
            PppCode.CONF_ACK -> {
                cb.onLog("LCP Negotiated.")
                localLcpAck = true
                maybeAuthenticate()
            }
            PppCode.CONF_NAK, PppCode.CONF_REJ -> {
                // Server didn't like our options. Resend simplified (empty options).
                sendLcpConfReq(empty = true)
            }
            PppCode.ECHO_REQ -> {
                // Send Echo Reply with same ID and payload
                val reply = ByteArray(data.size)
                System.arraycopy(data, 0, reply, 0, data.size)
                reply[0] = PppCode.ECHO_REP
                // Recalculate magic number? Just echo payload.
                sendPayload(PppProtocolId.LCP, reply)
            }
        }
    }
    
    private suspend fun processPap(data: ByteArray, cb: SstpCallback) {
         if (data.size < 4) return
         val code = data[0]
         when (code) {
             2.toByte() -> { // Authenticate-Ack
                 if (authenticated) return
                 authenticated = true
                 cb.onLog("PAP Authentication Success.")
                 val request = checkNotNull(bindingRequest) { "SSTP crypto binding is missing" }
                 val certificate = checkNotNull(sslSocket).session.peerCertificates.first().encoded
                 writePacket(SstpCryptoBinding.connected(request, certificate))
                 cb.onLog("SSTP crypto binding sent (${if (request.hashProtocol == 2) "SHA256" else "SHA1"}).")
                 _state.value = SstpState.IPCP_NEGOTIATION
                 sendIpcpConfReq("0.0.0.0") 
             }
             3.toByte() -> { // Authenticate-Nak
                 cb.onError("PAP Authentication Failed.")
                 disconnect()
             }
         }
    }
    
    private suspend fun processIpcp(data: ByteArray, cb: SstpCallback) {
        if (data.size < 4) return
        val code = data[0]
        val id = data[1]
        val len = ByteBuffer.wrap(data, 2, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        require(len in 4..data.size) { "Invalid PPP IPCP length" }
        val optionsData = ByteArray(maxOf(0, len - 4))
        if (data.size >= len) {
            System.arraycopy(data, 4, optionsData, 0, optionsData.size)
        }
        
        when (code) {
            PppCode.CONF_REQ -> {
                // Server asking us to accept its IP/DNS?
                // Usually server pushes IP to us via NAK to our request.
                // But server also sends its own Req for its end. We simply Ack it.
                val ack = createConfRequests(PppProtocolId.IPCP, id, optionsData, PppCode.CONF_ACK)
                sendRawPpp(ack)
                peerIpcpAck = true
                notifyConnected(cb)
            }
            PppCode.CONF_NAK -> {
                // Server suggests IP!
                val newIp = extractIpFromOptions(optionsData, IpcpOptionType.IP_ADDRESS)
                if (newIp != null) {
                    currentIpRequest = newIp
                    cb.onLog("Server assigned IP: $newIp")
                }
                
                // Also check DNS
                val newDns = extractIpFromOptions(optionsData, IpcpOptionType.PRIMARY_DNS)
                if (newDns != null) {
                    currentDnsRequest = newDns
                }
                sendIpcpConfReq(currentIpRequest)
            }
            PppCode.CONF_REJ -> {
                if (extractIpFromOptions(optionsData, IpcpOptionType.PRIMARY_DNS) != null) requestDns = false
                sendIpcpConfReq(currentIpRequest)
            }
            PppCode.CONF_ACK -> {
                // Server accepted our IP
                cb.onLog("IPCP Negotiated. IP: $currentIpRequest")
                localIpcpAck = true
                notifyConnected(cb)
            }
        }
    }
    
    // --- Sender Helpers ---
    private suspend fun maybeAuthenticate() {
        if (localLcpAck && peerLcpAck && !authenticationStarted) {
            authenticationStarted = true
            _state.value = SstpState.AUTHENTICATING
            sendPapReq(username, password)
        }
    }

    private fun notifyConnected(cb: SstpCallback) {
        if (!localIpcpAck || !peerIpcpAck || notifiedConnected || currentIpRequest == "0.0.0.0") return
        notifiedConnected = true
        _state.value = SstpState.CONNECTED
        socket?.soTimeout = READ_TIMEOUT
        cb.onConnected(currentIpRequest, socket?.inetAddress?.hostAddress ?: serverAddress,
            if (currentDnsRequest != "0.0.0.0") listOf(currentDnsRequest) else listOf("8.8.8.8"))
    }
    
    private suspend fun sendLcpConfReq(empty: Boolean = false) {
        callback?.onLog("Sending LCP Conf Req (Empty=$empty)")
        val options = if (empty) ByteArray(0) else byteArrayOf(
            LcpOptionType.MRU, 0x04, (requestedMtu shr 8).toByte(), requestedMtu.toByte(),
            // Magic Number for loop detection (random)
            LcpOptionType.MAGIC_NUMBER, 0x06, 0x01, 0x02, 0x03, 0x04 
        )
        val packet = createConfRequests(PppProtocolId.LCP, lcpId.getAndIncrement().toByte(), options)
        sendRawPpp(packet)
        outputStream?.flush() // Flush LCP packets immediately
    }
    
    private suspend fun sendPapReq(user: String, pass: String) {
        callback?.onLog("Sending PAP authentication request")
        val uBytes = user.toByteArray(Charsets.US_ASCII)
        val pBytes = pass.toByteArray(Charsets.US_ASCII)
        
        // Code(1) ID(1) Len(2) [ULen U] [PLen P]
        val len = 4 + 1 + uBytes.size + 1 + pBytes.size
        val buf = ByteBuffer.allocate(len)
        buf.put(1.toByte()) // Auth-Req
        buf.put(papId.getAndIncrement().toByte())
        buf.putShort(len.toShort())
        buf.put(uBytes.size.toByte())
        buf.put(uBytes)
        buf.put(pBytes.size.toByte())
        buf.put(pBytes)
        
        sendPayload(PppProtocolId.PAP, buf.array())
    }
    
    private suspend fun sendIpcpConfReq(ip: String) {
        // Option 0x03 (IP) Length 6. Value = 4 bytes IP
        val ipBytes = InetSocketAddress(ip, 0).address.address
        val options = ByteBuffer.allocate(if (requestDns) 12 else 6)
        options.put(IpcpOptionType.IP_ADDRESS)
        options.put(6.toByte())
        options.put(ipBytes)
        if (requestDns) {
            options.put(IpcpOptionType.PRIMARY_DNS).put(6.toByte())
            options.put(java.net.InetAddress.getByName(currentDnsRequest).address)
        }
        
        // Also Request DNS? Option 0x81 (Pri DNS)
        
        val packet = createConfRequests(PppProtocolId.IPCP, ipcpId.getAndIncrement().toByte(), options.array())
        sendRawPpp(packet)
    }
    
    suspend fun sendDataPacket(data: ByteArray) {
        if (!isRunning.get()) return
        sendPayload(PppProtocolId.IP, data)
    }

    private suspend fun sendControlMessage(msg: SstpControlMessage) {
        writePacket(msg.toBytes())
        // Flush immediately after control messages for handshake
        outputStream?.flush()
    }
    
    private suspend fun sendPayload(protocol: Short, data: ByteArray) {
        // Construct PPP Frame: Protocol + Data
        val pppFrame = ByteBuffer.allocate(2 + data.size)
        pppFrame.putShort(protocol)
        pppFrame.put(data)
        
        // Send using raw PPP sender (which adds FF 03 and SSTP Header)
        sendRawPpp(pppFrame.array())
    }
    
    // For raw PPP frame (already has protocol/code inside or created by helper)
    private suspend fun sendRawPpp(data: ByteArray) {
         // Prepend FF 03 (HDLC Address/Control)
         val framedData = ByteArray(data.size + 2)
         framedData[0] = 0xFF.toByte()
         framedData[1] = 0x03.toByte()
         System.arraycopy(data, 0, framedData, 2, data.size)
     
         val totalLen = 4 + framedData.size
         val buf = ByteBuffer.allocate(totalLen)
         buf.order(ByteOrder.BIG_ENDIAN)
         buf.put(0x10.toByte())
         buf.put(0x00.toByte())
         buf.putShort(totalLen.toShort())
         buf.put(framedData)
         writePacket(buf.array())
    }

    private suspend fun writePacket(data: ByteArray) {
        writeMutex.withLock {
            try {
                outputStream?.write(data)
                outputStream?.flush() // Flush every packet to avoid handshake stalls
                // Note: Removed per-packet flush() for better throughput with large buffers
                // BufferedOutputStream will auto-flush when buffer is full
            } catch (e: Exception) {
                if (isRunning.get()) {
                    throw e
                } else {
                    // Ignore error if not running
                }
            }
        }
    }
    
    // --- Utilities ---
    
    private fun readExactly(bytes: Int): ByteArray? {
        if (bytes == 0) return ByteArray(0)
        val buf = ByteArray(bytes)
        var offset = 0
        while (offset < bytes) {
            try {
                val read = inputStream?.read(buf, offset, bytes - offset) ?: -1
                if (read == -1) {
                    val msg = "readExactly: EOF at offset $offset (wanted $bytes)"
                    // Log to system log for deep debugging, stick to callbacks for UI
                    Log.w(TAG, msg)
                    return null
                }
                offset += read
            } catch (e: Exception) {
                Log.w(TAG, "readExactly Exception: ${e.message}")
                return null
            }
        }
        return buf
    }
    
    private fun extractIpFromOptions(options: ByteArray, type: Byte): String? {
        var offset = 0
        while (offset + 1 < options.size) {
            val optType = options[offset]
            val optLen = options[offset+1].toInt() and 0xFF
            if (optLen < 2 || offset + optLen > options.size) break
            
            if (optType == type && optLen == 6) {
                 val ipBytes = ByteArray(4)
                 System.arraycopy(options, offset + 2, ipBytes, 0, 4)
                 return java.net.InetAddress.getByAddress(ipBytes).hostAddress
            }
            offset += optLen
        }
        return null
    }

    private fun createTrustAllSocketFactory(): SSLSocketFactory {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        return sslContext.socketFactory
    }
    
    private fun createConnectRequest(): SstpControlMessage {
        val protocolAttr = SstpAttribute(
            id = SstpAttributeId.SSTP_ATTRIB_ENCAPSULATED_PROTOCOL_ID,
            value = ByteBuffer.allocate(2).putShort(PppProtocolId.LCP).array() // Actually should be PPP (0x0001) for Encap Protocol ID
        )
        // SSTP Spec says Encapsulated Protocol ID = 1 for PPP.
        // PppProtocolId.LCP is 0xC021.
        // Let's use 1.
        val pppIdAttr = SstpAttribute(
             id = SstpAttributeId.SSTP_ATTRIB_ENCAPSULATED_PROTOCOL_ID,
             value = byteArrayOf(0x00, 0x01)
        )
        return SstpControlMessage(
            messageType = SstpMessageType.SSTP_MSG_CALL_CONNECT_REQUEST,
            attributes = listOf(pppIdAttr)
        )
    }
    
    private fun createConfRequests(protocol: Short, id: Byte, options: ByteArray, code: Byte = PppCode.CONF_REQ): ByteArray {
        val len = 4 + options.size
        val buf = ByteBuffer.allocate(2 + len)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putShort(protocol)
        buf.put(code)
        buf.put(id)
        buf.putShort(len.toShort())
        buf.put(options)
        return buf.array()
    }

    fun disconnect() {
        if (isRunning.compareAndSet(true, false)) {
            _state.value = SstpState.DISCONNECTED
            // Close synchronously: a coroutine sharing the blocked read scope may never run.
            runCatching { socket?.close() }
            runCatching { sslSocket?.close() }
            callback?.onDisconnected()
        }
    }
}

package com.vpnapp.vpn.openconnect

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import java.nio.ByteBuffer
import org.conscrypt.Conscrypt

class OpenConnectEngine(
    private val serverAddress: String,
    private val serverPort: Int,
    private val username: String,
    private val password: String,
    private val skipCertificateVerification: Boolean = false,
    private val bufferSize: Int = 2 * 1024 * 1024,
    private val tcpNoDelay: Boolean = true,
    private val disableDtls: Boolean = false,
    private val requestedMtu: Int = 1400
) {

    interface OpenConnectCallback {
        fun onLog(message: String)
        fun onError(message: String)
        fun onConnected(privateIp: String, serverIpAddr: String, dnsServers: List<String>, mtu: Int, splitExcludes: List<String> = emptyList(), splitIncludes: List<String> = emptyList())
        fun onDataPacket(data: ByteArray)
        fun onDisconnected()
    }

    private var socket: Socket? = null
    private var sslSocket: SSLSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var callback: OpenConnectCallback? = null
    
    // DTLS Support
    private var dtlsClient: DTLSClient? = null
    private var useDtls = false
    private var tlsMasterSecret: String = ""  // Hex-encoded master secret for DTLS PSK
    private var derivedPsk: ByteArray? = null // RFC 5705 derived PSK for DTLS
    private var extractionSuccess = false
    
    // Keepalive support
    private var keepaliveThread: Thread? = null
    @Volatile private var isRunning = false
    private var dpdInterval = 30 // seconds - send keepalive every 30s
    
    // AnyConnect/OpenConnect relies on getting a "webvpn" cookie via HTTP/XML forms
    private var webVpnCookie: String? = null
    
    /**
     * Extract TLS master secret from SSL session using reflection
     * This is needed for DTLS PSK (Pre-Shared Key) authentication
     */
    data class TlsSecrets(val masterHex: String, val masterBytes: ByteArray, val clientRandom: ByteArray?, val serverRandom: ByteArray?)

    // Hidden API bypass not needed with Bundled Conscrypt
    // private fun allowHiddenApi() { ... }
    
    private fun createConscryptSocketFactory(): SSLSocketFactory {
        try {
            val provider = Conscrypt.newProvider()
            val context = SSLContext.getInstance("TLS", provider)
            
            // 1. Trust Manager
            val trustManagers = if (skipCertificateVerification) {
                arrayOf<TrustManager>(object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
                })
            } else {
                null // Use default
            }
            
            context.init(null, trustManagers, java.security.SecureRandom())
            callback?.onLog("Conscrypt Init SUCCESS. Provider: ${provider.name}")
            return context.socketFactory
        } catch (e: Throwable) {
            callback?.onLog("Conscrypt Init Failed (Native Error?): ${e.message}, falling back to system")
            e.printStackTrace()
            return SSLSocketFactory.getDefault() as SSLSocketFactory
        }
    }


    private fun extractTlsSecrets(sslSocket: SSLSocket): TlsSecrets {
        try {
            var session: Any = sslSocket.session
            val sessionClass = session.javaClass
            
            // DEBUG: Log class info
            callback?.onLog("Session Class: ${sessionClass.name}")
            
            // DEBUG: Inspect Socket Hierarchy
            callback?.onLog("Socket Class: ${sslSocket.javaClass.name}")
            var sockCls: Class<*>? = sslSocket.javaClass
            while (sockCls != null) {
                callback?.onLog("Hierarchy: ${sockCls.name}")
                 sockCls.declaredFields.forEach { f ->
                     callback?.onLog(" SockField: ${f.name} (${f.type.simpleName})")
                     
                     // Drill into Engine if found
                     if (f.name == "engine" || f.type.simpleName.contains("Engine")) {
                         try {
                              f.isAccessible = true
                              val eng = f.get(sslSocket)
                              if (eng != null) {
                                  callback?.onLog("Found Engine: ${eng.javaClass.name}")
                                  var engCls: Class<*>? = eng.javaClass
                                  while (engCls != null) {
                                      callback?.onLog(" EngHierarchy: ${engCls.name}")
                                      engCls.declaredFields.forEach { ef ->
                                          callback?.onLog(" EngField: ${ef.name} (${ef.type.simpleName})")
                                      }
                                      engCls = engCls.superclass
                                  }
                              }
                         } catch (e: Exception) {
                             callback?.onLog("Error accessing engine: ${e.message}")
                         }
                     }
                 }
                sockCls = sockCls.superclass
            }
            


            // Verify Conscrypt Class Availability
            try {
                val c = Class.forName("org.conscrypt.Conscrypt")
                callback?.onLog("VERIFY: org.conscrypt.Conscrypt is LOADED. Class: ${c.name}")
            } catch (t: Throwable) {
                callback?.onLog("VERIFY: org.conscrypt.Conscrypt NOT FOUND. Error: ${t.message}")
            }

            // 1. Unwrap Conscrypt wrapper if present
            // Iterate unwrapping until we find a session with master_secret or stop
            var currentSession: Any = session
            for (i in 0..5) { // Max depth 5
                 val cls = currentSession.javaClass
                  // Log fields for debugging
                  // callback?.onLog("Inspecting ${cls.simpleName} fields:")
                  
                  // Run ONCE: Dump full hierarchy
                  if (session === currentSession && cls == currentSession.javaClass) {
                      callback?.onLog("Dumping NativeCrypto Methods:")
                      try {
                          val nc = Class.forName("org.conscrypt.NativeCrypto")
                          nc.declaredMethods.filter { it.name.contains("master") || it.name.contains("key") || it.name.contains("session") }.forEach { m ->
                              callback?.onLog("  NativeCrypto.${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
                          }
                      } catch (e: Exception) {
                          callback?.onLog("NativeCrypto dump failed: ${e.message}")
                      }
                  }

                  cls.declaredFields.forEach { f ->
                      callback?.onLog("  ${f.name} (${f.type.simpleName})")
                  }

                 // Try to get master secret directly first (optimization)
                 if (getBytesField(currentSession, listOf("masterSecret", "master_secret", "master_key")) != null) {
                     session = currentSession
                     break
                 }
                 
                  // Try to unwrap wrapper fields
                  var foundWrapper = false
                  for (fieldName in listOf("delegate", "ssl", "nativeSsl", "adapter", "function", "wrapper")) {
                      val wrapperField = getFieldInHierarchy(cls, fieldName)
                      if (wrapperField != null) {
                          val wrapped = wrapperField.get(currentSession)
                          if (wrapped != null) {
                              callback?.onLog("Unwrapped ${cls.simpleName}.$fieldName -> ${wrapped.javaClass.simpleName}")
                              currentSession = wrapped
                              session = currentSession
                              foundWrapper = true
                              break
                          }
                      }
                  }
                  
                  if (!foundWrapper) {
                      break
                  }
             }
            

            
            // 2. Try to get OpenSSL/Native session pointer/parameters
            // Conscrypt usually has 'sslParameters' or similar
            
            var masterSecret: ByteArray? = getBytesField(session, listOf("masterSecret", "master_secret", "master_key"))
            var clientRandom: ByteArray? = getBytesField(session, listOf("clientRandom", "localRandom", "encodedClientHello", "client_random"))
            var serverRandom: ByteArray? = getBytesField(session, listOf("serverRandom", "peerRandom", "encodedServerHello", "server_random"))

            // 3. Fallback: If not found, try to locate 'ssl' or 'nativeSsl' object which holds the session state
            if (masterSecret == null) {
                // Try to check declared fields if we missed any
                session.javaClass.declaredFields.forEach { f ->
                     if (f.type == ByteArray::class.java) {
                         // callback?.onLog("Checking bytes field: ${f.name} size=${(f.get(session) as? ByteArray)?.size}")
                     }
                }
            }

            // 4. Fallback: Try methods (getPeerRandom, getClientRandom) - sometimes hidden or public
            // 5. Public API: Conscrypt exportKeyingMaterial (The correct way for Bundled Conscrypt)
            if (masterSecret == null && sslSocket != null) {
                 try {
                     if (Conscrypt.isConscrypt(sslSocket)) {
                         callback?.onLog("SSLSocket is Conscrypt. Attempting public exportKeyingMaterial...")
                         // CRITICAL: OpenConnect uses EXACTLY this label, lowercase, no context, 32 bytes
                         val label = "EXPORTER-openconnect-psk"
                         // OpenConnect uses 256-bit (32 bytes) PSK, NOT 48 bytes
                         val key = Conscrypt.exportKeyingMaterial(sslSocket, label, null, 32)
                         
                         if (key != null) {
                             masterSecret = key
                             derivedPsk = key
                             callback?.onLog("Conscrypt Public Export SUCCESS! Key: ${key.size} bytes, Label: $label")
                             extractionSuccess = true
                         } else {
                             callback?.onLog("Conscrypt Public Export returned null")
                         }
                     } else {
                         callback?.onLog("SSLSocket is NOT recognized as Conscrypt")
                     }
                 } catch (e: Exception) {
                     callback?.onLog("Conscrypt Public Export Failed: ${e.message}")
                     // Fallback involved?
                 }
            }

            if (masterSecret == null) {
                callback?.onLog("Could not extract master secret, generating random (DTLS will fail)")
                masterSecret = ByteArray(48)
                java.security.SecureRandom().nextBytes(masterSecret)
                extractionSuccess = false
            } else {
                callback?.onLog("MASTER SECRET EXTRACTED SUCCESS: ${masterSecret!!.size} bytes")
                callback?.onLog("Secret Hex: ${masterSecret!!.take(10).joinToString("") { "%02x".format(it) }}...")
                
                // If we got it via JNI export, verify flag
                if (derivedPsk != null && masterSecret === derivedPsk) {
                    callback?.onLog("Using Derived PSK (RFC 5705) as Master Secret substitute.")
                }
                extractionSuccess = true
            }
            
            return TlsSecrets(
                masterHex = masterSecret!!.joinToString("") { "%02x".format(it) },
                masterBytes = masterSecret,
                clientRandom = clientRandom,
                serverRandom = serverRandom
            )
            
        } catch (e: Exception) {
            Log.e("OpenConnectEngine", "Secret extraction error: ${e.message}")
            callback?.onLog("Secret extraction CRASH: ${e.message}")
            val rnd = ByteArray(48)
            java.security.SecureRandom().nextBytes(rnd)
            extractionSuccess = false
            return TlsSecrets(rnd.joinToString("") { "%02x".format(it) }, rnd, null, null)
        }
    }
    
    // RFC 5705 Key Exporter
    private fun calculateRFC5705Exporter(masterSecret: ByteArray, clientRandom: ByteArray, serverRandom: ByteArray): ByteArray {
        try {
            val label = "EXPORTER-openconnect-psk".toByteArray(Charsets.US_ASCII)
            val seed = ByteBuffer.allocate(label.size + clientRandom.size + serverRandom.size)
            seed.put(label)
            seed.put(clientRandom)
            seed.put(serverRandom)
            
            // For TLS 1.2, PRF is P_SHA256
            return prfSha256(masterSecret, seed.array(), 32) // 32 bytes needed
        } catch (e: Exception) {
            Log.e("OpenConnectEngine", "PRF Error", e)
            return ByteArray(0)
        }
    }
    
    private fun prfSha256(secret: ByteArray, seed: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        try {
            val hmac = javax.crypto.Mac.getInstance("HmacSHA256")
            val keySpec = javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256")
            hmac.init(keySpec)
            
            var a = seed
            var offset = 0
            
            while (offset < length) {
                a = hmac.doFinal(a)
                hmac.update(a)
                hmac.update(seed)
                val block = hmac.doFinal()
                
                val copyLen = kotlin.math.min(block.size, length - offset)
                System.arraycopy(block, 0, out, offset, copyLen)
                offset += copyLen
            }
        } catch (e: Exception) { e.printStackTrace() }
        return out
    }



    private fun <T> findFieldRecursively(root: Any?, names: List<String>, type: Class<T>, maxDepth: Int, cb: OpenConnectCallback?): T? {
        if (root == null) return null
        
        val visited = java.util.HashSet<Any>() // Avoid cycles
        val queue = java.util.ArrayDeque<Pair<Any, Int>>()
        queue.add(root to 0)
        
        // Normalize names
        val lowerNames = names.map { it.lowercase() }
        
        while (!queue.isEmpty()) {
            val (currentObj, depth) = queue.removeFirst()
            
            if (visited.contains(currentObj)) continue
            visited.add(currentObj)
            
            if (depth > maxDepth) continue
            
            // Check fields of current object
            var c: Class<*>? = currentObj.javaClass
            while (c != null && c != Object::class.java) {
                // Don't recurse into basic types
                if (c!!.name.startsWith("java.lang") || c!!.isPrimitive) break
                
                try {
                    c!!.declaredFields.forEach { f ->
                        f.isAccessible = true
                        val valObj = f.get(currentObj)
                        
                        // Check if this IS the field we want
                        if (valObj != null && type.isInstance(valObj)) {
                            if (lowerNames.any { f.name.lowercase().contains(it) }) {
                                cb?.onLog("Found matching field: ${f.name} in ${c!!.simpleName} (depth $depth)")
                                @Suppress("UNCHECKED_CAST")
                                return valObj as T
                            }
                        }
                        
                        // Enqueue for recursion if it looks like a container/wrapper
                        if (valObj != null && depth < maxDepth) {
                             if (!valObj.javaClass.isPrimitive && 
                                 !valObj.javaClass.name.startsWith("java.lang") &&
                                 !valObj.javaClass.isArray) {
                                 
                                 // Heuristic: Only follow relevant fields to save time
                                 val fname = f.name.lowercase()
                                 if (fname.contains("ssl") || fname.contains("session") || 
                                     fname.contains("context") || fname.contains("param") || 
                                     fname.contains("delegate") || fname.contains("inner")) {
                                     queue.add(valObj to depth + 1)
                                 }
                             }
                        }
                    }
                } catch (e: Exception) { }
                c = c!!.superclass
            }
        }
        return null
    }

    private fun dumpObjectHierarchy(obj: Any?, depth: Int, cb: OpenConnectCallback?) {
        if (obj == null || depth > 5) return
        val indent = "  ".repeat(depth)
        val cls = obj.javaClass
        cb?.onLog("${indent}DUMP: ${cls.name} (@${System.identityHashCode(obj)})")
        
        // Don't dump primitives/strings/arrays content detailedly inside recursion
        if (cls.isPrimitive || obj is String || obj is Number || obj is Boolean) return
        
        try {
            var c: Class<*>? = cls
            while (c != null && c != Object::class.java) {
                cb?.onLog("${indent}  [Class: ${c.simpleName}]")
                c.declaredFields.forEach { f ->
                    f.isAccessible = true
                    val valObj = f.get(obj)
                    var valStr = "null"
                    if (valObj != null) {
                        valStr = if (valObj.javaClass.isArray) {
                             "[Array len=${java.lang.reflect.Array.getLength(valObj)}]"
                        } else {
                             valObj.toString().take(50)
                        }
                    }
                    cb?.onLog("${indent}    - ${f.name} (${f.type.simpleName}) = $valStr")
                    
                    // Recurse into interesting fields
                    if (valObj != null && depth < 3) {
                       if (f.name.lowercase().contains("delegate") || 
                           f.name.lowercase().contains("ssl") || 
                           f.name.lowercase().contains("session") ||
                           f.name.lowercase().contains("context") ||
                           f.name.lowercase().contains("param")) {
                           dumpObjectHierarchy(valObj, depth + 1, cb)
                       }
                    }
                }
                c = c.superclass
            }
        } catch (e: Exception) {
            cb?.onLog("${indent}  Error dumping: ${e.message}")
        }
    }

    // Reference helpers at class level
    private fun getFieldInHierarchy(cls: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = cls
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f
            } catch (e: NoSuchFieldException) { }
            c = c.superclass
        }
        return null
    }

    private fun getBytesField(obj: Any, names: List<String>): ByteArray? {
        val cls = obj.javaClass
        var c: Class<*>? = cls
        while (c != null) {
            for (name in names) {
                try {
                    val f = c!!.getDeclaredField(name)
                    f.isAccessible = true
                    val v = f.get(obj)
                    if (v is ByteArray) return v
                } catch (e: NoSuchFieldException) { }
            }
            c = c!!.superclass
        }
        return null
    }

    suspend fun connect(cb: OpenConnectCallback) {
        callback = cb
        
        // allowHiddenApi() // Not needed for bundled Conscrypt
        
        try {
            cb.onLog("Connecting to $serverAddress:$serverPort (OpenConnect) using Bundled Conscrypt...")
            
            // 1. SSL Handshake
            val factory = createConscryptSocketFactory()
            val socket = withContext(Dispatchers.IO) {
                val s = factory.createSocket(serverAddress, serverPort) as SSLSocket
                try {
                    s.enabledProtocols = arrayOf("TLSv1.2")
                    Log.d("OpenConnectEngine", "Forced TLSv1.2")
                } catch (e: Exception) {
                    Log.w("OpenConnectEngine", "Failed to force TLSv1.2: ${e.message}")
                }
                s.tcpNoDelay = tcpNoDelay
                s.receiveBufferSize = bufferSize
                s.sendBufferSize = bufferSize
                Log.d("OpenConnectEngine", "Socket config: tcpNoDelay=$tcpNoDelay, bufferSize=$bufferSize")
                s
            }
            this.socket = socket
            this.sslSocket = socket
            this.inputStream = java.io.BufferedInputStream(socket.inputStream, bufferSize)
            this.outputStream = java.io.BufferedOutputStream(socket.outputStream, bufferSize)
            
            // Get TLS version from the SSL session
            val sslSession = socket.session
            val tlsVersion = sslSession.protocol ?: "Unknown"
            val cipherSuite = sslSession.cipherSuite ?: "Unknown"
            
            // Extract master secret and randoms for DTLS PSK
            cb.onLog("Attempting to extract TLS secrets from ${socket.javaClass.name}...")
            val secrets = extractTlsSecrets(socket)
            tlsMasterSecret = secrets.masterHex
            
            // Derive PSK using RFC 5705 if possible (Standard label for OpenConnect)
            // Derive PSK using RFC 5705 if possible (Standard label for OpenConnect)
            if (this.derivedPsk == null) {
                this.derivedPsk = if (secrets.clientRandom != null && secrets.serverRandom != null) {
                    calculateRFC5705Exporter(secrets.masterBytes, secrets.clientRandom, secrets.serverRandom)
                } else {
                    null
                }
            } else {
                cb.onLog("Using pre-extracted/exported PSK (Conscrypt API).")
            }
            
            cb.onLog("TLS Handshake complete. Protocol: $tlsVersion")
            cb.onLog("Cipher: $cipherSuite")
            if (tlsMasterSecret.isNotEmpty()) {
                cb.onLog("Master secret extracted (${tlsMasterSecret.length/2} bytes)")
            }
            if (this.derivedPsk != null) {
                cb.onLog("RFC5705 PSK Derived (${this.derivedPsk!!.size} bytes)")
            } else {
                cb.onLog("Could not derive RFC5705 PSK. Missing randoms? C=${secrets.clientRandom?.size} S=${secrets.serverRandom?.size}")
            }
            cb.onLog("Cipher: $cipherSuite")
            if (tlsMasterSecret.isNotEmpty()) {
                cb.onLog("Master secret extracted for DTLS (${tlsMasterSecret.length/2} bytes)")
            }
            
            // 2. Authentication Phase (The complex part)
            // We need to simulate the XML-based login steps.
            // Step A: GET / to see if we get a form or needs headers
            // Usually we POST to / with XML.
            
            if (performAuthHandshake()) {
                 // 3. CSTP Tunnel Connection
                 establishTunnel()
            } else {
                 cb.onError("Authentication failed.")
            }

        } catch (e: Exception) {
            cb.onError("Connection Error: ${e.message}")
            e.printStackTrace()
        }
    }
    
    // --- Phase 1: Authentication ---
    private suspend fun performAuthHandshake(): Boolean {
        var currentMethod = "GET"
        var currentPath = "/"
        var nextBody = "" // Body to send in POST
        
        val cookies = mutableMapOf<String, String>()
        
        // Loop max 5 times to prevent infinite loops
        for (step in 1..5) {
            callback?.onLog("Auth Step $step: $currentMethod $currentPath")
            
            // 1. Send Request
            var req = "$currentMethod $currentPath HTTP/1.1\r\n" +
                      "Host: $serverAddress\r\n" +
                      "User-Agent: Open AnyConnect VPN Agent\r\n" +
                      "Connection: keep-alive\r\n"
            
            // Add Cookies
            if (cookies.isNotEmpty()) {
                val cookieStr = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                req += "Cookie: $cookieStr\r\n"
            }
            
            if (currentMethod == "POST") {
                req += "Content-Type: application/x-www-form-urlencoded\r\n"
                req += "Content-Length: ${nextBody.length}\r\n"
            }
            
            req += "\r\n"
            if (currentMethod == "POST") {
                req += nextBody
            }
            
            outputStream?.write(req.toByteArray())
            outputStream?.flush()
            
            // 2. Read Response
            val response = readResponse()
            
            // ... inside loop ...
            
            // Update Cookies - use lowercase key for case-insensitive lookup
            response.headers["set-cookie"]?.let { cookieHeader ->
                val keyVal = cookieHeader.split(";")[0]
                val parts = keyVal.split("=", limit=2)
                if (parts.size == 2) {
                    cookies[parts[0].trim()] = parts[1].trim()
                    callback?.onLog("Authentication cookie received")
                }
            }
            
            // Read Body - use lowercase key
            val contentLen = response.headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLen > 0) readBody(contentLen) else ""
            callback?.onLog("Authentication response received (${body.length} characters)")
            
            // Check for Success (type="complete")
            if (body.contains("type=\"complete\"")) {
                callback?.onLog("Auth Success (type=complete)!")
                
                // Use all cookies for the tunnel
                if (cookies.isNotEmpty()) {
                    webVpnCookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    return true
                } else {
                    callback?.onLog("Warning: No cookies found, but auth complete.")
                    return false
                }
            }
            
            // 3. Parse Form from Body
            // ...
            
            // 3. Parse Form from Body
            // Need to find: action, inputs
            
            // Find action
            val actionMatch = "action=\"([^\"]+)\"".toRegex().find(body)
            if (actionMatch != null) {
                currentPath = actionMatch.groupValues[1]
                // If path is relative, prefix (simplified)
                if (!currentPath.startsWith("/")) currentPath = "/$currentPath"
            }
            
            currentMethod = "POST"
            nextBody = ""
            
            // Find inputs
            if (body.contains("name=\"username\"")) {
                nextBody += "username=${java.net.URLEncoder.encode(username, "UTF-8")}"
            } else if (body.contains("name=\"password\"")) {
                nextBody += "password=${java.net.URLEncoder.encode(password, "UTF-8")}"
            }
            
            // Add hidden inputs often required? logic: if empty body, break?
            if (nextBody.isEmpty()) {
                 // Maybe we are just submitting? or unexpected form.
                 // Try generic submit if no fields found but form exists?
                 if (!body.contains("<form")) {
                     callback?.onLog("No form found. Stopping.")
                     return false
                 }
            }
        }
        
        return false
    }

    data class HttpResponse(val statusCode: Int, val headers: Map<String, String>)

    private fun readResponse(): HttpResponse {
        val headers = mutableMapOf<String, String>()
        var statusCode = 0
        
        // Read Status Line
        val statusLine = readAsciiLine()
        if (statusLine.isNotEmpty()) {
            val parts = statusLine.split(" ")
            if (parts.size > 1) {
                statusCode = parts[1].toIntOrNull() ?: 0
            }
        }
        
        // Read Headers - store keys in lowercase for case-insensitive lookup
        while (true) {
            val line = readAsciiLine()
            if (line.isEmpty()) break // End of headers
            
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                headers[parts[0].trim().lowercase()] = parts[1].trim()
            }
        }
        return HttpResponse(statusCode, headers)
    }
    
    // Reads a line (ASCII) byte-by-byte to avoid buffering
    private fun readAsciiLine(): String {
        val sb = StringBuilder()
        var c: Int
        while (true) {
            c = inputStream?.read() ?: -1
            if (c == -1) break
            if (c == '\n'.code) break
            if (c != '\r'.code) {
                sb.append(c.toChar())
            }
        }
        return sb.toString()
    }
    
    private fun readBody(len: Int): String {
        val buffer = ByteArray(len)
        var read = 0
        while (read < len) {
            val count = inputStream?.read(buffer, read, len - read) ?: -1
            if (count == -1) break
            read += count
        }
        return String(buffer, 0, read)
    }
    
    // --- Helper: Unsafe SSL ---
    private fun getUnsafeSslSocketFactory(): SSLSocketFactory {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
        })
        val context = SSLContext.getInstance("SSL")
        context.init(null, trustAllCerts, java.security.SecureRandom())
        return context.socketFactory
    }
    
    // --- Phase 2: Tunnel ---
    private fun establishTunnel() {
        // 1. Send CONNECT with DTLS capability headers
        val connectReq = "CONNECT /CSCOSSLC/tunnel HTTP/1.1\r\n" +
                         "Host: $serverAddress\r\n" +
                         "User-Agent: Open AnyConnect VPN Agent\r\n" +
                         "Cookie: $webVpnCookie\r\n" +
                         "X-CSTP-Version: 1\r\n" +
                         "X-CSTP-Hostname: android-client\r\n" +
                         // "X-CSTP-Accept-Encoding: deflate;q=1.0\r\n" + // Compression unsupported
                         "X-CSTP-MTU: $requestedMtu\r\n" +
                         "X-CSTP-Address-Type: IPv4\r\n" +
                         "X-DTLS-Master-Secret: $tlsMasterSecret\r\n" +
                         "X-DTLS-CipherSuite: ECDHE-RSA-AES256-GCM-SHA384:AES256-GCM-SHA384:AES128-GCM-SHA256\r\n" +
                         "X-DTLS12-CipherSuite: ECDHE-RSA-AES256-GCM-SHA384:AES256-GCM-SHA384:AES128-GCM-SHA256\r\n" +
                         // "X-DTLS-Accept-Encoding: deflate;q=1.0\r\n" + 
                         "\r\n"
                         
        outputStream?.write(connectReq.toByteArray())
        outputStream?.flush()
        callback?.onLog("Sent CONNECT for tunnel...")
        
        // 2. Read Response
        val response = readResponse()
        callback?.onLog("Tunnel Response: ${response.statusCode}")
        
        // Debug: Log all headers to see what server sends
        response.headers.forEach { (key, value) ->
            if (key.contains("dtls") || key.contains("cstp")) {
                callback?.onLog("Header: $key = $value")
            }
        }
        
        if (response.statusCode == 200) {
            // Parse IP, DNS, and MTU from headers
            val assignedIp = response.headers["x-cstp-address"] ?: ""
            val dns = response.headers["x-cstp-dns"] ?: "8.8.8.8"
            val mtu = minOf(requestedMtu, response.headers["x-cstp-mtu"]?.toIntOrNull() ?: requestedMtu)
            
            // Parse DTLS Info - server may send x-dtls-app-id OR x-dtls-session-id
            val dtlsAppId = response.headers["x-dtls-app-id"]
            val dtlsSessionId = response.headers["x-dtls-session-id"]
            val dtlsPortStr = response.headers["x-dtls-port"]
            val dtlsCipher = response.headers["x-dtls-ciphersuite"]
            
            // Use app-id or session-id, whichever is available
            val dtlsIdHex = dtlsAppId ?: dtlsSessionId
            
            callback?.onLog("Tunnel Established. IP: $assignedIp, MTU: $mtu")
            
            if (assignedIp.isNotEmpty()) {
                val resolvedIp = socket?.inetAddress?.hostAddress ?: serverAddress
                
                // Try DTLS if port is available (primary check) and not disabled
                if (dtlsPortStr != null && dtlsCipher != null && !disableDtls) {
                    val dtlsPort = dtlsPortStr.toIntOrNull() ?: 443
                    callback?.onLog("DTLS available! Port: $dtlsPort, Cipher: $dtlsCipher")
                    callback?.onLog("App-ID: ${dtlsAppId?.take(32)}...")
                    callback?.onLog("Secret Extraction Success Flag: $extractionSuccess")
                    callback?.onLog("Attempting DTLS connection...")
                    
                    try {
                        // Use app-id or generate from master secret if not available
                        val sessionId = if (dtlsIdHex != null && dtlsIdHex.length >= 64) {
                            dtlsIdHex.take(64).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        } else {
                            // Use first 32 bytes of master secret as session ID
                            tlsMasterSecret.take(64).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        }
                        
                        // Convert master secret hex string to bytes for PSK
                        val masterSecretBytes = if (tlsMasterSecret.isNotEmpty()) {
                            tlsMasterSecret.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        } else {
                            null
                        }
                        
                        // Check if cipher is PSK-NEGOTIATE
                        if (dtlsCipher?.contains("PSK-NEGOTIATE") == true) {
                             if (derivedPsk != null) {
                                 callback?.onLog("Using RFC5705 Derived PSK for DTLS PSK-NEGOTIATE")
                             } else {
                                 callback?.onLog("Warning: PSK-NEGOTIATE requested but no derived PSK available. DTLS might fail.")
                             }
                        }

                        // Use derived PSK if available, otherwise raw master secret (legacy)
                        val pskToUse = derivedPsk ?: masterSecretBytes
                             
                        // Create and connect DTLS client with PSK
                        val client = DTLSClient(
                            serverAddress = resolvedIp,
                            serverPort = dtlsPort,
                            sessionId = sessionId,
                            masterSecret = pskToUse
                             )
                             
                             val dtlsCallback = object : DTLSClient.DTLSCallback {
                                 override fun onConnected() {
                                     callback?.onLog("DTLS Connected! Using UDP for data.")
                                 }
                                 override fun onDataReceived(data: ByteArray) {
                                     callback?.onDataPacket(data)
                                 }
                                 override fun onError(message: String) {
                                     callback?.onLog("DTLS Error: $message")
                                 }
                                 override fun onDisconnected() {
                                     callback?.onLog("DTLS Disconnected")
                                 }
                             }

                             // Try DTLS with a short timeout - 5s
                             callback?.onLog("Starting DTLS handshake (5s timeout)...")
                             var dtlsConnected = false
                             val dtlsThread = Thread {
                                 try {
                                     callback?.onLog("DTLS Thread started. Calling connect()...")
                                     dtlsConnected = client.connect(dtlsCallback)
                                     callback?.onLog("DTLS connect() returned: $dtlsConnected")
                                 } catch (e: Throwable) {
                                     Log.e("OpenConnectEngine", "DTLS Critical Error", e)
                                     callback?.onLog("DTLS CRASH: ${e.javaClass.simpleName} - ${e.message}")
                                     e.printStackTrace()
                                 }
                             }
                             dtlsThread.start()
                             dtlsThread.join(5000) // Wait max 5 seconds

                             if (dtlsThread.isAlive) {
                                 callback?.onLog("DTLS handshake timed out after 5s")
                                 dtlsThread.interrupt()
                                 dtlsConnected = false
                             }

                             if (dtlsConnected) {
                                 dtlsClient = client
                                 useDtls = true
                                 callback?.onLog("DTLS Active! Data over UDP.")
                             } else {
                                 callback?.onLog("DTLS failed, falling back to TLS")
                                 useDtls = false
                             }
                    } catch (e: Exception) {
                        callback?.onLog("DTLS Error: ${e.message}, using TLS")
                        useDtls = false
                    }
                } else if (disableDtls) {
                    callback?.onLog("DTLS disabled by user setting. Using TLS.")
                    useDtls = false
                } else {
                    callback?.onLog("Server does not support DTLS. Using TLS.")
                    useDtls = false
                }
                
                callback?.onConnected(assignedIp, resolvedIp, listOf(dns), mtu)
                
                // NOTE: Data loop is NOT started here anymore.
                // VpnConnectionService will call startDataLoop() after establishing the VPN interface.
                // This fixes a race condition where data arrives before VPN interface is ready.
            } else {
                callback?.onError("No IP assigned by server.")
            }
        } else {
            callback?.onError("Tunnel failed. Status: ${response.statusCode}")
        }
    }
    
    private var rxPacketCount = 0
    
    /**
     * Start the CSTP data receive loop. Must be called AFTER the VPN interface is established.
     * This reads packets from the server and forwards them to the callback.
     */
    fun startDataLoop() {
        callback?.onLog("Starting CSTP data loop...")
        isRunning = true
        
        // Start keepalive thread
        startKeepaliveThread()
        
        // Launch reader loop
        val buffer = ByteArray(32767)
        try {
            while (true) {
                // Read CSTP Header (8 bytes)
                // STF\x01 (4) + Len (2) + Rsrv (2)
                readExactly(8, buffer)
                
                // Check Magic
                if (buffer[0] != 0x53.toByte() || buffer[1] != 0x54.toByte() || buffer[2] != 0x46.toByte() || buffer[3] != 0x01.toByte()) {
                    callback?.onLog("Bad CSTP Magic: ${buffer[0]},${buffer[1]}...")
                    // If we lose sync, strictly we should disconnect, but might be keepalive?
                    // For now, disconnect.
                    break
                }
                
                // Get Length (Big Endian at 4,5)
                val len = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
                
                if (len > 0) {
                    val payload = ByteArray(len)
                    readExactly(len, payload)
                    
                    rxPacketCount++
                    if (rxPacketCount <= 5) {
                        Log.d("OpenConnectEngine", "Received CSTP packet #$rxPacketCount: $len bytes")
                    }
                    
                    // Forward to VPN interface
                    callback?.onDataPacket(payload)
                } else {
                    // Keepalive or control packet (len = 0)
                    Log.d("OpenConnectEngine", "Received keepalive/control packet")
                }
            }
        } catch (e: Exception) {
            if (isRunning) {
                callback?.onError("Data Loop Error: ${e.message}")
            }
        } finally {
            isRunning = false
        }
        Log.d("OpenConnectEngine", "Data loop ended. Total received: $rxPacketCount")
    }
    
    /**
     * Start background thread to send keepalive packets
     */
    private fun startKeepaliveThread() {
        keepaliveThread = Thread {
            Log.d("OpenConnectEngine", "Keepalive thread started (interval: ${dpdInterval}s)")
            try {
                while (isRunning) {
                    Thread.sleep(dpdInterval * 1000L)
                    if (isRunning) {
                        sendKeepalive()
                    }
                }
            } catch (e: InterruptedException) {
                Log.d("OpenConnectEngine", "Keepalive thread interrupted")
            } catch (e: Exception) {
                Log.e("OpenConnectEngine", "Keepalive thread error: ${e.message}")
            }
            Log.d("OpenConnectEngine", "Keepalive thread exited")
        }
        keepaliveThread?.isDaemon = true
        keepaliveThread?.start()
    }
    
    /**
     * Send CSTP keepalive/DPD packet (empty payload with STF header)
     */
    private fun sendKeepalive() {
        try {
            // CSTP keepalive is just the header with length 0
            val frame = ByteArray(8)
            frame[0] = 0x53.toByte() // S
            frame[1] = 0x54.toByte() // T
            frame[2] = 0x46.toByte() // F
            frame[3] = 0x01.toByte() // version
            frame[4] = 0x00 // length high
            frame[5] = 0x00 // length low
            frame[6] = 0x00 // reserved
            frame[7] = 0x00 // reserved
            
            synchronized(outputStream ?: return) {
                outputStream?.write(frame)
                outputStream?.flush()
            }
            Log.d("OpenConnectEngine", "Sent keepalive packet")
        } catch (e: Exception) {
            Log.e("OpenConnectEngine", "Failed to send keepalive: ${e.message}")
        }
    }
    
    private fun readExactly(len: Int, buf: ByteArray) {
        var read = 0
        while (read < len) {
            val count = inputStream?.read(buf, read, len - read) ?: -1
            if (count == -1) throw java.io.EOFException("Socket closed")
            read += count
        }
    }
    
    private var txPacketCount = 0
    
    fun sendDataPacket(data: ByteArray) {
        // Construct CSTP Frame
        // Header: STF\x01 (4) + Len (2) + 00 00 (2)
        val frame = ByteArray(8 + data.size)
        
        // Magic
        frame[0] = 0x53.toByte() // S
        frame[1] = 0x54.toByte() // T
        frame[2] = 0x46.toByte() // F
        frame[3] = 0x01.toByte() // \x01
        
        // Length
        val len = data.size
        frame[4] = ((len shr 8) and 0xFF).toByte()
        frame[5] = (len and 0xFF).toByte()
        
        // Reserved
        frame[6] = 0x00
        frame[7] = 0x00
        
        // Payload
        System.arraycopy(data, 0, frame, 8, len)
        
        try {
            outputStream?.write(frame)
            outputStream?.flush()  // IMPORTANT: Flush to actually send the data!
            txPacketCount++
            if (txPacketCount <= 5) {
                Log.d("OpenConnectEngine", "Sent CSTP packet #$txPacketCount: ${data.size} bytes payload")
            }
        } catch (e: Exception) {
            Log.e("OpenConnectEngine", "Error sending packet: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * DTLS Data Loop - receives UDP packets and forwards to callback
     */
    private fun startDtlsDataLoop() {
        callback?.onLog("Starting DTLS data loop (UDP)...")
        try {
            while (useDtls && dtlsClient?.isConnected() == true) {
                val data = dtlsClient?.receive()
                if (data != null && data.isNotEmpty()) {
                    // DTLS packets are raw IP packets (no CSTP header)
                    callback?.onDataPacket(data)
                }
            }
        } catch (e: Exception) {
            if (useDtls) {
                callback?.onError("DTLS Data Loop Error: ${e.message}")
            }
        }
        callback?.onLog("DTLS data loop ended")
    }
    
    /**
     * Send data packet - uses DTLS (UDP) if active, otherwise TLS (TCP)
     */
    fun sendDataPacketDtls(data: ByteArray) {
        if (useDtls && dtlsClient?.isConnected() == true) {
            // Send raw IP packet over DTLS (no CSTP framing needed for UDP)
            dtlsClient?.send(data)
        } else {
            // Fallback to TLS with CSTP framing
            sendDataPacket(data)
        }
    }
    
    fun disconnect() {
        isRunning = false
        useDtls = false
        
        // Stop keepalive thread
        try {
            keepaliveThread?.interrupt()
            keepaliveThread?.join(1000)
        } catch (e: Exception) {}
        keepaliveThread = null
        
        try {
            dtlsClient?.disconnect()
        } catch (e: Exception) {}
        dtlsClient = null
        try {
            socket?.close()
        } catch (e: Exception) {}
        callback?.onDisconnected()
        callback = null
    }
    
    fun isUsingDtls() = useDtls
}

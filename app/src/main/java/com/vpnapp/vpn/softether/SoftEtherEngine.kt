package com.vpnapp.vpn.softether

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SoftEtherEngine(
    private val serverAddress: String,
    private val serverPort: Int,
    private val username: String,
    private val password: String,
    private val skipCertificateVerification: Boolean = false
) {
    private var socket: Socket? = null
    private var sslSocket: SSLSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var callback: SoftEtherCallback? = null
    
    interface SoftEtherCallback {
        fun onLog(message: String)
        fun onError(message: String)
        fun onConnected(privateIp: String, serverIp: String, dnsServers: List<String>)
        fun onDataPacket(data: ByteArray)
        fun onDisconnected()
    }
    
    suspend fun connect(cb: SoftEtherCallback) {
        this.callback = cb
        
        withContext(Dispatchers.IO) {
            try {
                cb.onLog("Connecting to $serverAddress:$serverPort (SoftEther Native)...")
                
                // 1. TCP Connect
                socket = Socket()
                socket?.connect(InetSocketAddress(serverAddress, serverPort), 10000)
                
                // 2. SSL Handshake (SoftEther uses HTTPS)
                cb.onLog("Starting SSL Handshake...")
                val sslContext = if (skipCertificateVerification) getUnsafeSslContext() else SSLContext.getDefault()
                val factory = sslContext.socketFactory
                sslSocket = factory.createSocket(socket, serverAddress, serverPort, true) as SSLSocket
                sslSocket?.startHandshake()
                
                inputStream = sslSocket?.inputStream
                outputStream = sslSocket?.outputStream
                
                cb.onLog("SSL Established. Starting SoftEther Handshake...")
                
                // 3. SoftEther Handshake typically involves:
                // GET / HTTP/1.1 -> Server info
                // POST /vpnsvc/connect.cgi -> Binary stream
                
                // TODO: Implement Native Handshake
                // For now, we just establish connection to verify reachability
                
                performHandshake()

            } catch (e: Exception) {
                cb.onError("Error: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    private fun performHandshake() {
        // Placeholder
        val req = "GET / HTTP/1.1\r\nHost: $serverAddress\r\n\r\n"
        outputStream?.write(req.toByteArray())
        outputStream?.flush()
        
        // Just read one line to prove it talks
        val buffer = ByteArray(1024)
        val len = inputStream?.read(buffer) ?: -1
        if (len > 0) {
            val response = String(buffer, 0, len)
            callback?.onLog("Server Response: $response")
            callback?.onError("SoftEther Native handshake not fully implemented yet.")
        } else {
            callback?.onError("Server closed connection.")
        }
    }
    
    fun sendDataPacket(data: ByteArray) {
        // todo
    }
    
    fun disconnect() {
        try {
            sslSocket?.close()
            socket?.close()
        } catch (e: Exception) {}
    }

    private fun getUnsafeSslContext(): SSLContext {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        return sslContext
    }
}

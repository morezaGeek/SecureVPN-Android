package com.vpnapp

import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/** Generated test-APK identity; no AndroidKeyStore/emulator signing dependency or production CA change. */
internal class LocalTlsServer : Closeable {
    private val keys = KeyStore.getInstance("PKCS12").apply {
        InstrumentationRegistry.getInstrumentation().context.assets.open("loopback-test.p12").use {
            load(it, "local-test-only".toCharArray())
        }
    }
    val certificate: X509Certificate = keys.getCertificate("loopback") as X509Certificate
    val server: SSLServerSocket
    init {
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keys, "local-test-only".toCharArray())
        }
        server = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            .serverSocketFactory.createServerSocket(0, 16, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
    }
    override fun close() = server.close()
}

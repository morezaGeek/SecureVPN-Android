package com.vpnapp

import org.junit.Assert.*
import org.junit.Test
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

class LocalTlsServerTest {
    @Test fun ephemeralIdentitySupportsTls12And13() {
        LocalTlsServer().use { fixture ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                for (protocol in listOf("TLSv1.2", "TLSv1.3")) {
                    val peer = executor.submit {
                        fixture.server.accept().use { socket ->
                            socket.soTimeout = 4000
                            assertEquals(42, socket.getInputStream().read())
                            socket.getOutputStream().write(43)
                        }
                    }
                    // Pinned to the exact ephemeral fixture certificate, never installed as a system CA.
                    val trust = object : X509TrustManager {
                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                        override fun checkClientTrusted(chain: Array<out X509Certificate>?, auth: String?) = error("No client auth")
                        override fun checkServerTrusted(chain: Array<out X509Certificate>?, auth: String?) {
                            requireNotNull(chain).first().checkValidity()
                            assertArrayEquals(fixture.certificate.encoded, chain.first().encoded)
                            assertTrue(chain.first().publicKey.algorithm == "EC")
                            chain.first().verify(chain.first().publicKey)
                        }
                    }
                    val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
                    (tls.socketFactory.createSocket("127.0.0.1", fixture.server.localPort) as SSLSocket).use { client ->
                        client.soTimeout = 4000
                        client.enabledProtocols = arrayOf(protocol)
                        client.startHandshake()
                        client.outputStream.write(42)
                        assertEquals(43, client.inputStream.read())
                    }
                    peer.get(5, TimeUnit.SECONDS)
                }
            } finally { executor.shutdownNow() }
        }
    }
}

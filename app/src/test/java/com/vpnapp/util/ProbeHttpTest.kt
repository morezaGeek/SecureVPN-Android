package com.vpnapp.util

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

class ProbeHttpTest {
    @Test fun consumesChunkedResponsesBeforeReusingTheSessionAndExcludesSetupTime() {
        ServerSocket(0).use { server ->
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    repeat(3) { index ->
                        while (input.readLine()?.isNotEmpty() == true) {}
                        val reply = if (index == 0) "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n"
                            else "HTTP/1.1 204 No Content\r\n\r\n"
                        socket.getOutputStream().apply { write(reply.toByteArray()); flush() }
                    }
                }
            }
            val setupStart = System.nanoTime()
            ProbeHttp.open(URL("http://localhost:${server.localPort}/ping")) { host, port ->
                Thread.sleep(350)
                Socket(host, port).apply { soTimeout = 2000 }
            }.use { session ->
                assertEquals("abc", session.get(true).body)
                assertTrue((System.nanoTime() - setupStart) / 1000000 >= 350)
                val start = System.nanoTime()
                assertEquals(204, session.get(true).status)
                assertEquals(204, session.get(true).status)
                assertTrue("Setup time leaked into warm probe", (System.nanoTime() - start) / 1000000 < 300)
            }
            worker.join(2000)
        }
    }
}

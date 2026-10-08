package com.vpnapp.vpn.singbox

import android.content.*
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.MainActivity
import com.vpnapp.util.ProbeHttp
import com.vpnapp.vpn.VpnConnectionService
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.*
import kotlin.concurrent.thread

class ProbeCoreLifecycleTest {
    @Test fun exitLookupAndWarmLatencySurviveFiveCoreRestarts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertNull(android.net.VpnService.prepare(context))
        val events = LinkedBlockingQueue<String>()
        val closingPid = java.util.concurrent.atomic.AtomicInteger()
        val endpointState = java.util.concurrent.atomic.AtomicReference<ProbeEndpoint?>()
        fun fakeIp(host: String): String {
            val query = java.io.ByteArrayOutputStream().also { bytes ->
                java.io.DataOutputStream(bytes).apply {
                    writeShort(2345); writeShort(256); writeShort(1); repeat(3) { writeShort(0) }
                    host.split('.').forEach { label -> writeByte(label.length); writeBytes(label) }
                    writeByte(0); writeShort(1); writeShort(1)
                }
            }.toByteArray()
            java.net.DatagramSocket().use { socket ->
                socket.soTimeout = 4000
                socket.send(java.net.DatagramPacket(query, query.size, java.net.InetAddress.getByName("8.8.8.8"), 53))
                val response = java.net.DatagramPacket(ByteArray(4096), 4096); socket.receive(response)
                val bytes = response.data.copyOf(response.length)
                assertEquals("DNS transaction", 2345, ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255))
                assertEquals("DNS response must succeed", 0, bytes[3].toInt() and 15)
                assertTrue("DNS must have an A answer", bytes[7].toInt() > 0)
                return bytes.takeLast(4).joinToString(".") { (it.toInt() and 255).toString() }.also {
                    assertTrue("Expected FakeIP: $it", it.startsWith("198.18.") || it.startsWith("198.19."))
                }
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getStringExtra(VpnConnectionService.EXTRA_STATUS)) {
                    "CONNECTED" -> endpointState.set(ProbeEndpoint.readFrom(intent))
                    "DISCONNECTED" -> {
                        endpointState.set(null)
                        closingPid.set(intent.getIntExtra("closed_worker_pid", 0))
                    }
                }
                intent.getStringExtra(VpnConnectionService.EXTRA_STATUS)?.let(events::offer)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(VpnConnectionService.ACTION_VPN_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        fun await(status: String) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < end) {
                val value = events.poll(1, TimeUnit.SECONDS) ?: continue
                assertNotEquals("Core error", "ERROR", value)
                if (value == status) {
                    if (status == "DISCONNECTED") com.vpnapp.AwaitVpnTeardown.await(context, closingPid.get())
                    return
                }
            }
            fail("Missing status $status")
        }
        val workers = Executors.newCachedThreadPool()
        val retainedTargets = LinkedBlockingQueue<String>()
        val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        val acceptor = thread(isDaemon = true) {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    workers.submit {
                        socket.use {
                            socket.soTimeout = 10000
                            val input = DataInputStream(socket.getInputStream())
                            check(input.readUnsignedByte() == 0)
                            input.readFully(ByteArray(16))
                            input.readFully(ByteArray(input.readUnsignedByte()))
                            check(input.readUnsignedByte() == 1)
                            input.readUnsignedShort()
                            val addressType = input.readUnsignedByte()
                            val length = when (addressType) {
                                1 -> 4
                                2 -> input.readUnsignedByte()
                                3 -> 16
                                else -> error("Invalid VLESS address")
                            }
                            val targetBytes = ByteArray(length); input.readFully(targetBytes)
                            val targetName = if (addressType == 2) targetBytes.toString(Charsets.UTF_8) else "IP"
                            val output = socket.getOutputStream()
                            output.write(byteArrayOf(0, 0)); output.flush()
                            val reader = input.bufferedReader()
                            var requestIndex = 0
                            while (true) {
                                val request = reader.readLine() ?: break
                                if(request.contains("/retained")) retainedTargets.offer(targetName)
                                var keepAlive = false
                                while (true) {
                                    val header = reader.readLine() ?: return@submit
                                    if (header.isEmpty()) break
                                    if (header.equals("Connection: keep-alive", true)) keepAlive = true
                                }
                                if (request.contains("/ping")) {
                                    Thread.sleep(if (requestIndex++ == 0) 300 else 15)
                                    output.write("HTTP/1.1 204 No Content\r\n\r\n".toByteArray())
                                } else {
                                    val body = "{\"ip\":\"203.0.113.100\",\"country_code\":\"DE\"}"
                                    output.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
                                }
                                output.flush()
                                if (!keepAlive) break
                            }
                        }
                    }
                }
            } catch (_: java.net.SocketException) { }
        }
        try {
            var retainedFakeIp: String? = null
            repeat(5) { round ->
                events.clear()
                context.startService(Intent(context, VpnConnectionService::class.java).apply {
                    action = VpnConnectionService.ACTION_CONNECT
                    putExtra(VpnConnectionService.EXTRA_PROTOCOL, "VLESS")
                    putExtra(VpnConnectionService.EXTRA_PROFILE_NAME, "Local probe fixture")
                    putExtra(VpnConnectionService.EXTRA_SERVER_IP_INPUT, "127.0.0.1")
                    putExtra(VpnConnectionService.EXTRA_SERVER_PORT, server.localPort)
                    putExtra(VpnConnectionService.EXTRA_V2RAY_UUID, "00000000-0000-4000-8000-000000000001")
                    putExtra(VpnConnectionService.EXTRA_MTU, if (round % 2 == 0) 1280 else 1400)
                })
                await("CONNECTED")
                val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
                val readyUntil = android.os.SystemClock.elapsedRealtime() + 5_000
                fun vpnReady() = manager.getNetworkCapabilities(manager.activeNetwork)
                    ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                while (!vpnReady() && android.os.SystemClock.elapsedRealtime() < readyUntil) Thread.sleep(50)
                assertTrue("DNS query must enter the Android VPN", vpnReady())
                // Vary allocation order after reconnect. Applications can retain a previously resolved IP.
                repeat(round + 1) { fakeIp("noise-$round-$it.invalid") }
                val mapped = fakeIp("retained-app-cache.invalid")
                if (retainedFakeIp == null) retainedFakeIp = mapped
                else assertEquals("FakeIP changed while an application's cached IP can survive reconnect", retainedFakeIp, mapped)
                // Use the cached IP as the HTTP Host too: sniffing cannot recover the original domain.
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(retainedFakeIp,80),4000);socket.soTimeout=4000
                    socket.getOutputStream().write("GET /retained HTTP/1.1\r\nHost: $retainedFakeIp\r\nConnection: close\r\n\r\n".toByteArray())
                    assertTrue(socket.getInputStream().bufferedReader().readLine().contains("200"))
                }
                assertEquals("Persist the reverse IP → domain mapping too", "retained-app-cache.invalid", retainedTargets.poll(4, TimeUnit.SECONDS))
                val endpoint = checkNotNull(endpointState.get())
                ProbeHttp.open(URL("http://probe.invalid/geo"), endpoint::connect).use {
                    assertTrue(it.get().body.contains("203.0.113.100"))
                }
                ProbeHttp.open(URL("http://probe.invalid/ping"), endpoint::connect).use {
                    assertEquals(204, it.get(true).status)
                    val start = System.nanoTime()
                    assertEquals(204, it.get(true).status)
                    val milliseconds = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                    assertTrue("Warm latency includes setup: $milliseconds", milliseconds < 250)
                }
                val config = org.json.JSONObject(java.io.File(context.cacheDir, "sing-box-config.json").readText())
                val inbounds = config.getJSONArray("inbounds")
                val tun = (0 until inbounds.length()).map { inbounds.getJSONObject(it) }.single { it.getString("type") == "tun" }
                assertEquals(if (round % 2 == 0) 1280 else 1400, tun.getInt("mtu"))
                context.startService(Intent(context, VpnConnectionService::class.java).setAction(VpnConnectionService.ACTION_DISCONNECT))
                await("DISCONNECTED")
                assertNull(endpointState.get())
            }
        } finally {
            context.startService(Intent(context, VpnConnectionService::class.java).setAction(VpnConnectionService.ACTION_DISCONNECT))
            server.close()
            workers.shutdownNow()
            acceptor.join(1000)
            context.unregisterReceiver(receiver)
            activity.finish()
        }
    }
}

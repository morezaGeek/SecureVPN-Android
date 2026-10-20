package com.vpnapp

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.app.NotificationManager
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.data.UsageHistory
import com.vpnapp.util.LocalNetworkInfo
import com.vpnapp.vpn.*
import com.vpnapp.vpn.singbox.ProbeEndpoint
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.Socket
import java.time.LocalDate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class NetworkFeaturesRuntimeTest {
    @Test fun usageSurvivesReopenWithoutCountingDuplicateOrStaleSamples() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="usage-diagnostic-${System.nanoTime()}.db"
        val today=LocalDate.now()
        try {
            UsageHistory(context,name).use { db ->
                db.record("one",100,20);db.record("one",100,20);db.record("one",80,10)
                db.record("one",150,30);db.record("two",40,7)
            }
            UsageHistory(context,name).use { db ->
                val day=db.days(today,today).single()
                assertEquals(190L,day.downloaded);assertEquals(37L,day.uploaded)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun openConnectAndSelectedLanProtocolsWorkOnNativePages() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture=File(context.cacheDir,"network-features.private.json")
        assumeTrue("Opt-in authorized OpenConnect fixture required",fixture.isFile)
        assumeTrue("VPN consent required",VpnService.prepare(context)==null)
        val cm=context.getSystemService(ConnectivityManager::class.java)
        fun vpn()=cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true }
        assumeTrue("Do not disturb another running VPN",vpn()==null)
        val original=LanShareSettings.read(context)
        val port=java.net.ServerSocket(0).use { it.localPort }
        val input=JSONObject(fixture.readText())
        val result=JSONObject().put("page_size",Os.sysconf(OsConstants._SC_PAGESIZE))
        val externalLan = InstrumentationRegistry.getArguments().getString("externalLan") == "true"
        fun externalPhase(phase: String) {
            if (!externalLan) return
            val reply = File(context.cacheDir, "external-lan-$phase.json")
            reply.delete()
            File(context.cacheDir,"external-lan-ready.json").writeText(
                JSONObject().put("phase",phase).put("port",port).toString())
            val end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60)
            while (!reply.isFile && System.nanoTime() < end) Thread.sleep(100)
            assertTrue("External host must report $phase result", reply.isFile)
            val response = JSONObject(reply.readText())
            result.put("external_$phase",response)
            assertTrue("External LAN $phase: ${response.optString("error")}",response.optBoolean("ok"))
        }
        val history=UsageHistory(context)
        val today=LocalDate.now()
        val before=history.days(today,today).sumOf { it.downloaded+it.uploaded }
        fun waitFor(message: String, condition: () -> Boolean) {
            val end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(25)
            while(!condition() && System.nanoTime()<end) Thread.sleep(150)
            assertTrue(message,condition())
        }
        fun listenerReady()=runCatching { Socket("127.0.0.1",port).use { true } }.getOrDefault(false)
        fun fetch(raw: Socket): String {
            raw.use {
                val ssl=org.conscrypt.Conscrypt.newProvider()
                val tlsContext=javax.net.ssl.SSLContext.getInstance("TLS",ssl).apply { init(null,null,null) }
                (tlsContext.socketFactory.createSocket(raw,"api.ipify.org",443,true) as SSLSocket).use { socket ->
                    socket.soTimeout=20000
                    socket.sslParameters=socket.sslParameters.apply {
                        endpointIdentificationAlgorithm="HTTPS";serverNames=listOf(SNIHostName("api.ipify.org"))
                    }
                    socket.startHandshake()
                    socket.outputStream.write("GET / HTTP/1.1\r\nHost: api.ipify.org\r\nConnection: close\r\n\r\n".toByteArray())
                    socket.outputStream.flush()
                    return socket.inputStream.bufferedReader().readText().also { assertTrue(it.startsWith("HTTP/1.1 200")) }
                }
            }
        }
        try {
            LanShareSettings.save(context,original.copy(enabled=true,port=port,protocol=LanProxyProtocol.HTTP))
            val connect=Intent(context,VpnConnectionService::class.java).setAction(VpnConnectionService.ACTION_CONNECT)
                .putExtra(VpnConnectionService.EXTRA_SERVER_IP_INPUT,input.optString("server"))
                .putExtra(VpnConnectionService.EXTRA_SERVER_PORT,443)
                .putExtra(VpnConnectionService.EXTRA_PROTOCOL,"OPENCONNECT")
                .putExtra(VpnConnectionService.EXTRA_USERNAME,input.optString("username"))
                .putExtra(VpnConnectionService.EXTRA_PASSWORD,input.optString("password"))
                .putExtra(VpnConnectionService.EXTRA_SKIP_CERT_VERIFY,true)
                .putExtra(VpnConnectionService.EXTRA_USE_NATIVE,true)
                .putExtra(VpnConnectionService.EXTRA_PROFILE_NAME,"Temporary OpenConnect diagnostic")
                .putExtra("bypass_iran",false).putExtra("bypass_private_ips",false)
            input.optJSONObject("outbound")?.let { outbound ->
                val tls=outbound.getJSONObject("tls")
                val transport=outbound.getJSONObject("transport")
                connect.putExtra(VpnConnectionService.EXTRA_SERVER_IP_INPUT,outbound.getString("server"))
                    .putExtra(VpnConnectionService.EXTRA_SERVER_PORT,outbound.getInt("server_port"))
                    .putExtra(VpnConnectionService.EXTRA_PROTOCOL,"VLESS")
                    .putExtra(VpnConnectionService.EXTRA_V2RAY_UUID,outbound.getString("uuid"))
                    .putExtra(VpnConnectionService.EXTRA_V2RAY_NETWORK,"xhttp")
                    .putExtra(VpnConnectionService.EXTRA_V2RAY_TYPE,transport.optString("mode","stream-up"))
                    .putExtra(VpnConnectionService.EXTRA_V2RAY_PATH,transport.optString("path"))
                    .putExtra(VpnConnectionService.EXTRA_V2RAY_HOST,transport.optString("host"))
                    .putExtra(VpnConnectionService.EXTRA_V2RAY_TLS,"tls")
                    .putExtra(VpnConnectionService.EXTRA_V2RAY_SNI,tls.optString("server_name"))
                    .putExtra(VpnConnectionService.EXTRA_V2RAY_FP,tls.optJSONObject("utls")?.optString("fingerprint")?:"chrome")
                    .putExtra(VpnConnectionService.EXTRA_SKIP_CERT_VERIFY,tls.optBoolean("insecure",false))
                tls.optJSONArray("alpn")?.let { alpn -> connect.putExtra(VpnConnectionService.EXTRA_V2RAY_ALPN,
                    (0 until alpn.length()).joinToString(",") { alpn.getString(it) }) }
                val fake=InstrumentationRegistry.getArguments().getString("fakeIp") == "true"
                com.vpnapp.vpn.singbox.TunnelDnsSettings(com.vpnapp.vpn.singbox.TunnelDns.DEFAULT_URL,fake).writeTo(connect)
                result.put("vpn_protocol","VLESS").put("fake_ip",fake)
            }
            LanShareSettings.read(context).writeTo(connect)
            context.startForegroundService(connect)
            waitFor("VPN interface",{ vpn()!=null })
            assertTrue("Physical address must exclude the tunnel",LocalNetworkInfo.addresses(context).isNotEmpty())
            assertFalse(LocalNetworkInfo.addresses(context).contains("172.19.0.1"))
            waitFor("LAN listener starts",::listenerReady)
            externalPhase("http")
            Socket("127.0.0.1",port).use { socket ->
                socket.soTimeout=4000
                socket.getOutputStream().write(byteArrayOf(5,1,0))
                assertEquals(5,socket.getInputStream().read());assertEquals(255,socket.getInputStream().read())
            }
            Socket("127.0.0.1",port).use { socket ->
                socket.soTimeout=20000
                socket.getOutputStream().write("CONNECT api.ipify.org:443 HTTP/1.1\r\nHost: api.ipify.org:443\r\n\r\n".toByteArray())
                val header=StringBuilder()
                while(!header.endsWith("\r\n\r\n")) header.append(socket.getInputStream().read().also { require(it>=0) }.toChar())
                assertTrue("HTTP must not challenge for credentials",header.startsWith("HTTP/1.1 200"))
                result.put("http_without_password_http_status",fetch(socket).lineSequence().first())
            }
            val config=LanShareSettings.read(context).copy(protocol=LanProxyProtocol.SOCKS5,
                socksAuthentication=true,username="my-lan-user",password="my chosen password")
            LanShareSettings.save(context,config)
            context.startService(Intent(context,VpnConnectionService::class.java).setAction(VpnConnectionService.ACTION_LAN_SHARE_CONFIG).also(config::writeTo))
            waitFor("Changing to SOCKS5 restarts the listener") {
                runCatching { Socket("127.0.0.1",port).use { socket ->
                    socket.soTimeout=1000
                    socket.outputStream.write(byteArrayOf(5,1,2))
                    socket.inputStream.read()==5 && socket.inputStream.read()==2
                } }.getOrDefault(false)
            }
            assertTrue("Wrong SOCKS5 password must fail",runCatching {
                ProbeEndpoint(port,config.username,"wrong-password").connect("api.ipify.org",443).use { }
            }.isFailure)
            externalPhase("socks5")
            val socks=fetch(ProbeEndpoint(port,config.username,config.password).connect("api.ipify.org",443))
            result.put("socks5_http_status",socks.lineSequence().first())
            val disable=Intent(context,VpnConnectionService::class.java).setAction(VpnConnectionService.ACTION_LAN_SHARE_CONFIG)
            config.copy(enabled=false).writeTo(disable);context.startService(disable)
            waitFor("LAN sharing can be disabled without disconnecting VPN") { !listenerReady() }
            assertNotNull(vpn())
            val enable=Intent(context,VpnConnectionService::class.java).setAction(VpnConnectionService.ACTION_LAN_SHARE_CONFIG)
            config.writeTo(enable);context.startService(enable)
            waitFor("LAN sharing can be enabled on the existing VPN",::listenerReady)
            waitFor("VPN-only traffic must reach persistent history") {
                history.days(today,today).sumOf { it.downloaded+it.uploaded }>before
            }
            result.put("recorded_bytes_delta",history.days(today,today).sumOf { it.downloaded+it.uploaded }-before)
            val notifications=context.getSystemService(NotificationManager::class.java)
            waitFor("Ongoing VPN notification") { notifications.activeNotifications.any { it.id==1 && it.isOngoing } }
            result.put("notification_ongoing",true).put("custom_socks5_credentials_verified",true)
        } finally {
            context.startService(Intent(context,VpnConnectionService::class.java).setAction(VpnConnectionService.ACTION_DISCONNECT))
            waitFor("Disconnect removes VPN") { vpn()==null }
            waitFor("Disconnect closes LAN listener") { !listenerReady() }
            LanShareSettings.save(context,original)
            fixture.delete();history.close()
            File(context.cacheDir,"external-lan-ready.json").delete()
            File(context.cacheDir,"network-features-results.json").writeText(result.toString(2))
        }
    }
}

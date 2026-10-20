package com.vpnapp

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.vpn.VpnConnectionService
import com.vpnapp.vpn.singbox.TunnelDns
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Opt-in end-to-end test. Credentials arrive in debug cache, never in an APK. */
class TunnelDnsServiceTest {
    @Test fun youtubeUsesCustomDnsThroughTheRealVpnService() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val input=File(context.cacheDir,"dns-service.private.json")
        assumeTrue("Requires an authorized private France fixture",input.isFile)
        assumeTrue("Requires emulator VPN consent",VpnService.prepare(context)==null)
        val prefs=context.getSharedPreferences("vpn_app_prefs",0)
        val oldUrl=prefs.getString(TunnelDns.URL_KEY,null)
        val oldFake=prefs.getBoolean(TunnelDns.FAKE_IP_KEY,true)
        val cm=context.getSystemService(ConnectivityManager::class.java)
        fun vpn()=cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true
        }
        assertNull("Do not disturb an existing VPN",vpn())
        try {
            val outbound=JSONObject(input.readText())
            val tls=outbound.getJSONObject("tls");val transport=outbound.getJSONObject("transport")
            prefs.edit().putString(TunnelDns.URL_KEY,"https://doh.rahanetmci.com/dns-query")
                .putBoolean(TunnelDns.FAKE_IP_KEY,false).commit()
            val intent=Intent(context,VpnConnectionService::class.java).setAction(VpnConnectionService.ACTION_CONNECT)
                .putExtra(VpnConnectionService.EXTRA_SERVER_IP_INPUT,outbound.getString("server"))
                .putExtra(VpnConnectionService.EXTRA_SERVER_PORT,outbound.getInt("server_port"))
                .putExtra(VpnConnectionService.EXTRA_PROTOCOL,"VLESS")
                .putExtra(VpnConnectionService.EXTRA_PROFILE_NAME,"Temporary DNS diagnostic")
                .putExtra(VpnConnectionService.EXTRA_PROFILE_ID,"temporary-dns-diagnostic")
                .putExtra(VpnConnectionService.EXTRA_V2RAY_UUID,outbound.getString("uuid"))
                .putExtra(VpnConnectionService.EXTRA_V2RAY_NETWORK,"xhttp")
                .putExtra(VpnConnectionService.EXTRA_V2RAY_TYPE,transport.optString("mode","stream-up"))
                .putExtra(VpnConnectionService.EXTRA_V2RAY_PATH,transport.optString("path"))
                .putExtra(VpnConnectionService.EXTRA_V2RAY_HOST,transport.optString("host"))
                .putExtra(VpnConnectionService.EXTRA_V2RAY_TLS,"tls")
                .putExtra(VpnConnectionService.EXTRA_V2RAY_SNI,tls.optString("server_name"))
                .putExtra(VpnConnectionService.EXTRA_V2RAY_FP,tls.optJSONObject("utls")?.optString("fingerprint")?:"chrome")
                .putExtra(VpnConnectionService.EXTRA_SKIP_CERT_VERIFY,false)
                .putExtra("bypass_iran",false).putExtra("bypass_private_ips",false)
            tls.optJSONArray("alpn")?.let { alpn ->
                intent.putExtra(VpnConnectionService.EXTRA_V2RAY_ALPN,(0 until alpn.length()).joinToString(",") { alpn.getString(it) })
            }
            com.vpnapp.vpn.singbox.TunnelDnsSettings("https://doh.rahanetmci.com/dns-query",false).writeTo(intent)
            context.startForegroundService(intent)
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
            while(vpn()==null && System.nanoTime()<deadline) Thread.sleep(100)
            val network=checkNotNull(vpn()) { "VPN interface did not appear" }
            val answers=network.getAllByName("www.youtube.com").map { it.hostAddress }
            assertTrue("System DNS must use the chosen DoH",answers.contains("91.107.253.167"))
            val raw=network.socketFactory.createSocket()
            raw.use {
                raw.connect(InetSocketAddress(InetAddress.getByName("91.107.253.167"),443),15000)
                val factory=SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(raw,"www.youtube.com",443,true) as SSLSocket).use { socket ->
                    socket.soTimeout=20000
                    socket.sslParameters=socket.sslParameters.apply {
                        endpointIdentificationAlgorithm="HTTPS";serverNames=listOf(SNIHostName("www.youtube.com"))
                    }
                    socket.startHandshake()
                    socket.outputStream.write("GET / HTTP/1.1\r\nHost: www.youtube.com\r\nUser-Agent: Mozilla/5.0\r\nAccept-Language: en-US\r\nConnection: close\r\n\r\n".toByteArray())
                    socket.outputStream.flush()
                    val response=socket.inputStream.bufferedReader().readText()
                    val countries=Regex("\"gl\"\\s*:\\s*\"([A-Z]{2})\"").findAll(response).map { it.groupValues[1] }.toSet()
                    File(context.cacheDir,"dns-service-results.json").writeText(JSONObject()
                        .put("transport","real Android VPN service / TUN")
                        .put("dns_answers",org.json.JSONArray(answers))
                        .put("http_status_line",response.lineSequence().first())
                        .put("youtube_client_gl",org.json.JSONArray(countries.toList())).toString(2))
                    assertTrue(response.lineSequence().first().contains("200"))
                    assertTrue("Real VPN service should use US relay",countries.contains("US"))
                }
            }
        } finally {
            context.startService(Intent(context,VpnConnectionService::class.java).setAction(VpnConnectionService.ACTION_DISCONNECT))
            val end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            while(vpn()!=null && System.nanoTime()<end) Thread.sleep(100)
            prefs.edit().apply { if(oldUrl==null) remove(TunnelDns.URL_KEY) else putString(TunnelDns.URL_KEY,oldUrl) }
                .putBoolean(TunnelDns.FAKE_IP_KEY,oldFake).commit()
            input.delete()
            assertNull("Disconnect must remove the VPN interface",vpn())
        }
    }
}

package com.vpnapp

import android.app.Application
import android.util.Base64
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.data.*
import com.vpnapp.viewmodel.VpnViewModel
import com.vpnapp.vpn.singbox.*
import io.nekohasekai.libbox.*
import org.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.*
import java.net.*
import javax.net.ssl.*

class TunnelDnsRuntimeTest {
    @Test fun savingDnsSelectsRealAnswersAndPersistsTheMode() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val app=inst.targetContext.applicationContext as Application
        val prefs=app.getSharedPreferences("vpn_app_prefs",0)
        val saved=prefs.all; val store=ViewModelStore()
        try {
            prefs.edit().putBoolean(TunnelDns.FAKE_IP_KEY,true).commit()
            inst.runOnMainSync {
                val vm=VpnViewModel(app); store.put("first",vm)
                vm.setTunnelDnsUrl("https://doh.rahanetmci.com/dns-query")
                assertFalse(vm.useFakeIp.value)
                assertFalse(prefs.getBoolean(TunnelDns.FAKE_IP_KEY,true))
                assertTrue(runCatching { vm.setTunnelDnsUrl("http://invalid.example/") }.isFailure)
                assertEquals("https://doh.rahanetmci.com/dns-query",vm.tunnelDnsUrl.value)
                val restored=VpnViewModel(app); store.put("second",restored)
                assertFalse(restored.useFakeIp.value)
                restored.setUseFakeIp(true); assertTrue(restored.useFakeIp.value)
            }
        } finally {
            inst.runOnMainSync { store.clear() }
            restore(prefs,saved)
        }
    }

    // User-authorized fixture is supplied only to debug cache; no profile is in the app/test APK.
    @Test fun nativeCoreQueriesTheChosenResolverAndSwitchesOnReload() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val input=File(context.cacheDir,"dns-diagnostics.private.txt")
        assumeTrue("Requires private subscription fixture",input.isFile)
        val prefs=context.getSharedPreferences("vpn_app_prefs",0); val saved=prefs.all
        var server: CommandServer?=null
        val results=JSONArray()
        try {
            val raw=String(Base64.decode(input.readText().trim(),Base64.DEFAULT),Charsets.UTF_8)
            val profile=XHttpModePolicy.apply(raw.lines().mapNotNull(V2RayLinkParser::parseLink).first(),true)
                .copy(bypassIranRoutes=false,bypassPrivateIps=false)
            val port=DatagramSocket(0).use { it.localPort }
            Libbox.setup(SetupOptions().apply {
                setBasePath(context.filesDir.absolutePath);setWorkingPath(context.cacheDir.absolutePath)
                setTempPath(context.cacheDir.absolutePath)
            })
            val handler = object : CommandServerHandler {
                override fun connectSSHAgent(): Int = -1
                override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()
                override fun serviceReload() {}
                override fun serviceStop() {}
                override fun setSystemProxyEnabled(enabled: Boolean) {}
                override fun triggerNativeCrash() {}
                override fun writeDebugMessage(message: String?) {}
            }

            val platformInterface = object : PlatformInterface {
                override fun autoDetectInterfaceControl(fd: Int) {}
                override fun checkPlatformShell() {}
                override fun clearDNSCache() {}
                override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {}
                override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}
                override fun createBridge(options: BridgeOptions?): BridgeSession? = null
                override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String?, sourcePort: Int, destinationAddress: String?, destinationPort: Int): ConnectionOwner = ConnectionOwner()
                override fun getInterfaces(): NetworkInterfaceIterator? = null
                override fun includeAllNetworks(): Boolean = false
                override fun localDNSTransport(): LocalDNSTransport? = null
                override fun lookupSFTPServer(): String = ""
                override fun lookupUser(name: String?): PlatformUser? = null
                override fun openShellSession(user: PlatformUser?, command: String?, args: StringIterator?, workingDirectory: String?, pty: Int, ptyWindow: Int): ShellSession? = null
                override fun openTun(options: TunOptions?): Int = -1
                override fun readSystemSSHHostKey(): String = ""
                override fun readWIFIState(): WIFIState? = null
                override fun registerMyInterface(name: String?) {}
                override fun sendNotification(notification: Notification?) {}
                override fun cancelNotification(identifier: String?, id: Int) {}
                override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {}
                override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}
                override fun tailscaleHostname(): String = ""
                override fun underNetworkExtension(): Boolean = false
                override fun usePlatformAutoDetectInterfaceControl(): Boolean = false
                override fun usePlatformBridge(): Boolean = false
                override fun usePlatformShell(): Boolean = false
                override fun useProcFS(): Boolean = false
            }


            server=CommandServer(handler,platformInterface);server.start()
            for ((label,url,fake) in listOf(
                Triple("fakeip", "https://doh.rahanetmci.com/dns-query",true),
                Triple("custom-real", "https://doh.rahanetmci.com/dns-query",false),
                Triple("public-real",TunnelDns.DEFAULT_URL,false),
                Triple("custom-reloaded", "https://doh.rahanetmci.com/dns-query",false))) {
                prefs.edit().putString(TunnelDns.URL_KEY,url).putBoolean(TunnelDns.FAKE_IP_KEY,fake).commit()
                val config=JSONObject(SingBoxConfigGenerator.generate(profile,context))
                // Replace only TUN transport with a loopback UDP listener. Keep production DNS/rules/outbounds.
                config.put("inbounds",JSONArray().put(JSONObject().put("type","direct").put("tag","dns-test")
                    .put("listen","127.0.0.1").put("listen_port",port).put("network","udp")
                    .put("override_address","8.8.8.8").put("override_port",53)))
                config.getJSONObject("route").put("auto_detect_interface",false).put("override_android_vpn",false)
                config.remove("experimental")
                Libbox.checkConfig(config.toString())
                server.startOrReloadService(config.toString(),OverrideOptions())
                for (name in listOf("www.youtube.com","youtubei.googleapis.com","music.youtube.com")) {
                    val packet=query(name)
                    val ips=DatagramSocket().use { sock ->
                        sock.soTimeout=20000
                        sock.send(DatagramPacket(packet,packet.size,InetAddress.getByName("127.0.0.1"),port))
                        val response=DatagramPacket(ByteArray(4096),4096);sock.receive(response)
                        answers(response.data.copyOf(response.length))
                    }
                    results.put(JSONObject().put("mode",label).put("domain",name).put("ipv4",JSONArray(ips)))
                    assertTrue("DNS answer must exist: $label / $name",ips.isNotEmpty())
                    if(fake) assertTrue(ips.all { it.startsWith("198.18.") || it.startsWith("198.19.") })
                    else assertTrue(ips.none { it.startsWith("198.18.") || it.startsWith("198.19.") })
                    if(label.startsWith("custom")) assertTrue("Expected anti-sanction relay answer",ips.contains("91.107.253.167"))
                    if(label=="public-real") assertFalse(ips.contains("91.107.253.167"))
                }
            }
            // Private DNS on Android is DoT with a hostname, not a DoH URL.
            val dot=JSONObject().put("transport","DoT").put("host","doh.rahanetmci.com")
            try {
                Socket().use { raw ->
                    raw.connect(InetSocketAddress("doh.rahanetmci.com",853),8000)
                    dot.put("remote_ip",raw.inetAddress.hostAddress)
                    val factory=SSLSocketFactory.getDefault() as SSLSocketFactory
                    (factory.createSocket(raw,"doh.rahanetmci.com",853,true) as SSLSocket).use { tls ->
                        tls.soTimeout=8000;tls.sslParameters=tls.sslParameters.apply {
                            endpointIdentificationAlgorithm="HTTPS"
                            serverNames=listOf(SNIHostName("doh.rahanetmci.com"))
                        }
                        tls.startHandshake();dot.put("tls_verified",true)
                        val packet=query("www.youtube.com")
                        // Match Android's resolver: compose the complete frame in one TLS write.
                        val frame=ByteArrayOutputStream()
                        DataOutputStream(frame).apply { writeShort(packet.size);write(packet) }
                        tls.outputStream.write(frame.toByteArray());tls.outputStream.flush()
                        val reader=DataInputStream(tls.inputStream);val response=ByteArray(reader.readUnsignedShort());reader.readFully(response)
                        dot.put("ipv4",JSONArray(answers(response)))
                    }
                }
            } catch(e:Exception) { dot.put("error",e.javaClass.simpleName+": "+e.message) }
            results.put(dot)
        } finally {
            File(context.cacheDir,"dns-diagnostics-results.json").writeText(results.toString(2))
            runCatching { server?.closeService() };runCatching { server?.close() }
            restore(prefs,saved);input.delete()
        }
    }


    @Test fun nativeGoogleTrafficUsesDnsIpsIncludingCachedFakeIpsAndProbes() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val input=File(context.cacheDir,"dns-destination.private.json")
        assumeTrue("Requires private France outbound fixture",input.isFile)
        val prefs=context.getSharedPreferences("vpn_app_prefs",0);val saved=prefs.all
        val cache=File(context.cacheDir,"dns-destination-cache.db")
        val log=File(context.cacheDir,"dns-destination.private.log")
        val results=JSONArray();var server:CommandServer?=null
        try {
            val outbound=JSONObject(input.readText()).put("tag","proxy")
            val profile=VpnProfile(name="France diagnostic",serverAddress=outbound.getString("server"),
                port=outbound.getInt("server_port"),protocol=VpnProtocol.VLESS,
                v2rayUuid=outbound.getString("uuid"),v2rayTls="tls",
                v2raySni=outbound.getJSONObject("tls").optString("server_name"),
                bypassIranRoutes=false,bypassPrivateIps=false)
            val port=java.net.ServerSocket(0).use { it.localPort }
            val dnsPort=DatagramSocket(0).use { it.localPort }
            val probe=ProbeEndpoint.create()
            Libbox.setup(SetupOptions().apply {
                setBasePath(context.filesDir.absolutePath);setWorkingPath(context.cacheDir.absolutePath)
                setTempPath(context.cacheDir.absolutePath)
            })
            val handler = object : CommandServerHandler {
                override fun connectSSHAgent(): Int = -1
                override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()
                override fun serviceReload() {}
                override fun serviceStop() {}
                override fun setSystemProxyEnabled(enabled: Boolean) {}
                override fun triggerNativeCrash() {}
                override fun writeDebugMessage(message: String?) {}
            }

            val platformInterface = object : PlatformInterface {
                override fun autoDetectInterfaceControl(fd: Int) {}
                override fun checkPlatformShell() {}
                override fun clearDNSCache() {}
                override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {}
                override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}
                override fun createBridge(options: BridgeOptions?): BridgeSession? = null
                override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String?, sourcePort: Int, destinationAddress: String?, destinationPort: Int): ConnectionOwner = ConnectionOwner()
                override fun getInterfaces(): NetworkInterfaceIterator? = null
                override fun includeAllNetworks(): Boolean = false
                override fun localDNSTransport(): LocalDNSTransport? = null
                override fun lookupSFTPServer(): String = ""
                override fun lookupUser(name: String?): PlatformUser? = null
                override fun openShellSession(user: PlatformUser?, command: String?, args: StringIterator?, workingDirectory: String?, pty: Int, ptyWindow: Int): ShellSession? = null
                override fun openTun(options: TunOptions?): Int = -1
                override fun readSystemSSHHostKey(): String = ""
                override fun readWIFIState(): WIFIState? = null
                override fun registerMyInterface(name: String?) {}
                override fun sendNotification(notification: Notification?) {}
                override fun cancelNotification(identifier: String?, id: Int) {}
                override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {}
                override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}
                override fun tailscaleHostname(): String = ""
                override fun underNetworkExtension(): Boolean = false
                override fun usePlatformAutoDetectInterfaceControl(): Boolean = false
                override fun usePlatformBridge(): Boolean = false
                override fun usePlatformShell(): Boolean = false
                override fun useProcFS(): Boolean = false
            }



            server=CommandServer(handler,platformInterface);server.start()
            fun reload(fake:Boolean) {
                prefs.edit().putString(TunnelDns.URL_KEY,"https://doh.rahanetmci.com/dns-query")
                    .putBoolean(TunnelDns.FAKE_IP_KEY,fake).commit()
                val config=JSONObject(SingBoxConfigGenerator.generate(profile,context,probe))
                config.getJSONArray("outbounds").put(0,outbound)
                val inbounds=config.getJSONArray("inbounds");val replacement=JSONArray().put(inbounds.getJSONObject(0))
                replacement.put(JSONObject().put("type","mixed").put("tag","client")
                    .put("listen","127.0.0.1").put("listen_port",port))
                replacement.put(JSONObject().put("type","direct").put("tag","dns-test")
                    .put("listen","127.0.0.1").put("listen_port",dnsPort).put("network","udp")
                    .put("override_address","8.8.8.8").put("override_port",53))
                config.put("inbounds",replacement)
                config.getJSONObject("route").put("auto_detect_interface",false).put("override_android_vpn",false)
                config.getJSONObject("experimental").getJSONObject("cache_file").put("path",cache.absolutePath)
                config.put("log",JSONObject().put("level","debug").put("output",log.absolutePath))
                Libbox.checkConfig(config.toString());server!!.startOrReloadService(config.toString(),OverrideOptions())
                File(context.cacheDir,"dns-destination-config.private.json").writeText(config.toString())
            }
            fun lookup(name:String):String = DatagramSocket().use { sock ->
                val bytes=query(name);sock.soTimeout=15000
                sock.send(DatagramPacket(bytes,bytes.size,InetAddress.getByName("127.0.0.1"),dnsPort))
                val response=DatagramPacket(ByteArray(4096),4096);sock.receive(response)
                answers(response.data.copyOf(response.length)).first()
            }
            fun connect(address:String):Socket {
                val raw=Socket();raw.soTimeout=15000;raw.connect(InetSocketAddress("127.0.0.1",port),4000)
                val output=raw.outputStream;val reader=DataInputStream(raw.inputStream)
                output.write(byteArrayOf(5,1,0));output.flush();require(reader.readUnsignedByte()==5 && reader.readUnsignedByte()==0)
                val ip=runCatching { if(address.matches(Regex("[0-9.]+"))) InetAddress.getByName(address).address else null }.getOrNull()
                val target=if(ip!=null) byteArrayOf(1)+ip else address.toByteArray().let { byteArrayOf(3,it.size.toByte())+it }
                output.write(byteArrayOf(5,1,0)+target+byteArrayOf(1,187.toByte()));output.flush()
                require(reader.readUnsignedByte()==5 && reader.readUnsignedByte()==0) { "SOCKS connect failed" }
                reader.readUnsignedByte();val size=when(reader.readUnsignedByte()) { 1->4;3->reader.readUnsignedByte();4->16;else->error("SOCKS reply") }
                reader.readFully(ByteArray(size+2));return raw
            }
            fun fetch(label:String,name:String,address:String?,appProbe:Boolean=false):JSONObject {
                val raw=if(appProbe) probe.connect(name,443) else connect(address?:name)
                raw.use {
                    val factory=SSLSocketFactory.getDefault() as SSLSocketFactory
                    (factory.createSocket(raw,name,443,true) as SSLSocket).use { tls ->
                        tls.soTimeout=15000;tls.sslParameters=tls.sslParameters.apply {
                            endpointIdentificationAlgorithm="HTTPS";serverNames=listOf(SNIHostName(name))
                        };tls.startHandshake()
                        tls.outputStream.write(("GET / HTTP/1.1\r\nHost: "+name+"\r\nUser-Agent: Mozilla/5.0\r\nAccept-Language: en-US,en;q=0.9\r\nConnection: close\r\n\r\n").toByteArray());tls.outputStream.flush()
                        val body=tls.inputStream.bufferedReader().readText()
                        val country=Regex("\"gl\"\\s*:\\s*\"([A-Z]{2})\"").findAll(body).map { it.groupValues[1] }.toSet()
                        return JSONObject().put("mode",label).put("domain",name).put("client_destination",address?:"domain")
                            .put("http_status_line",body.lineSequence().first()).put("youtube_client_gl",JSONArray(country.toList()))
                            .put("response_bytes",body.length)
                    }
                }
            }
            reload(true)
            val cached=lookup("www.youtube.com")
            val control=fetch("fakeip-control","www.youtube.com",cached);results.put(control)
            assertTrue("Control should use France region",control.getJSONArray("youtube_client_gl").toString().contains("FR"))
            reload(false)
            val real=lookup("www.youtube.com");assertEquals("91.107.253.167",real)
            for ((label,address) in listOf("real-ip" to real,"cached-fake-ip" to cached,"domain" to null)) {
                val response=fetch(label,"www.youtube.com",address);results.put(response)
                assertTrue("DNS-selected relay should give US: $label",response.getJSONArray("youtube_client_gl").toString().contains("US"))
            }
            val probeResult=fetch("app-probe","www.youtube.com",null,true);results.put(probeResult)
            assertTrue(probeResult.getJSONArray("youtube_client_gl").toString().contains("US"))
            val gemini=fetch("real-ip","gemini.google.com",lookup("gemini.google.com"));results.put(gemini)
            assertTrue(gemini.getString("http_status_line").contains("200"))
            val trace=log.readText().lineSequence().filter { it.contains("outbound connection to") || it.contains("sniffed protocol:") }.toList()
            results.put(JSONObject().put("destination_trace",JSONArray(trace)))
            assertTrue(trace.any { it.contains("outbound/vless[proxy]") && it.contains("91.107.253.167:443") })
            assertFalse(trace.any { it.contains("outbound/direct[direct]") && it.contains("91.107.253.167:443") })
        } finally {
            File(context.cacheDir,"dns-destination-results.json").writeText(results.toString(2))
            runCatching { server?.closeService() };runCatching { server?.close() }
            restore(prefs,saved);input.delete();cache.delete()
            File(context.cacheDir,"dns-destination-config.private.json").delete()
        }
    }

    private fun restore(prefs:android.content.SharedPreferences,saved:Map<String,*>) {
        val edit=prefs.edit()
        for(key in listOf(TunnelDns.URL_KEY,TunnelDns.FAKE_IP_KEY)) when(val value=saved[key]) {
            is String -> edit.putString(key,value)
            is Boolean -> edit.putBoolean(key,value)
            else -> edit.remove(key)
        }
        edit.commit()
    }
    private fun query(name:String):ByteArray {
        val bytes=ByteArrayOutputStream();val out=DataOutputStream(bytes)
        for(n in listOf(431,256,1,0,0,0)) out.writeShort(n)
        for(label in name.split('.')) { out.writeByte(label.length);out.write(label.toByteArray()) }
        out.writeByte(0);out.writeShort(1);out.writeShort(1);return bytes.toByteArray()
    }
    private fun answers(data:ByteArray):List<String> {
        fun u16(i:Int)=((data[i].toInt() and 255) shl 8) or (data[i+1].toInt() and 255)
        fun skip(start:Int):Int { var p=start;while(data[p].toInt()!=0) {
            val n=data[p].toInt() and 255;if(n and 192==192)return p+2;p+=n+1
        };return p+1 }
        require(u16(2) and 15==0) { "DNS rcode ${u16(2) and 15}" }
        var pos=12;repeat(u16(4)) { pos=skip(pos)+4 };val ips=mutableListOf<String>()
        repeat(u16(6)) { pos=skip(pos);val type=u16(pos);val length=u16(pos+8);pos+=10
            if(type==1 && length==4) ips.add(InetAddress.getByAddress(data.copyOfRange(pos,pos+4)).hostAddress!!)
            pos+=length
        };return ips
    }
}

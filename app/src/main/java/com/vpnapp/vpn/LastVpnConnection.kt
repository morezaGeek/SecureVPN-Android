package com.vpnapp.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import com.vpnapp.data.ProfileRepository
import java.io.File

/** Private, non-backed-up connection state used only when Android starts Always-on VPN. */
object LastVpnConnection {
    private fun file(context: Context)=AtomicFile(File(context.noBackupFilesDir,"last-vpn-connection"))
    fun save(context: Context,intent: Intent) {
        val target=file(context);val stream=target.startWrite()
        try { stream.write(intent.toUri(Intent.URI_INTENT_SCHEME).toByteArray());target.finishWrite(stream) }
        catch(error: Exception) { target.failWrite(stream);throw error }
    }
    fun restore(context: Context): Intent? = runCatching {
        val intent=Intent.parseUri(String(file(context).readFully()),Intent.URI_INTENT_SCHEME)
        val id=intent.getStringExtra(VpnConnectionService.EXTRA_PROFILE_ID)
        if(ProfileRepository.getInstance(context).loadProfiles().none { it.id==id }) return null
        intent.component=ComponentName(context,VpnConnectionService::class.java)
        intent.action=VpnConnectionService.ACTION_CONNECT
        val prefs=context.getSharedPreferences("vpn_app_prefs",0)
        com.vpnapp.vpn.singbox.TunnelDnsSettings(prefs.getString(com.vpnapp.vpn.singbox.TunnelDns.URL_KEY,com.vpnapp.vpn.singbox.TunnelDns.DEFAULT_URL)!!,
            prefs.getBoolean(com.vpnapp.vpn.singbox.TunnelDns.FAKE_IP_KEY,true)).writeTo(intent)
        LanShareSettings.read(context).writeTo(intent)
        intent.putExtra("bypass_iran",prefs.getBoolean("bypass_iran",true))
            .putExtra("bypass_private_ips",prefs.getBoolean("bypass_private_ips",true))
            .putExtra(VpnConnectionService.EXTRA_MTU,prefs.getInt("mtu_size",1400))
            .putExtra(VpnConnectionService.EXTRA_BUFFER_SIZE,prefs.getInt("buffer_size",2*1024*1024))
            .putExtra(VpnConnectionService.EXTRA_TCP_NODELAY,prefs.getBoolean("tcp_no_delay",true))
            .putStringArrayListExtra(VpnConnectionService.EXTRA_EXCLUDED_APPS,ArrayList(prefs.getStringSet("excluded_apps",emptySet()).orEmpty()))
        intent
    }.getOrNull()
}

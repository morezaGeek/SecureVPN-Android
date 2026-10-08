package com.vpnapp.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vpnapp.MainActivity
import com.vpnapp.R
import com.vpnapp.vpn.VpnConnectionService.Companion.ACTION_DISCONNECT
import com.vpnapp.vpn.VpnConnectionService.Companion.ACTION_RESTORE_NOTIFICATION

object VpnNotificationFactory {
    fun create(context: Context, channelId: String, profileName: String, serverIp: String,
               status: String, countryCode: String?, notificationSession: Long): Notification {

        // Tap notification to open app
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // Disconnect action button
        val disconnectIntent = Intent(context, VpnConnectionService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            context,
            1,
            disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val contentText = if (countryCode != null) "Public IP: $serverIp ($countryCode)" else "Server: $serverIp"
        val dismissedIntent = PendingIntent.getService(context, 2,
            Intent(context, VpnConnectionService::class.java).apply {
                action = ACTION_RESTORE_NOTIFICATION
                data = android.net.Uri.parse("securevpn://notification/$notificationSession")
                putExtra("notification_session", notificationSession)
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        
        return NotificationCompat.Builder(context, channelId)
            .setContentTitle("$profileName - $status")
            .setContentText(contentText)
            .setSubText(profileName) // Show profile name in notification subtext
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setDeleteIntent(dismissedIntent)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Disconnect",
                disconnectPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
}

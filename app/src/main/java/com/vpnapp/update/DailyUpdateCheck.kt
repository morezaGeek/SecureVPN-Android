package com.vpnapp.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vpnapp.BuildConfig
import com.vpnapp.MainActivity
import com.vpnapp.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DailyUpdateCheck : JobService() {
    private var check: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        check = CoroutineScope(Dispatchers.Main).launch {
            var retry = false
            try {
                val prefs = preferences(this@DailyUpdateCheck)
                val release = GitHubUpdate.latest(BuildConfig.VERSION_NAME)
                if (release == null) {
                    prefs.edit().remove("latest").apply()
                } else {
                    prefs.edit().putString("latest", release.version).apply()
                    if (prefs.getString("notified", null) != release.version && notifyUpdate(release.version)) {
                        prefs.edit().putString("notified", release.version).apply()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                retry = true
            }
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        check?.cancel()
        return true
    }

    override fun onDestroy() {
        check?.cancel()
        super.onDestroy()
    }

    private fun notifyUpdate(version: String): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(NotificationChannel("app-updates", "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(this, MainActivity::class.java).putExtra(OPEN_UPDATES, true)
        val pending = PendingIntent.getActivity(this, jobId, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(jobId, NotificationCompat.Builder(this, "app-updates")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("SecureVPN $version is available")
            .setContentText("Tap to see what’s new and download the update.")
            .setContentIntent(pending).setAutoCancel(true).build())
        return true
    }

    companion object {
        internal const val OPEN_UPDATES = "com.vpnapp.OPEN_UPDATES"
        private const val jobId = 5418
        private const val dayMillis = 24 * 60 * 60 * 1000L

        internal fun preferences(context: Context) = context.getSharedPreferences("github_updates", MODE_PRIVATE)

        internal fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(jobId) != null) return
            scheduler.schedule(JobInfo.Builder(jobId, ComponentName(context, DailyUpdateCheck::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(dayMillis)
                .setPersisted(true)
                .build())
        }
    }
}

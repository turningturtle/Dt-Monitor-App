package com.turningturtle.dtmonitor

import android.app.JobInfo
import android.app.JobScheduler
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Background scheduling for DT Monitor.
 *
 * A one-shot JobScheduler job is used instead of AlarmManager's repeating alarm.
 * After every successful attempt the job schedules the next one for ~10 minutes.
 * A persistent ConnectivityManager PendingIntent watches for a network becoming
 * available, so a device coming back online can trigger a check immediately.
 */
object Scheduler {
    private const val CHECK_JOB_ID = 7410
    private const val NETWORK_REQUEST_CODE = 7411
    private const val INTERVAL_MS = 10L * 60L * 1000L

    fun schedule(context: Context) {
        scheduleNext(context.applicationContext)
        ensureNetworkWatcher(context.applicationContext)
    }

    fun scheduleNext(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        scheduler.cancel(CHECK_JOB_ID)
        val component = ComponentName(context, MonitorJobService::class.java)
        val job = JobInfo.Builder(CHECK_JOB_ID, component)
            .setMinimumLatency(INTERVAL_MS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        scheduler.schedule(job)
    }

    fun ensureNetworkWatcher(context: Context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        val intent = Intent(context, NetworkReceiver::class.java).setAction(NetworkReceiver.ACTION_NETWORK_AVAILABLE)
        val pending = PendingIntent.getBroadcast(
            context,
            NETWORK_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val request = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivity.registerNetworkCallback(request, pending) }
    }

    fun cancel(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler?.cancel(CHECK_JOB_ID)
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        val intent = Intent(context, NetworkReceiver::class.java).setAction(NetworkReceiver.ACTION_NETWORK_AVAILABLE)
        val pending = PendingIntent.getBroadcast(
            context,
            NETWORK_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching { connectivity.unregisterNetworkCallback(pending) }
    }
}

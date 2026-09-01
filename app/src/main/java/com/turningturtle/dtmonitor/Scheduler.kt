package com.turningturtle.dtmonitor

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Lightweight background scheduling for DT Monitor.
 *
 * A one-shot JobScheduler job is used instead of AlarmManager's repeating alarm.
 * The two alternating job IDs let a running job schedule its successor without
 * cancelling itself. A persistent ConnectivityManager PendingIntent watches for
 * a network becoming available so a device coming back online can trigger a check.
 */
object Scheduler {
    private const val CHECK_JOB_A = 7410
    private const val CHECK_JOB_B = 7412
    private const val NETWORK_REQUEST_CODE = 7411
    private const val INTERVAL_MS = 10L * 60L * 1000L
    private const val RECENT_CHECK_GUARD_MS = 90_000L
    const val EXTRA_SOURCE = "source"
    const val SOURCE_TIMER = "background timer"
    const val SOURCE_NETWORK = "internet connected"

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val scheduler = appContext.getSystemService(JobScheduler::class.java)
        val a = scheduler?.getPendingJob(CHECK_JOB_A)
        val b = scheduler?.getPendingJob(CHECK_JOB_B)
        val recent = System.currentTimeMillis() - MonitorPrefs.lastCheck(appContext) < RECENT_CHECK_GUARD_MS
        if (a == null && b == null && !recent) {
            scheduleNew(appContext, CHECK_JOB_A, INTERVAL_MS, SOURCE_TIMER)
        }
        ensureNetworkWatcher(appContext)
    }

    fun scheduleNext(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        scheduler.cancel(CHECK_JOB_A)
        scheduler.cancel(CHECK_JOB_B)
        scheduleNew(context.applicationContext, CHECK_JOB_A, INTERVAL_MS, SOURCE_TIMER)
    }

    fun scheduleNext(context: Context, currentJobId: Int) {
        val nextId = if (currentJobId == CHECK_JOB_A) CHECK_JOB_B else CHECK_JOB_A
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        scheduler.cancel(nextId)
        scheduleNew(context.applicationContext, nextId, INTERVAL_MS, SOURCE_TIMER)
    }

    fun scheduleImmediateCheck(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        scheduler.cancel(CHECK_JOB_A)
        scheduler.cancel(CHECK_JOB_B)
        scheduleNew(context.applicationContext, CHECK_JOB_A, 0L, SOURCE_NETWORK)
    }

    private fun scheduleNew(context: Context, jobId: Int, delayMs: Long, source: String) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val component = ComponentName(context, MonitorJobService::class.java)
        val job = JobInfo.Builder(jobId, component)
            .setMinimumLatency(delayMs)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setTransientExtras(android.os.Bundle().apply { putString(EXTRA_SOURCE, source) })
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
        scheduler?.cancel(CHECK_JOB_A)
        scheduler?.cancel(CHECK_JOB_B)
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

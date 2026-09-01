package com.turningturtle.dtmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors

/** Receives persistent ConnectivityManager PendingIntent callbacks. */
class NetworkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_NETWORK_AVAILABLE) return

        val pending = goAsync()
        val appContext = context.applicationContext
        executor.execute {
            try {
                // registerNetworkCallback() also reports the currently available network
                // immediately. Do not turn that initial callback into a duplicate check.
                if (MonitorPrefs.tryAcquireBackgroundCheck(appContext)) {
                    val outcome = try {
                        DtMonitor.check(appContext)
                    } catch (e: Exception) {
                        CheckOutcome(
                            System.currentTimeMillis(),
                            emptyList(),
                            MonitorPrefs.loadTargets(appContext).size,
                            e.message ?: "Network-triggered check failed."
                        )
                    }
                    MonitorPrefs.recordOutcome(appContext, outcome, "internet connected")
                    Scheduler.scheduleNext(appContext)
                }
                Scheduler.ensureNetworkWatcher(appContext)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_NETWORK_AVAILABLE = "com.turningturtle.dtmonitor.NETWORK_AVAILABLE"
        private val executor = Executors.newSingleThreadExecutor()
    }
}

package com.turningturtle.dtmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives persistent ConnectivityManager PendingIntent callbacks. */
class NetworkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_NETWORK_AVAILABLE) return

        // registerNetworkCallback() reports the currently available network immediately
        // when the watcher is installed. The cooldown prevents that initial callback
        // from becoming a duplicate check, while still allowing a real reconnect to
        // trigger a check as soon as the network becomes available.
        val lastCheck = MonitorPrefs.lastCheck(context)
        val now = System.currentTimeMillis()
        if (lastCheck == 0L || now - lastCheck >= 90_000L) {
            Scheduler.scheduleImmediateCheck(context.applicationContext)
        }
    }

    companion object {
        const val ACTION_NETWORK_AVAILABLE = "com.turningturtle.dtmonitor.NETWORK_AVAILABLE"
    }
}

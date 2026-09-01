package com.turningturtle.dtmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors

class MonitorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val appContext = context.applicationContext
        executor.execute {
            try {
                if (MonitorPrefs.loadTargets(appContext).isNotEmpty()) {
                    runCatching { DtMonitor.check(appContext) }
                }
            } finally { pending.finish() }
        }
    }

    companion object { private val executor = Executors.newSingleThreadExecutor() }
}

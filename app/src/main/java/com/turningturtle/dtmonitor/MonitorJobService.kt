package com.turningturtle.dtmonitor

import android.app.job.JobParameters
import android.app.job.JobService
import java.util.concurrent.Executors

class MonitorJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val appContext = applicationContext
        executor.execute {
            val outcome = try {
                DtMonitor.check(appContext)
            } catch (e: Exception) {
                CheckOutcome(
                    System.currentTimeMillis(),
                    emptyList(),
                    MonitorPrefs.loadTargets(appContext).size,
                    e.message ?: "Background check failed."
                )
            }

            MonitorPrefs.recordOutcome(appContext, outcome, "background")
            Scheduler.scheduleNext(appContext)
            Scheduler.ensureNetworkWatcher(appContext)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
    }
}

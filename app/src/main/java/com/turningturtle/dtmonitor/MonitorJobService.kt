package com.turningturtle.dtmonitor

import android.app.job.JobParameters
import android.app.job.JobService
import java.util.concurrent.Executors

class MonitorJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val appContext = applicationContext
        val source = params.transientExtras.getString(Scheduler.EXTRA_SOURCE) ?: Scheduler.SOURCE_TIMER
        executor.execute {
            if (MonitorPrefs.tryAcquireBackgroundCheck(appContext)) {
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
                MonitorPrefs.recordOutcome(appContext, outcome, source)
            }

            // Schedule the successor on the other job ID before finishing this one,
            // so the scheduler never has to cancel the job that is currently running.
            Scheduler.scheduleNext(appContext, params.jobId)
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

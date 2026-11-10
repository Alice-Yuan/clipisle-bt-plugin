package com.clipisle.app.bt

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.util.Log

/** API 34+: this job owns execution until all accepted work stops or finishes. */
class BtTransferJobService : JobService() {
    companion object {
        const val OWNER = "uidt"
        const val JOB_ID = 0xB7
        private const val TAG = "BtTransferJob"
        private const val POLICY_KEY = "unmeteredOnly"
        private const val GENERATION_KEY = "ownerGeneration"
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var active: BtTransferJobService? = null
        val isRunning: Boolean get() = active != null

        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= 34

        /** Only user actions may schedule. A snapshot or periodic tick must never call this. */
        fun schedule(context: Context, estimatedBytes: Long, unmeteredOnly: Boolean = true): Boolean {
            if (!isSupported()) return false
            return try {
                BtAndroidDispatch.call { scheduleOnMain(context, estimatedBytes, unmeteredOnly) }
            } catch (e: Exception) {
                Log.w(TAG, "UIDT scheduling rejected: ${e.javaClass.simpleName}")
                false
            }
        }

        private fun scheduleOnMain(context: Context, estimatedBytes: Long, unmeteredOnly: Boolean): Boolean {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            return try {
                val previous = scheduler.getPendingJob(JOB_ID)
                if (previous != null && previous.extras.getBoolean(POLICY_KEY, true) == unmeteredOnly) {
                    main.post { active?.updateEstimate(estimatedBytes) }
                    return true
                }
                val network = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .apply {
                        if (unmeteredOnly) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    }.build()
                val info = JobInfo.Builder(JOB_ID, ComponentName(context, BtTransferJobService::class.java))
                    .setRequiredNetwork(network)
                    .setUserInitiated(true)
                    .setExtras(PersistableBundle().apply {
                        putBoolean(POLICY_KEY, unmeteredOnly)
                        putLong(GENERATION_KEY, SystemClock.elapsedRealtimeNanos())
                    })
                    .setEstimatedNetworkBytes(estimate(estimatedBytes), JobInfo.NETWORK_BYTES_UNKNOWN.toLong())
                    .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build()
                scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
            } catch (e: Exception) {
                Log.w(TAG, "UIDT scheduling rejected: ${e.javaClass.simpleName}")
                false
            }
        }

        fun update(
            context: Context, taskId: String, title: String, text: String,
            progress: Int, indeterminate: Boolean = false,
        ) {
            main.post { active?.updateNotification(title, text, progress, indeterminate) }
        }

        fun finish(context: Context) {
            BtAndroidDispatch.call {
                active?.finishOwnedWork()
                runCatching { context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
            }
        }

        fun cancel(context: Context) = finish(context)

        private fun estimate(bytes: Long): Long =
            if (bytes >= 0) bytes else JobInfo.NETWORK_BYTES_UNKNOWN.toLong()
    }

    private var parameters: JobParameters? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        if (Build.VERSION.SDK_INT < 34 || params == null) return false
        parameters = params
        try {
            setNotification(params, BtNotifications.NOTIFICATION_ID,
                BtNotifications.build(this), JOB_END_NOTIFICATION_POLICY_REMOVE)
        } catch (e: Exception) {
            parameters = null
            Log.w(TAG, "UIDT notification failed: ${e.javaClass.simpleName}")
            BtRuntime.get(applicationContext).onExecutionStopped(OWNER, "notification_failed")
            return false
        }
        active = this
        BtRuntime.get(applicationContext).onExecutionStarted(OWNER)
        // Returning false would immediately relinquish execution ownership.
        return true
    }

    private fun updateNotification(title: String, text: String, progress: Int, indeterminate: Boolean) {
        val params = parameters ?: return
        if (Build.VERSION.SDK_INT < 34 || active !== this) return
        try {
            setNotification(params, BtNotifications.NOTIFICATION_ID,
                BtNotifications.build(this, title, text, progress, indeterminate),
                JOB_END_NOTIFICATION_POLICY_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "UIDT notification update failed: ${e.javaClass.simpleName}")
        }
    }

    private fun updateEstimate(bytes: Long) {
        val params = parameters ?: return
        if (Build.VERSION.SDK_INT >= 34) runCatching {
            updateEstimatedNetworkBytes(params, estimate(bytes), JobInfo.NETWORK_BYTES_UNKNOWN.toLong())
        }
    }

    private fun release(reason: String) {
        if (active !== this) return
        active = null
        BtRuntime.get(applicationContext).onExecutionStopped(OWNER, reason)
    }

    private fun finishOwnedWork() {
        val params = parameters ?: return
        parameters = null
        release("finished")
        jobFinished(params, false)
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        val current = parameters ?: return false
        // Start and stop are separate Binder messages, so JobParameters object identity differs.
        if (params == null || params.jobId != current.jobId ||
            params.extras.getLong(GENERATION_KEY) != current.extras.getLong(GENERATION_KEY)) return false
        parameters = null
        release("system_stop_${params.stopReason}")
        // Retrying a background restriction cannot fix the user's system setting.
        // Keep the checkpoint and require an explicit resume after the setting changes.
        return params.stopReason != JobParameters.STOP_REASON_USER &&
            params.stopReason != JobParameters.STOP_REASON_CANCELLED_BY_APP &&
            params.stopReason != JobParameters.STOP_REASON_BACKGROUND_RESTRICTION
    }

    override fun onNetworkChanged(params: JobParameters) {
        val current = parameters ?: return
        if (active !== this || params.jobId != current.jobId ||
            params.extras.getLong(GENERATION_KEY) != current.extras.getLong(GENERATION_KEY)) return
        // The framework gives us a new parcel with the replacement network. Keep it for
        // notification/estimate/finish calls; default-network policy owns native socket use.
        parameters = params
    }

    override fun onDestroy() {
        parameters = null
        release("service_destroyed")
        super.onDestroy()
    }
}

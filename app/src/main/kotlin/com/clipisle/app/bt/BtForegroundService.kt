package com.clipisle.app.bt

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/** API 26–33 execution owner. API 34+ must use the UIDT branch exclusively. */
class BtForegroundService : Service() {
    companion object {
        const val OWNER = "fgs"
        const val CHANNEL_ID = BtNotifications.CHANNEL_ID
        const val NOTIFICATION_ID = BtNotifications.NOTIFICATION_ID
        const val ACTION_START = "com.clipisle.app.bt.START"
        const val ACTION_STOP = "com.clipisle.app.bt.STOP"
        const val ACTION_PAUSE_ALL = "com.clipisle.app.bt.PAUSE_ALL"
        private const val TAG = "BtForeground"
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var active: BtForegroundService? = null
        val isRunning: Boolean get() = active != null

        fun start(
            context: Context, taskId: String, title: String, text: String,
            progress: Int = 0, indeterminate: Boolean = true,
        ): Boolean {
            if (Build.VERSION.SDK_INT >= 34) return false
            if (isRunning) {
                update(context, taskId, title, text, progress, indeterminate)
                return true
            }
            val intent = Intent(context, BtForegroundService::class.java).apply {
                action = ACTION_START
                putExtra("title", title)
                putExtra("text", text)
                putExtra("progress", progress)
                putExtra("indeterminate", indeterminate)
            }
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Foreground start rejected: ${e.javaClass.simpleName}")
                false
            }
        }

        fun update(
            context: Context, taskId: String, title: String, text: String,
            progress: Int, indeterminate: Boolean = false,
        ) {
            main.post { active?.updateNotification(title, text, progress, indeterminate) }
        }

        fun stop(context: Context) {
            BtAndroidDispatch.call {
                active?.stopOwnedWork("finished")
                runCatching { context.stopService(Intent(context, BtForegroundService::class.java)) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 34 || intent == null) {
            stopOwnedWork("unsupported_start")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_PAUSE_ALL) {
            BtRuntime.get(applicationContext).pauseAllRunning()
            return START_NOT_STICKY
        }
        if (intent.action != ACTION_START) {
            stopOwnedWork("stopped")
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = BtNotifications.build(this,
            intent.getStringExtra("title") ?: BtNotifications.title(this),
            intent.getStringExtra("text") ?: "",
            intent.getIntExtra("progress", 0),
            intent.getBooleanExtra("indeterminate", true))
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Foreground promotion rejected: ${e.javaClass.simpleName}")
            BtRuntime.get(applicationContext).onExecutionStopped(OWNER, "notification_failed")
            stopSelf()
            return START_NOT_STICKY
        }
        if (active !== this) {
            active = this
            BtRuntime.get(applicationContext).onExecutionStarted(OWNER)
        }
        return START_NOT_STICKY
    }

    private fun updateNotification(title: String, text: String, progress: Int, indeterminate: Boolean) {
        if (active !== this) return
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID,
                BtNotifications.build(this, title, text, progress, indeterminate))
        }.onFailure { Log.w(TAG, "Notification update failed: ${it.javaClass.simpleName}") }
    }

    private fun stopOwnedWork(reason: String) {
        if (active !== this) return
        active = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        BtRuntime.get(applicationContext).onExecutionStopped(OWNER, reason)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopOwnedWork("system_timeout")
        stopSelf()
    }

    override fun onDestroy() {
        stopOwnedWork("service_destroyed")
        super.onDestroy()
    }
}

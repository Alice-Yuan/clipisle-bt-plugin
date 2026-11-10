package com.clipisle.app.bt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale

/** Shared notification presentation; the pending action never starts an FGS on API 34+. */
internal object BtNotifications {
    const val CHANNEL_ID = "clipisle_bt"
    const val NOTIFICATION_ID = 0x8C1
    const val COMPLETION_NOTIFICATION_ID = 0x8C4
    const val ACTION_PAUSE = "com.clipisle.app.bt.PAUSE"

    private fun language(context: Context): String {
        val requested = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            .getString("flutter.app_language", "system")
        return if (requested in setOf("zh", "en", "ja", "ko")) requested!!
        else context.resources.configuration.locales[0]?.language ?: Locale.getDefault().language
    }

    fun title(context: Context): String = when (language(context)) {
        "en" -> "Torrent downloads"
        "ja" -> "トレントのダウンロード"
        "ko" -> "토렌트 다운로드"
        else -> "磁力下载"
    }

    private fun pause(context: Context): String = when (language(context)) {
        "en" -> "Pause"
        "ja" -> "一時停止"
        "ko" -> "일시 정지"
        else -> "暂停"
    }

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Updating an existing channel's name preserves the user's importance choice.
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, title(context), NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) })
    }

    fun build(
        context: Context, title: String = title(context), text: String = "",
        progress: Int = 0, indeterminate: Boolean = true,
    ): Notification {
        createChannel(context)
        val open = Intent().apply {
            setClassName("com.clipisle.app", "com.clipisle.app.MainActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pauseIntent = Intent(context, BtNotificationReceiver::class.java).apply { action = ACTION_PAUSE }
        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title.ifBlank { BtNotifications.title(context) })
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(context, 0x8C2, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(null, pause(context),
                PendingIntent.getBroadcast(context, 0x8C3, pauseIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
            .setProgress(if (indeterminate) 0 else 100,
                if (indeterminate) 0 else progress.coerceIn(0, 100), indeterminate)
            .build()
    }

    /** Best-effort delivery after the completion record is durably committed. */
    fun completed(context: Context, taskId: String, name: String, fileCount: Int, bytes: Long): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            if (!manager.areNotificationsEnabled()) return false
            createChannel(context)
            val locale = language(context)
            val completedTitle = when (locale) {
                "en" -> "Download complete"
                "ja" -> "ダウンロード完了"
                "ko" -> "다운로드 완료"
                else -> "下载完成"
            }
            val count = when (locale) {
                "en" -> if (fileCount == 1) "1 file" else "$fileCount files"
                "ja" -> "${fileCount}ファイル"
                "ko" -> "파일 ${fileCount}개"
                else -> "$fileCount 个文件"
            }
            val open = Intent().apply {
                setClassName("com.clipisle.app", "com.clipisle.app.MainActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val text = "$name\n$count · ${formatBytes(bytes)}"
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(completedTitle)
                .setContentText(name)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setCategory(Notification.CATEGORY_STATUS)
                .setOngoing(false)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(PendingIntent.getActivity(context, 0x8C5, open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .build()
            // Separate tag/ID from the progress owner: jobFinished must not remove completion.
            manager.notify("bt-complete:$taskId", COMPLETION_NOTIFICATION_ID, notification)
            true
        } catch (_: Exception) {
            // Revoked notifications or an OEM error never changes a verified download to failed.
            false
        }
    }

    private fun formatBytes(bytes: Long): String {
        var value = bytes.toDouble()
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var index = 0
        while (value >= 1000 && index < units.lastIndex) { value /= 1000; index++ }
        return if (index == 0) "$bytes B" else String.format(Locale.ROOT, "%.1f %s", value, units[index])
    }
}

/** Registered non-exported. This posts a native command without starting another owner. */
class BtNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != BtNotifications.ACTION_PAUSE) return
        // An active UIDT / FGS already owns process execution. The command is non-blocking.
        BtRuntime.get(context).pauseAllRunning()
    }
}

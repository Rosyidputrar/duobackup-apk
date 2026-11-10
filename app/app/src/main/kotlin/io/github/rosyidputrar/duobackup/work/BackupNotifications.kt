package io.github.rosyidputrar.duobackup.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import io.github.rosyidputrar.duobackup.R
import java.util.UUID

/** The ongoing notification shown while a backup runs. */
class BackupNotifications(private val context: Context) {
    fun foregroundInfo(workId: UUID): ForegroundInfo {
        ensureChannel()
        val notification = build(workId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun build(workId: UUID): Notification {
        val stop = WorkManager.getInstance(context).createCancelPendingIntent(workId)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_backup_title))
            .setContentText(context.getString(R.string.notification_backup_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .addAction(0, context.getString(R.string.action_stop), stop)
            .build()
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_backup),
            NotificationManager.IMPORTANCE_LOW
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID: String = "backup"
        const val NOTIFICATION_ID: Int = 1001
    }
}

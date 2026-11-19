package de.hr.mediathek.core.jobs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.work.ForegroundInfo
import de.hr.mediathek.MainActivity
import de.hr.mediathek.R

/**
 * Neutrale Benachrichtigung für Hintergrundarbeit: kein Dateiname, auf dem Sperrbildschirm privat.
 */
object JobNotifications {
    private const val CHANNEL_ID = "jobs"
    private const val NOTIFICATION_ID = 4711

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.job_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun foregroundInfo(context: Context, text: String, progress: Float?): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, build(context, text, progress), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    fun update(context: Context, text: String, progress: Float?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, build(context, text, progress))
    }

    private fun build(context: Context, text: String, progress: Float?): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.job_notification_title))
            .setContentText(text.ifBlank { context.getString(R.string.job_notification_working) })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(openApp)
        if (progress != null) {
            builder.setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }
}

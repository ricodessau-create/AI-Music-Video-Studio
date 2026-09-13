package com.example.aivideostudio.render

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

object RenderNotifications {

    const val CHANNEL_ID = "render_progress_channel"
    const val NOTIFICATION_ID = 4201

    fun buildNotification(context: Context, contentText: String, progressPercent: Int): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Musikvideo wird erstellt")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progressPercent.coerceIn(0, 100), progressPercent <= 0)
            .build()
    }

    fun buildForegroundInfo(context: Context, contentText: String, progressPercent: Int): ForegroundInfo {
        val notification = buildNotification(context, contentText, progressPercent)
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
}

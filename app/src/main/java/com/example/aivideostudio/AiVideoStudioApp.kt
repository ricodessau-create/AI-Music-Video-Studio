package com.example.aivideostudio

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.aivideostudio.render.RenderNotifications
import com.example.aivideostudio.util.CrashLogger

class AiVideoStudioApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(CrashLogger(applicationContext, defaultHandler))
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                RenderNotifications.CHANNEL_ID,
                "Musikvideo-Rendering",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Fortschritt beim Erstellen von Musikvideos"
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}

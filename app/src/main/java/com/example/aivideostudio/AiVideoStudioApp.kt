package com.example.aivideostudio

import android.app.Application
import com.example.aivideostudio.util.CrashLogger

class AiVideoStudioApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(CrashLogger(applicationContext, defaultHandler))
    }
}

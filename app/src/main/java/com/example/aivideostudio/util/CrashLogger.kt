package com.example.aivideostudio.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashLogger(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val logDirectory = File(context.getExternalFilesDir(null), "crash_logs")
            if (!logDirectory.exists()) {
                logDirectory.mkdirs()
            }
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.GERMANY).format(Date())
            val logFile = File(logDirectory, "crash_$timestamp.txt")

            val stringWriter = StringWriter()
            throwable.printStackTrace(PrintWriter(stringWriter))

            logFile.writeText(
                "Zeitpunkt: $timestamp\n" +
                    "Thread: ${thread.name}\n" +
                    "Fehler: ${throwable.message}\n\n" +
                    stringWriter.toString()
            )
        } catch (loggingException: Exception) {
            // Fehlerprotokollierung darf die eigentliche Absturzbehandlung nicht verhindern
        }

        defaultHandler?.uncaughtException(thread, throwable)
    }
}

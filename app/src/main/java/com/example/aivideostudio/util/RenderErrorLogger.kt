package com.example.aivideostudio.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RenderErrorLogger {

    fun logRenderFailure(context: Context, throwable: Throwable, contextLabel: String) {
        try {
            val logDirectory = File(context.getExternalFilesDir(null), "crash_logs")
            if (!logDirectory.exists()) {
                logDirectory.mkdirs()
            }
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.GERMANY).format(Date())
            val logFile = File(logDirectory, "render_error_${timestamp}.txt")

            val stringWriter = StringWriter()
            throwable.printStackTrace(PrintWriter(stringWriter))

            logFile.writeText(
                "Zeitpunkt: $timestamp\n" +
                    "Kontext: $contextLabel\n" +
                    "Fehler: ${throwable.message}\n\n" +
                    stringWriter.toString()
            )
        } catch (loggingException: Exception) {
            // Fehlerprotokollierung darf den eigentlichen Renderfehler nicht verdecken
        }
    }
}

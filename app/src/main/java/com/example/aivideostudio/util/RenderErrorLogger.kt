package com.example.aivideostudio.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RenderErrorLogger {

    private const val TAG = "AI_MUSIC_VIDEO_RENDER"

    fun logEvent(
        context: Context,
        message: String
    ) {
        try {
            val timestamp =
                SimpleDateFormat(
                    "yyyy-MM-dd_HH-mm-ss.SSS",
                    Locale.GERMANY
                ).format(Date())

            val line =
                "[$timestamp] $message"

            Log.d(
                TAG,
                line
            )

            appendToLogFile(
                context,
                line
            )
        } catch (exception: Exception) {
            Log.e(
                TAG,
                "Konnte Render-Ereignis nicht protokollieren.",
                exception
            )
        }
    }

    fun logRenderFailure(
        context: Context,
        throwable: Throwable,
        contextLabel: String
    ) {
        try {
            val timestamp =
                SimpleDateFormat(
                    "yyyy-MM-dd_HH-mm-ss",
                    Locale.GERMANY
                ).format(Date())

            val stackTraceWriter =
                StringWriter()

            throwable.printStackTrace(
                PrintWriter(stackTraceWriter)
            )

            val diagnostics =
                buildString {
                    appendLine(
                        "=================================================="
                    )
                    appendLine(
                        "AI MUSIC VIDEO STUDIO - RENDER FEHLER"
                    )
                    appendLine(
                        "=================================================="
                    )
                    appendLine()
                    appendLine(
                        "Zeitpunkt: $timestamp"
                    )
                    appendLine(
                        "Kontext: $contextLabel"
                    )
                    appendLine()

                    appendLine(
                        "==================== GERÄT ===================="
                    )
                    appendLine(
                        "Hersteller: ${Build.MANUFACTURER}"
                    )
                    appendLine(
                        "Modell: ${Build.MODEL}"
                    )
                    appendLine(
                        "Gerät: ${Build.DEVICE}"
                    )
                    appendLine(
                        "Produkt: ${Build.PRODUCT}"
                    )
                    appendLine(
                        "Board: ${Build.BOARD}"
                    )
                    appendLine(
                        "Android SDK: ${Build.VERSION.SDK_INT}"
                    )
                    appendLine(
                        "Android Release: ${Build.VERSION.RELEASE}"
                    )
                    appendLine(
                        "CPU ABI: ${Build.SUPPORTED_ABIS.joinToString()}"
                    )
                    appendLine()

                    appendLine(
                        "==================== FEHLER ==================="
                    )
                    appendLine(
                        "Exception: ${throwable.javaClass.name}"
                    )
                    appendLine(
                        "Message: ${throwable.message}"
                    )
                    appendLine()

                    appendLine(
                        "================== CAUSE CHAIN ================="
                    )

                    var cause =
                        throwable.cause

                    var causeIndex =
                        1

                    if (cause == null) {
                        appendLine(
                            "Keine weitere Cause vorhanden."
                        )
                    }

                    while (cause != null) {
                        appendLine(
                            "#$causeIndex ${cause.javaClass.name}"
                        )
                        appendLine(
                            "Message: ${cause.message}"
                        )
                        appendLine()

                        cause =
                            cause.cause

                        causeIndex++
                    }

                    appendLine(
                        "=================== STACKTRACE ================="
                    )
                    appendLine(
                        stackTraceWriter.toString()
                    )

                    appendLine(
                        "=================================================="
                    )
                }

            val logDirectory =
                File(
                    context.getExternalFilesDir(null),
                    "crash_logs"
                )

            if (!logDirectory.exists()) {
                logDirectory.mkdirs()
            }

            val logFile =
                File(
                    logDirectory,
                    "render_error_${timestamp}.txt"
                )

            logFile.writeText(
                diagnostics
            )

            val latestLogFile =
                File(
                    logDirectory,
                    "latest_render_error.txt"
                )

            latestLogFile.writeText(
                diagnostics
            )

            Log.e(
                TAG,
                diagnostics,
                throwable
            )
        } catch (loggingException: Exception) {
            Log.e(
                TAG,
                "Fehler beim Schreiben des Render-Logs.",
                loggingException
            )
        }
    }

    private fun appendToLogFile(
        context: Context,
        line: String
    ) {
        val logDirectory =
            File(
                context.getExternalFilesDir(null),
                "crash_logs"
            )

        if (!logDirectory.exists()) {
            logDirectory.mkdirs()
        }

        val latestLogFile =
            File(
                logDirectory,
                "latest_render_error.txt"
            )

        latestLogFile.appendText(
            line + "\n"
        )
    }
}

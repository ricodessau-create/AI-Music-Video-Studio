package com.example.aivideostudio.render

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import java.io.File
import kotlin.math.min

class VideoEncoder(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val frameRate: Int = 30,
    private val bitrate: Int = 8_000_000
) {

    companion object {
        private const val TAG = "AI_MUSIC_VIDEO_RENDER"

        private const val INPUT_TIMEOUT_US = 250_000L
        private const val MAX_INPUT_WAIT_ATTEMPTS = 40
        private const val MAX_EOS_WAIT_ATTEMPTS = 80
    }

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null

    private var trackIndex = -1
    private var muxerStarted = false
    private var started = false

    private var frameCount = 0L
    private var writtenSampleCount = 0L
    private var lastPresentationTimeUs = -1L

    private var selectedCodecName: String = "unknown"
    private var configuredColorFormat: Int = -1

    private val bufferInfo =
        MediaCodec.BufferInfo()

    private fun log(message: String) {
        val fullMessage =
            "[VideoEncoder] $message"

        Log.d(
            TAG,
            fullMessage
        )

        try {
            val logDirectory =
                outputFile.parentFile
                    ?.resolve("render_diagnostics")

            if (logDirectory != null) {
                if (!logDirectory.exists()) {
                    logDirectory.mkdirs()
                }

                val logFile =
                    logDirectory.resolve(
                        "video_encoder_diagnostics.txt"
                    )

                logFile.appendText(
                    fullMessage + "\n"
                )
            }
        } catch (exception: Exception) {
            Log.e(
                TAG,
                "Konnte Diagnose-Logdatei nicht schreiben.",
                exception
            )
        }
    }

    private fun logError(
        message: String,
        throwable: Throwable? = null
    ) {
        val fullMessage =
            "[VideoEncoder][ERROR] $message"

        Log.e(
            TAG,
            fullMessage,
            throwable
        )

        try {
            val logDirectory =
                outputFile.parentFile
                    ?.resolve("render_diagnostics")

            if (logDirectory != null) {
                if (!logDirectory.exists()) {
                    logDirectory.mkdirs()
                }

                val logFile =
                    logDirectory.resolve(
                        "video_encoder_diagnostics.txt"
                    )

                logFile.appendText(
                    fullMessage + "\n"
                )

                throwable?.let {
                    logFile.appendText(
                        it.stackTraceToString() +
                            "\n"
                    )
                }
            }
        } catch (loggingException: Exception) {
            Log.e(
                TAG,
                "Konnte Fehlerdiagnose nicht schreiben.",
                loggingException
            )
        }
    }

    fun start() {
        check(!started) {
            "VideoEncoder wurde bereits gestartet."
        }

        require(width > 0) {
            "Ungültige Videobreite: $width"
        }

        require(height > 0) {
            "Ungültige Videohöhe: $height"
        }

        require(frameRate > 0) {
            "Ungültige Framerate: $frameRate"
        }

        require(bitrate > 0) {
            "Ungültige Bitrate: $bitrate"
        }

        outputFile.parentFile?.mkdirs()

        log(
            "=================================================="
        )
        log(
            "VIDEO ENCODER START"
        )
        log(
            "=================================================="
        )
        log(
            "Android SDK=${Build.VERSION.SDK_INT}"
        )
        log(
            "Android Release=${Build.VERSION.RELEASE}"
        )
        log(
            "Manufacturer=${Build.MANUFACTURER}"
        )
        log(
            "Model=${Build.MODEL}"
        )
        log(
            "Device=${Build.DEVICE}"
        )
        log(
            "Product=${Build.PRODUCT}"
        )
        log(
            "Board=${Build.BOARD}"
        )
        log(
            "ABI=${Build.SUPPORTED_ABIS.joinToString()}"
        )
        log(
            "Output=${outputFile.absolutePath}"
        )
        log(
            "Resolution=${width}x${height}"
        )
        log(
            "FrameRate=$frameRate"
        )
        log(
            "Bitrate=$bitrate"
        )

        val format =
            MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                width,
                height
            )

        configuredColorFormat =
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible

        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            configuredColorFormat
        )

        format.setInteger(
            MediaFormat.KEY_BIT_RATE,
            bitrate
        )

        format.setInteger(
            MediaFormat.KEY_FRAME_RATE,
            frameRate
        )

        format.setInteger(
            MediaFormat.KEY_I_FRAME_INTERVAL,
            2
        )

        log(
            "Requested MIME=${MediaFormat.MIMETYPE_VIDEO_AVC}"
        )

        log(
            "Requested COLOR_FORMAT=$configuredColorFormat " +
                "(COLOR_FormatYUV420Flexible)"
        )

        log(
            "Requested BIT_RATE=$bitrate"
        )

        log(
            "Requested FRAME_RATE=$frameRate"
        )

        log(
            "Requested I_FRAME_INTERVAL=2"
        )

        val codecName =
            findEncoderCodec()

        selectedCodecName =
            codecName

        log(
            "Selected codec=$selectedCodecName"
        )

        val codec =
            try {
                MediaCodec.createByCodecName(
                    codecName
                )
            } catch (exception: Exception) {
                logError(
                    "MediaCodec.createByCodecName() fehlgeschlagen.",
                    exception
                )

                throw exception
            }

        try {
            log(
                "Codec instance erfolgreich erstellt."
            )

            log(
                "Codec info name=${codec.codecInfo.name}"
            )

            log(
                "Codec info canonicalName=${codec.codecInfo.canonicalName}"
            )

            logCodecCapabilities(
                codec.codecInfo
            )

            log(
                "Rufe codec.configure() auf."
            )

            codec.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )

            log(
                "codec.configure() erfolgreich."
            )

            encoder =
                codec

            muxer =
                MediaMuxer(
                    outputFile.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )

            log(
                "MediaMuxer erfolgreich erstellt."
            )

            log(
                "Rufe codec.start() auf."
            )

            codec.start()

            log(
                "codec.start() erfolgreich."
            )

            started = true
            frameCount = 0L
            writtenSampleCount = 0L
            lastPresentationTimeUs = -1L

            log(
                "Encoder vollständig gestartet."
            )
        } catch (exception: Exception) {
            logError(
                "Fehler während configure/start.",
                exception
            )

            try {
                codec.release()
            } catch (releaseException: Exception) {
                logError(
                    "Fehler beim Release nach Startfehler.",
                    releaseException
                )
            }

            encoder = null

            try {
                muxer?.release()
            } catch (releaseException: Exception) {
                logError(
                    "Fehler beim Muxer-Release nach Startfehler.",
                    releaseException
                )
            }

            muxer = null

            throw exception
        }
    }

    fun encodeFrame(
        bitmap: Bitmap,
        presentationTimeUs: Long
    ) {
        check(started) {
            "VideoEncoder wurde noch nicht gestartet."
        }

        require(!bitmap.isRecycled) {
            "Das übergebene Bitmap wurde bereits recycelt."
        }

        require(bitmap.width == width) {
            "Bitmap-Breite ${bitmap.width} entspricht nicht der Encoder-Breite $width."
        }

        require(bitmap.height == height) {
            "Bitmap-Höhe ${bitmap.height} entspricht nicht der Encoder-Höhe $height."
        }

        require(presentationTimeUs >= 0L) {
            "Ungültiger Presentation Timestamp: $presentationTimeUs"
        }

        require(
            presentationTimeUs > lastPresentationTimeUs
        ) {
            "Presentation Timestamp muss monoton steigen. " +
                "Vorher=$lastPresentationTimeUs, " +
                "aktuell=$presentationTimeUs"
        }

        val currentFrame =
            frameCount + 1L

        log(
            "--------------------------------------------------"
        )

        log(
            "FRAME #$currentFrame"
        )

        log(
            "presentationTimeUs=$presentationTimeUs"
        )

        log(
            "bitmap=${bitmap.width}x${bitmap.height}"
        )

        log(
            "bitmapConfig=${bitmap.config}"
        )

        log(
            "bitmapRowBytes=${bitmap.rowBytes}"
        )

        log(
            "bitmapAllocationByteCount=${bitmap.allocationByteCount}"
        )

        val codec =
            encoder
                ?: error(
                    "Encoder ist nicht verfügbar."
                )

        val inputIndex =
            try {
                waitForInputBuffer(
                    codec
                )
            } catch (exception: Exception) {
                logError(
                    "Kein Input-Buffer für Frame #$currentFrame verfügbar.",
                    exception
                )

                throw exception
            }

        log(
            "Frame #$currentFrame erhielt InputBuffer index=$inputIndex"
        )

        val image =
            try {
                codec.getInputImage(
                    inputIndex
                )
                    ?: throw IllegalStateException(
                        "MediaCodec.getInputImage() hat ein null Image geliefert."
                    )
            } catch (exception: Exception) {
                logError(
                    "getInputImage() für Frame #$currentFrame fehlgeschlagen.",
                    exception
                )

                throw exception
            }

        try {
            log(
                "InputImage erhalten: " +
                    "width=${image.width}, " +
                    "height=${image.height}, " +
                    "format=${image.format}"
            )

            log(
                "InputImage timestamp=${image.timestamp}"
            )

            require(image.planes.size >= 3) {
                "Der Encoder stellt weniger als drei YUV-Planes bereit."
            }

            image.planes.forEachIndexed { index, plane ->
                logPlaneInfo(
                    index,
                    plane
                )
            }

            fillImageFromBitmap(
                bitmap = bitmap,
                image = image
            )

            log(
                "YUV-Daten für Frame #$currentFrame erfolgreich geschrieben."
            )
        } catch (exception: Exception) {
            logError(
                "Fehler beim Befüllen des InputImage für Frame #$currentFrame.",
                exception
            )

            throw exception
        } finally {
            try {
                image.close()

                log(
                    "InputImage für Frame #$currentFrame geschlossen."
                )
            } catch (exception: Exception) {
                logError(
                    "Fehler beim Schließen des InputImage.",
                    exception
                )
            }
        }

        try {
            log(
                "queueInputBuffer(): " +
                    "index=$inputIndex, " +
                    "offset=0, " +
                    "size=0, " +
                    "pts=$presentationTimeUs, " +
                    "flags=0"
            )

            codec.queueInputBuffer(
                inputIndex,
                0,
                0,
                presentationTimeUs,
                0
            )

            log(
                "queueInputBuffer() erfolgreich für Frame #$currentFrame."
            )
        } catch (exception: Exception) {
            logError(
                "queueInputBuffer() für Frame #$currentFrame fehlgeschlagen.",
                exception
            )

            throw exception
        }

        lastPresentationTimeUs =
            presentationTimeUs

        frameCount++

        log(
            "Frame #$currentFrame wurde an MediaCodec übergeben."
        )

        try {
            drainEncoder(
                endOfStream = false
            )
        } catch (exception: Exception) {
            logError(
                "drainEncoder() nach Frame #$currentFrame fehlgeschlagen.",
                exception
            )

            throw exception
        }

        log(
            "Frame #$currentFrame abgeschlossen. " +
                "writtenSamples=$writtenSampleCount"
        )
    }

    fun finish() {
        if (!started) {
            log(
                "finish(): Encoder war nicht gestartet."
            )

            return
        }

        log(
            "=================================================="
        )

        log(
            "VIDEO ENCODER FINISH"
        )

        log(
            "Frames verarbeitet=$frameCount"
        )

        log(
            "Samples geschrieben=$writtenSampleCount"
        )

        val codec =
            encoder
                ?: throw IllegalStateException(
                    "Encoder ist nicht verfügbar."
                )

        try {
            queueEndOfStream(
                codec
            )

            var eosReached = false

            var drainPasses = 0

            while (!eosReached) {
                drainPasses++

                log(
                    "EOS drain pass #$drainPasses"
                )

                eosReached =
                    drainEncoder(
                        endOfStream = true
                    )

                if (drainPasses > MAX_EOS_WAIT_ATTEMPTS) {
                    throw IllegalStateException(
                        "EOS wurde nach $MAX_EOS_WAIT_ATTEMPTS " +
                            "Drain-Versuchen nicht erreicht."
                    )
                }
            }

            log(
                "End-of-stream erfolgreich erreicht."
            )

            if (!muxerStarted) {
                throw IllegalStateException(
                    "Der MP4-Muxer wurde nie gestartet. " +
                        "Es wurde kein gültiger Video-Track erzeugt."
                )
            }

            if (writtenSampleCount <= 0L) {
                throw IllegalStateException(
                    "Der Encoder hat keine Videoframes " +
                        "in die MP4-Datei geschrieben."
                )
            }

            if (
                !outputFile.exists() ||
                outputFile.length() <= 1024L
            ) {
                throw IllegalStateException(
                    "Die erzeugte MP4-Datei ist ungültig oder leer: " +
                        outputFile.absolutePath
                )
            }

            log(
                "MP4 erfolgreich erzeugt."
            )

            log(
                "MP4 size=${outputFile.length()} bytes"
            )

            log(
                "Final frames=$frameCount"
            )

            log(
                "Final samples=$writtenSampleCount"
            )
        } catch (exception: Exception) {
            logError(
                "Fehler während finish().",
                exception
            )

            throw exception
        } finally {
            release()

            log(
                "VideoEncoder.finish() beendet."
            )
        }
    }

    private fun waitForInputBuffer(
        codec: MediaCodec
    ): Int {
        log(
            "waitForInputBuffer(): " +
                "timeoutUs=$INPUT_TIMEOUT_US, " +
                "maxAttempts=$MAX_INPUT_WAIT_ATTEMPTS"
        )

        repeat(
            MAX_INPUT_WAIT_ATTEMPTS
        ) { attempt ->

            val inputIndex =
                try {
                    codec.dequeueInputBuffer(
                        INPUT_TIMEOUT_US
                    )
                } catch (exception: Exception) {
                    logError(
                        "dequeueInputBuffer() fehlgeschlagen. " +
                            "attempt=${attempt + 1}",
                        exception
                    )

                    throw exception
                }

            if (inputIndex >= 0) {
                log(
                    "InputBuffer verfügbar: " +
                        "index=$inputIndex, " +
                        "attempt=${attempt + 1}/${MAX_INPUT_WAIT_ATTEMPTS}"
                )

                return inputIndex
            }

            log(
                "Kein InputBuffer verfügbar: " +
                    "attempt=${attempt + 1}/$MAX_INPUT_WAIT_ATTEMPTS, " +
                    "result=$inputIndex"
            )

            try {
                val eos =
                    drainEncoder(
                        endOfStream = false
                    )

                log(
                    "drainEncoder() während Input-Wartephase: " +
                        "eos=$eos, " +
                        "writtenSamples=$writtenSampleCount"
                )
            } catch (exception: Exception) {
                logError(
                    "drainEncoder() während waitForInputBuffer() fehlgeschlagen.",
                    exception
                )

                throw exception
            }
        }

        val diagnosticMessage =
            "Kein freier MediaCodec-Input-Buffer verfügbar. " +
                "Codec=$selectedCodecName, " +
                "resolution=${width}x$height, " +
                "frameRate=$frameRate, " +
                "bitrate=$bitrate, " +
                "colorFormat=$configuredColorFormat, " +
                "frames=$frameCount, " +
                "writtenSamples=$writtenSampleCount"

        logError(
            diagnosticMessage
        )

        throw IllegalStateException(
            diagnosticMessage
        )
    }

    private fun queueEndOfStream(
        codec: MediaCodec
    ) {
        log(
            "queueEndOfStream() gestartet."
        )

        repeat(
            MAX_EOS_WAIT_ATTEMPTS
        ) { attempt ->

            val inputIndex =
                try {
                    codec.dequeueInputBuffer(
                        INPUT_TIMEOUT_US
                    )
                } catch (exception: Exception) {
       

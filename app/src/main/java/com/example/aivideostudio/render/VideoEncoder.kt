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

    private var selectedCodecName = "unknown"
    private var configuredColorFormat = -1

    private val bufferInfo = MediaCodec.BufferInfo()

    private fun log(message: String) {
        val fullMessage = "[VideoEncoder] $message"

        Log.d(TAG, fullMessage)

        try {
            val directory = outputFile.parentFile?.resolve("render_diagnostics")

            if (directory != null) {
                if (!directory.exists()) {
                    directory.mkdirs()
                }

                directory.resolve("video_encoder_diagnostics.txt")
                    .appendText(fullMessage + "\n")
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
        val fullMessage = "[VideoEncoder][ERROR] $message"

        Log.e(TAG, fullMessage, throwable)

        try {
            val directory = outputFile.parentFile?.resolve("render_diagnostics")

            if (directory != null) {
                if (!directory.exists()) {
                    directory.mkdirs()
                }

                val file =
                    directory.resolve("video_encoder_diagnostics.txt")

                file.appendText(fullMessage + "\n")

                if (throwable != null) {
                    file.appendText(
                        throwable.stackTraceToString() + "\n"
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

        log("==================================================")
        log("VIDEO ENCODER START")
        log("==================================================")
        log("Android SDK=${Build.VERSION.SDK_INT}")
        log("Android Release=${Build.VERSION.RELEASE}")
        log("Manufacturer=${Build.MANUFACTURER}")
        log("Model=${Build.MODEL}")
        log("Device=${Build.DEVICE}")
        log("Product=${Build.PRODUCT}")
        log("Board=${Build.BOARD}")
        log("ABI=${Build.SUPPORTED_ABIS.joinToString()}")
        log("Output=${outputFile.absolutePath}")
        log("Resolution=${width}x$height")
        log("FrameRate=$frameRate")
        log("Bitrate=$bitrate")

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

        log("Requested BIT_RATE=$bitrate")
        log("Requested FRAME_RATE=$frameRate")
        log("Requested I_FRAME_INTERVAL=2")

        val codecName = findEncoderCodec()

        selectedCodecName = codecName

        log("Selected codec=$selectedCodecName")

        val codec =
            try {
                MediaCodec.createByCodecName(codecName)
            } catch (exception: Exception) {
                logError(
                    "MediaCodec.createByCodecName() fehlgeschlagen.",
                    exception
                )
                throw exception
            }

        try {
            log("Codec instance erfolgreich erstellt.")
            log("Codec info name=${codec.codecInfo.name}")
            log("Codec info canonicalName=${codec.codecInfo.canonicalName}")

            logCodecCapabilities(codec.codecInfo)

            log("Rufe codec.configure() auf.")

            codec.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )

            log("codec.configure() erfolgreich.")

            encoder = codec

            muxer =
                MediaMuxer(
                    outputFile.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )

            log("MediaMuxer erfolgreich erstellt.")
            log("Rufe codec.start() auf.")

            codec.start()

            log("codec.start() erfolgreich.")

            started = true
            frameCount = 0L
            writtenSampleCount = 0L
            lastPresentationTimeUs = -1L

            log("Encoder vollständig gestartet.")
        } catch (exception: Exception) {
            logError(
                "Fehler während configure/start.",
                exception
            )

            try {
                codec.stop()
            } catch (_: Exception) {
            }

            try {
                codec.release()
            } catch (_: Exception) {
            }

            encoder = null

            try {
                muxer?.release()
            } catch (_: Exception) {
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

        val currentFrame = frameCount + 1L

        log("--------------------------------------------------")
        log("FRAME #$currentFrame")
        log("presentationTimeUs=$presentationTimeUs")
        log("bitmap=${bitmap.width}x${bitmap.height}")
        log("bitmapConfig=${bitmap.config}")
        log("bitmapRowBytes=${bitmap.rowBytes}")
        log("bitmapAllocationByteCount=${bitmap.allocationByteCount}")

        val codec =
            encoder
                ?: error("Encoder ist nicht verfügbar.")

        val inputIndex =
            waitForInputBuffer(codec)

        log(
            "Frame #$currentFrame erhielt InputBuffer index=$inputIndex"
        )

        val image =
            try {
                codec.getInputImage(inputIndex)
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

            log("InputImage timestamp=${image.timestamp}")

            require(image.planes.size >= 3) {
                "Der Encoder stellt weniger als drei YUV-Planes bereit."
            }

            image.planes.forEachIndexed { index, plane ->
                logPlaneInfo(index, plane)
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

        lastPresentationTimeUs = presentationTimeUs
        frameCount++

        log(
            "Frame #$currentFrame wurde an MediaCodec übergeben."
        )

        try {
            drainEncoder(false)
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
            log("finish(): Encoder war nicht gestartet.")
            return
        }

        log("==================================================")
        log("VIDEO ENCODER FINISH")
        log("Frames verarbeitet=$frameCount")
        log("Samples geschrieben=$writtenSampleCount")

        val codec =
            encoder
                ?: throw IllegalStateException(
                    "Encoder ist nicht verfügbar."
                )

        try {
            queueEndOfStream(codec)

            var eosReached = false
            var drainPasses = 0

            while (!eosReached) {
                drainPasses++

                if (drainPasses > MAX_EOS_WAIT_ATTEMPTS) {
                    throw IllegalStateException(
                        "EOS wurde nach $MAX_EOS_WAIT_ATTEMPTS " +
                            "Drain-Versuchen nicht erreicht."
                    )
                }

                log(
                    "EOS drain pass #$drainPasses"
                )

                eosReached = drainEncoder(true)
            }

            log("End-of-stream erfolgreich erreicht.")

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

            log("MP4 erfolgreich erzeugt.")
            log("MP4 size=${outputFile.length()} bytes")
            log("Final frames=$frameCount")
            log("Final samples=$writtenSampleCount")
        } catch (exception: Exception) {
            logError(
                "Fehler während finish().",
                exception
            )
            throw exception
        } finally {
            release()
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

        repeat(MAX_INPUT_WAIT_ATTEMPTS) { attempt ->
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
                        "attempt=${attempt + 1}/$MAX_INPUT_WAIT_ATTEMPTS"
                )

                return inputIndex
            }

            log(
                "Kein InputBuffer verfügbar: " +
                    "attempt=${attempt + 1}/$MAX_INPUT_WAIT_ATTEMPTS, " +
                    "result=$inputIndex"
            )

            try {
                val eos = drainEncoder(false)

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

        logError(diagnosticMessage)

        throw IllegalStateException(diagnosticMessage)
    }

    private fun queueEndOfStream(
        codec: MediaCodec
    ) {
        log("queueEndOfStream() gestartet.")

        repeat(MAX_EOS_WAIT_ATTEMPTS) { attempt ->
            val inputIndex =
                try {
                    codec.dequeueInputBuffer(
                        INPUT_TIMEOUT_US
                    )
                } catch (exception: Exception) {
                    logError(
                        "dequeueInputBuffer() während EOS fehlgeschlagen.",
                        exception
                    )
                    throw exception
                }

            if (inputIndex >= 0) {
                val eosTimestamp =
                    if (lastPresentationTimeUs >= 0L) {
                        lastPresentationTimeUs +
                            1_000_000L / frameRate.toLong()
                    } else {
                        0L
                    }

                log(
                    "EOS InputBuffer erhalten: " +
                        "index=$inputIndex, " +
                        "timestamp=$eosTimestamp, " +
                        "attempt=${attempt + 1}"
                )

                try {
                    codec.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        eosTimestamp,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )

                    log(
                        "EOS erfolgreich an MediaCodec übergeben."
                    )
                } catch (exception: Exception) {
                    logError(
                        "queueInputBuffer(EOS) fehlgeschlagen.",
                        exception
                    )
                    throw exception
                }

                return
            }

            log(
                "Kein InputBuffer für EOS: " +
                    "attempt=${attempt + 1}/$MAX_EOS_WAIT_ATTEMPTS"
            )

            drainEncoder(false)
        }

        throw IllegalStateException(
            "Der MediaCodec-Input-Buffer für das " +
                "Ende des Videos wurde nicht verfügbar."
        )
    }

    private fun drainEncoder(
        endOfStream: Boolean
    ): Boolean {
        val codec =
            encoder
                ?: return false

        var eosReached = false

        val timeoutUs =
            if (endOfStream) {
                INPUT_TIMEOUT_US
            } else {
                0L
            }

        while (true) {
            val outputIndex =
                try {
                    codec.dequeueOutputBuffer(
                        bufferInfo,
                        timeoutUs
                    )
                } catch (exception: Exception) {
                    logError(
                        "dequeueOutputBuffer() fehlgeschlagen.",
                        exception
                    )
                    throw exception
                }

            when {
                outputIndex ==
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    return eosReached
                }

                outputIndex ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    log("INFO_OUTPUT_FORMAT_CHANGED")

                    if (muxerStarted) {
                        throw IllegalStateException(
                            "Das Encoder-Ausgabeformat wurde mehrfach geändert."
                        )
                    }

                    val outputFormat = codec.outputFormat

                    log(
                        "Encoder outputFormat=$outputFormat"
                    )

                    val currentMuxer =
                        muxer
                            ?: throw IllegalStateException(
                                "Muxer ist nicht verfügbar."
                            )

                    trackIndex =
                        currentMuxer.addTrack(
                            outputFormat
                        )

                    log(
                        "Muxer trackIndex=$trackIndex hinzugefügt."
                    )

                    currentMuxer.start()
                    muxerStarted = true

                    log("Muxer gestartet.")
                }

                outputIndex >= 0 -> {
                    log(
                        "OutputBuffer verfügbar: " +
                            "index=$outputIndex"
                    )

                    log(
                        "Output BufferInfo: " +
                            "offset=${bufferInfo.offset}, " +
                            "size=${bufferInfo.size}, " +
                            "presentationTimeUs=${bufferInfo.presentationTimeUs}, " +
                            "flags=${bufferInfo.flags}"
                    )

                    val encodedData =
                        codec.getOutputBuffer(outputIndex)

                    if (encodedData == null) {
                        log(
                            "getOutputBuffer($outputIndex) = null"
                        )
                    } else {
                        log(
                            "OutputBuffer: " +
                                "capacity=${encodedData.capacity()}, " +
                                "position=${encodedData.position()}, " +
                                "limit=${encodedData.limit()}, " +
                                "remaining=${encodedData.remaining()}"
                        )

                        if (
                            bufferInfo.size > 0 &&
                            muxerStarted &&
                            trackIndex >= 0
                        ) {
                            encodedData.position(
                                bufferInfo.offset
                            )

                            encodedData.limit(
                                bufferInfo.offset +
                                    bufferInfo.size
                            )

                            muxer?.writeSampleData(
                                trackIndex,
                                encodedData,
                                bufferInfo
                            )

                            writtenSampleCount++

                            log(
                                "Output-Sample geschrieben. " +
                                    "sampleCount=$writtenSampleCount"
                            )
                        } else {
                            log(
                                "Output-Sample NICHT geschrieben: " +
                                    "size=${bufferInfo.size}, " +
                                    "muxerStarted=$muxerStarted, " +
                                    "trackIndex=$trackIndex"
                            )
                        }
                    }

                    val isCodecConfig =
                        (
                            bufferInfo.flags and
                                MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                            ) != 0

                    val isKeyFrame =
                        (
                            bufferInfo.flags and
                                MediaCodec.BUFFER_FLAG_KEY_FRAME
                            ) != 0

                    val isEndOfStream =
                        (
                            bufferInfo.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            ) != 0

                    log(
                        "Output flags: " +
                            "codecConfig=$isCodecConfig, " +
                            "keyFrame=$isKeyFrame, " +
                            "eos=$isEndOfStream"
                    )

                    try {
                        codec.releaseOutputBuffer(
                            outputIndex,
                            false
                        )
                    } catch (exception: Exception) {
                        logError(
                            "releaseOutputBuffer($outputIndex) fehlgeschlagen.",
                            exception
                        )
                        throw exception
                    }

                    if (isEndOfStream) {
                        eosReached = true

                        log(
                            "BUFFER_FLAG_END_OF_STREAM erreicht."
                        )

                        return true
                    }
                }

                else -> {
                    log(
                        "Unerwarteter dequeueOutputBuffer() Wert: " +
                            "$outputIndex"
                    )

                    return eosReached
                }
            }
        }
    }

    private fun fillImageFromBitmap(
        bitmap: Bitmap,
        image: Image
    ) {
        val planes = image.planes

        require(planes.size >= 3) {
            "YUV-Image besitzt nicht genügend Planes."
        }

        val pixelBuffer =
            IntArray(
                bitmap.width *
                    bitmap.height
            )

        bitmap.getPixels(
            pixelBuffer,
            0,
            bitmap.width,
            0,
            0,
            bitmap.width,
            bitmap.height
        )

        writeLumaPlane(
            pixels = pixelBuffer,
            width = bitmap.width,
            height = bitmap.height,
            plane = planes[0]
        )

        writeChromaPlane(
            pixels = pixelBuffer,
            width = bitmap.width,
            height = bitmap.height,
            plane = planes[1],
            uPlane = true
        )

        writeChromaPlane(
            pixels = pixelBuffer,
            width = bitmap.width,
            height = bitmap.height,
            plane = planes[2],
            uPlane = false
        )
    }

    private fun logPlaneInfo(
        index: Int,
        plane: Image.Plane
    ) {
        val buffer = plane.buffer

        log(
            "Plane[$index]: " +
                "rowStride=${plane.rowStride}, " +
                "pixelStride=${plane.pixelStride}, " +
                "position=${buffer.position()}, " +
                "limit=${buffer.limit()}, " +
                "capacity=${buffer.capacity()}, " +
                "remaining=${buffer.remaining()}"
        )
    }

    private fun writeLumaPlane(
        pixels: IntArray,
        width: Int,
        height: Int,
        plane: Image.Plane
    ) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        require(rowStride > 0) {
            "Ungültiger Y-Row-Stride: $rowStride"
        }

        require(pixelStride > 0) {
            "Ungültiger Y-Pixel-Stride: $pixelStride"
        }

        require(buffer.remaining() > 0) {
            "Y-Plane besitzt keinen Speicher."
        }

        val basePosition = buffer.position()
        val bufferLimit = buffer.limit()

        log(
            "Writing Y plane: " +
                "rowStride=$rowStride, " +
                "pixelStride=$pixelStride, " +
                "basePosition=$basePosition, " +
                "limit=$bufferLimit"
        )

        for (y in 0 until height) {
            val rowStart =
                basePosition +
                    y * rowStride

            for (x in 0 until width) {
                val pixel =
                    pixels[
                        y * width + x
                    ]

                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val yValue =
                    (
                        66 * r +
                            129 * g +
                            25 * b +
                            128
                        ) shr 8

                val position =
                    rowStart +
                        x * pixelStride

                if (
                    position < basePosition ||
                    position >= bufferLimit
                ) {
                    throw IllegalStateException(
                        "Y-Plane überschreitet den verfügbaren Buffer."
                    )
                }

                buffer.put(
                    position,
                    (
                        yValue + 16
                    )
                        .coerceIn(0, 255)
                        .toByte()
                )
            }
        }
    }

    private fun writeChromaPlane(
        pixels: IntArray,
        width: Int,
        height: Int,
        plane: Image.Plane,
        uPlane: Boolean
    ) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        require(rowStride > 0) {
            "Ungültiger Chroma-Row-Stride: $rowStride"
        }

        require(pixelStride > 0) {
            "Ungültiger Chroma-Pixel-Stride: $pixelStride"
        }

        require(buffer.remaining() > 0) {
            "Chroma-Plane besitzt keinen Speicher."
        }

        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2

        val basePosition = buffer.position()
        val bufferLimit = buffer.limit()

        log(
            "Writing ${if (uPlane) "U" else "V"} plane: " +
                "rowStride=$rowStride, " +
                "pixelStride=$pixelStride, " +
                "chroma=${chromaWidth}x$chromaHeight"
        )

        for (y in 0 until chromaHeight) {
            val sourceY =
                min(
                    y * 2,
                    height - 1
                )

            val rowStart =
                basePosition +
                    y * rowStride

            for (x in 0 until chromaWidth) {
                val sourceX =
                    min(
                        x * 2,
                        width - 1
                    )

                val pixel =
                    pixels[
                        sourceY * width +
                            sourceX
                    ]

                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val value =
                    if (uPlane) {
                        (
                            -38 * r -
                                74 * g +
                                112 * b +
                                128
                            ) shr 8
                    } else {
                        (
                            112 * r -
                                94 * g -
                                18 * b +
                                128
                            ) shr 8
                    }

                val position =
                    rowStart +
                        x * pixelStride

                if (
                    position < basePosition ||
                    position >= bufferLimit
                ) {
                    throw IllegalStateException(
                        "Chroma-Plane überschreitet den verfügbaren Buffer."
                    )
                }

                buffer.put(
                    position,
                    (
                        value + 128
                    )
                        .coerceIn(0, 255)
                        .toByte()
                )
            }
        }
    }

    private fun logCodecCapabilities(
        codecInfo: MediaCodecInfo
    ) {
        try {
            log(
                "Codec supportedTypes=" +
                    codecInfo.supportedTypes.joinToString()
            )

            val avcType =
                codecInfo.supportedTypes.firstOrNull { type ->
                    type.equals(
                        MediaFormat.MIMETYPE_VIDEO_AVC,
                        ignoreCase = true
                    )
                }

            if (avcType == null) {
                log(
                    "WARNUNG: Codec meldet keinen AVC-Support."
                )
                return
            }

            val capabilities =
                codecInfo.getCapabilitiesForType(avcType)

            log(
                "AVC colorFormats=" +
                    capabilities.colorFormats.joinToString()
            )

            capabilities.colorFormats.forEach { colorFormat ->
                log(
                    "Supported colorFormat=$colorFormat " +
                        "name=${colorFormatName(colorFormat)}"
                )
            }

            val videoCapabilities =
                capabilities.videoCapabilities

            log(
                "Video widthRange=" +
                    videoCapabilities.supportedWidths
            )

            log(
                "Video heightRange=" +
                    videoCapabilities.supportedHeights
            )

            log(
                "Video widthAlignment=" +
                    videoCapabilities.widthAlignment
            )

            log(
                "Video heightAlignment=" +
                    videoCapabilities.heightAlignment
            )

            log(
                "Video bitrateRange=" +
                    videoCapabilities.bitrateRange
            )

            log(
                "Video frameRateRange=" +
                    videoCapabilities.supportedFrameRates
            )
        } catch (exception: Exception) {
            logError(
                "Codec-Capabilities konnten nicht vollständig ausgelesen werden.",
                exception
            )
        }
    }

    private fun colorFormatName(
        colorFormat: Int
    ): String {
        return when (colorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible ->
                "COLOR_FormatYUV420Flexible"

            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface ->
                "COLOR_FormatSurface"

            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ->
                "COLOR_FormatYUV420Planar"

            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ->
                "COLOR_FormatYUV420SemiPlanar"

            else ->
                "UNKNOWN"
        }
    }

    private fun findEncoderCodec(): String {
        val preferredNames =
            listOf(
                "c2.android.avc.encoder",
                "OMX.google.h264.encoder"
            )

        log(
            "Suche bevorzugten AVC-Encoder."
        )

        for (name in preferredNames) {
            try {
                val codec =
                    MediaCodec.createByCodecName(name)

                log(
                    "Bevorzugter Codec verfügbar: $name"
                )

                codec.release()

                return name
            } catch (exception: Exception) {
                log(
                    "Bevorzugter Codec nicht verfügbar: " +
                        "$name (${exception.javaClass.simpleName})"
                )
            }
        }

        log(
            "Durchsuche MediaCodecList nach AVC-Encodern."
        )

        val codecList =
            MediaCodecList(
                MediaCodecList.ALL_CODECS
            )

        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) {
                continue
            }

            val supportsAvc =
                codecInfo.supportedTypes.any { type ->
                    type.equals(
                        MediaFormat.MIMETYPE_VIDEO_AVC,
                        ignoreCase = true
                    )
                }

            if (supportsAvc) {
                log(
                    "AVC-Encoder gefunden: ${codecInfo.name}"
                )

                return codecInfo.name
            }
        }

        throw IllegalStateException(
            "Auf diesem Gerät wurde kein H.264/AVC-Encoder gefunden."
        )
    }

    private fun release() {
        log("release() gestartet.")

        try {
            encoder?.stop()
            log("Encoder.stop() erfolgreich.")
        } catch (exception: Exception) {
            logError(
                "Encoder.stop() fehlgeschlagen.",
                exception
            )
        }

        try {
            encoder?.release()
            log("Encoder.release() erfolgreich.")
        } catch (exception: Exception) {
            logError(
                "Encoder.release() fehlgeschlagen.",
                exception
            )
        }

        encoder = null

        if (muxerStarted) {
            try {
                muxer?.stop()
                log("Muxer.stop() erfolgreich.")
            } catch (exception: Exception) {
                logError(
                    "Muxer.stop() fehlgeschlagen.",
                    exception
                )
            }
        }

        try {
            muxer?.release()
            log("Muxer.release() erfolgreich.")
        } catch (exception: Exception) {
            logError(
                "Muxer.release() fehlgeschlagen.",
                exception
            )
        }

        muxer = null

        muxerStarted = false
        trackIndex = -1
        started = false

        log("release() abgeschlossen.")
        log("==================================================")
    }
}
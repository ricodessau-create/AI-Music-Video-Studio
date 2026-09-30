package com.example.aivideostudio.render

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import kotlin.math.min

class VideoEncoder(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val frameRate: Int = 30,
    private val bitrate: Int = 8_000_000
) {

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null

    private var trackIndex = -1
    private var muxerStarted = false
    private var started = false

    private var frameCount = 0L
    private var writtenSampleCount = 0L
    private var lastPresentationTimeUs = -1L

    private val bufferInfo =
        MediaCodec.BufferInfo()

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

        val format =
            MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                width,
                height
            )

        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
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

        val codecName =
            findEncoderCodec()

        val codec =
            MediaCodec.createByCodecName(
                codecName
            )

        try {
            codec.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )

            encoder = codec

            muxer =
                MediaMuxer(
                    outputFile.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )

            codec.start()

            started = true
            frameCount = 0L
            writtenSampleCount = 0L
            lastPresentationTimeUs = -1L
        } catch (exception: Exception) {
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

        val codec =
            encoder
                ?: error(
                    "Encoder ist nicht verfügbar."
                )

        val inputIndex =
            waitForInputBuffer(codec)

        val image =
            codec.getInputImage(
                inputIndex
            )
                ?: throw IllegalStateException(
                    "MediaCodec.getInputImage() hat ein null Image geliefert."
                )

        try {
            require(image.planes.size >= 3) {
                "Der Encoder stellt weniger als drei YUV-Planes bereit."
            }

            fillImageFromBitmap(
                bitmap = bitmap,
                image = image
            )
        } finally {
            image.close()
        }

        codec.queueInputBuffer(
            inputIndex,
            0,
            0,
            presentationTimeUs,
            0
        )

        lastPresentationTimeUs =
            presentationTimeUs

        frameCount++

        drainEncoder(
            endOfStream = false
        )
    }

    fun finish() {
        if (!started) {
            return
        }

        val codec =
            encoder
                ?: throw IllegalStateException(
                    "Encoder ist nicht verfügbar."
                )

        try {
            queueEndOfStream(codec)

            var eosReached = false

            while (!eosReached) {
                eosReached =
                    drainEncoder(
                        endOfStream = true
                    )
            }

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
        } finally {
            release()
        }
    }

    private fun waitForInputBuffer(
        codec: MediaCodec
    ): Int {
        val timeoutUs =
            250_000L

        val maxWaitAttempts =
            40

        repeat(maxWaitAttempts) {
            val inputIndex =
                codec.dequeueInputBuffer(
                    timeoutUs
                )

            if (inputIndex >= 0) {
                return inputIndex
            }

            drainEncoder(
                endOfStream = false
            )
        }

        throw IllegalStateException(
            "Kein freier MediaCodec-Input-Buffer verfügbar."
        )
    }

    private fun queueEndOfStream(
        codec: MediaCodec
    ) {
        val timeoutUs =
            250_000L

        val maxAttempts =
            80

        repeat(maxAttempts) {
            val inputIndex =
                codec.dequeueInputBuffer(
                    timeoutUs
                )

            if (inputIndex >= 0) {
                val eosTimestamp =
                    if (lastPresentationTimeUs >= 0L) {
                        lastPresentationTimeUs +
                            (
                                1_000_000L /
                                    frameRate.toLong()
                                )
                    } else {
                        0L
                    }

                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    eosTimestamp,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )

                return
            }

            drainEncoder(
                endOfStream = false
            )
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

        var eosReached =
            false

        val timeoutUs =
            if (endOfStream) {
                250_000L
            } else {
                0L
            }

        while (true) {
            val outputIndex =
                codec.dequeueOutputBuffer(
                    bufferInfo,
                    timeoutUs
                )

            when {
                outputIndex ==
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    return eosReached
                }

                outputIndex ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

                    if (muxerStarted) {
                        throw IllegalStateException(
                            "Das Encoder-Ausgabeformat wurde mehrfach geändert."
                        )
                    }

                    val outputFormat =
                        codec.outputFormat

                    val currentMuxer =
                        muxer
                            ?: throw IllegalStateException(
                                "Muxer ist nicht verfügbar."
                            )

                    trackIndex =
                        currentMuxer.addTrack(
                            outputFormat
                        )

                    currentMuxer.start()

                    muxerStarted = true
                }

                outputIndex >= 0 -> {
                    val encodedData =
                        codec.getOutputBuffer(
                            outputIndex
                        )

                    if (encodedData != null) {
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
                        }
                    }

                    val isEndOfStream =
                        (
                            bufferInfo.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            ) != 0

                    codec.releaseOutputBuffer(
                        outputIndex,
                        false
                    )

                    if (isEndOfStream) {
                        eosReached = true
                        return true
                    }
                }

                else -> {
                    return eosReached
                }
            }
        }
    }

    private fun fillImageFromBitmap(
        bitmap: Bitmap,
        image: android.media.Image
    ) {
        val planes =
            image.planes

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

    private fun writeLumaPlane(
        pixels: IntArray,
        width: Int,
        height: Int,
        plane: android.media.Image.Plane
    ) {
        val buffer =
            plane.buffer

        val rowStride =
            plane.rowStride

        val pixelStride =
            plane.pixelStride

        require(rowStride > 0) {
            "Ungültiger Y-Row-Stride: $rowStride"
        }

        require(pixelStride > 0) {
            "Ungültiger Y-Pixel-Stride: $pixelStride"
        }

        require(buffer.remaining() > 0) {
            "Y-Plane besitzt keinen Speicher."
        }

        val basePosition =
            buffer.position()

        val bufferLimit =
            buffer.limit()

        for (y in 0 until height) {
            val rowStart =
                basePosition +
                    y * rowStride

            for (x in 0 until width) {
                val pixel =
                    pixels[
                        y * width + x
                    ]

                val r =
                    (pixel shr 16) and 0xFF

                val g =
                    (pixel shr 8) and 0xFF

                val b =
                    pixel and 0xFF

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
                        .coerceIn(
                            0,
                            255
                        )
                        .toByte()
                )
            }
        }
    }

    private fun writeChromaPlane(
        pixels: IntArray,
        width: Int,
        height: Int,
        plane: android.media.Image.Plane,
        uPlane: Boolean
    ) {
        val buffer =
            plane.buffer

        val rowStride =
            plane.rowStride

        val pixelStride =
            plane.pixelStride

        require(rowStride > 0) {
            "Ungültiger Chroma-Row-Stride: $rowStride"
        }

        require(pixelStride > 0) {
            "Ungültiger Chroma-Pixel-Stride: $pixelStride"
        }

        require(buffer.remaining() > 0) {
            "Chroma-Plane besitzt keinen Speicher."
        }

        val chromaWidth =
            (width + 1) / 2

        val chromaHeight =
            (height + 1) / 2

        val basePosition =
            buffer.position()

        val bufferLimit =
            buffer.limit()

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

                val r =
                    (pixel shr 16) and 0xFF

                val g =
                    (pixel shr 8) and 0xFF

                val b =
                    pixel and 0xFF

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
                        .coerceIn(
                            0,
                            255
                        )
                        .toByte()
                )
            }
        }
    }

    private fun findEncoderCodec(): String {
        val preferredNames =
            listOf(
                "c2.android.avc.encoder",
                "OMX.google.h264.encoder"
            )

        for (name in preferredNames) {
            try {
                val codec =
                    MediaCodec.createByCodecName(
                        name
                    )

                codec.release()

                return name
            } catch (_: Exception) {
            }
        }

        val codecList =
            MediaCodecList(
                MediaCodecList.ALL_CODECS
            )

        for (
            codecInfo in
            codecList.codecInfos
        ) {
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
                return codecInfo.name
            }
        }

        throw IllegalStateException(
            "Auf diesem Gerät wurde kein H.264/AVC-Encoder gefunden."
        )
    }

    private fun release() {
        try {
            encoder?.stop()
        } catch (_: Exception) {
        }

        try {
            encoder?.release()
        } catch (_: Exception) {
        }

        encoder = null

        if (muxerStarted) {
            try {
                muxer?.stop()
            } catch (_: Exception) {
            }
        }

        try {
            muxer?.release()
        } catch (_: Exception) {
        }

        muxer = null

        muxerStarted = false
        trackIndex = -1
        started = false
    }
}

package com.example.aivideostudio.render

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
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
    private var frameCount = 0L
    private var writtenSampleCount = 0L
    private var presentationTimeUs = 0L
    private var started = false

    private val bufferInfo = MediaCodec.BufferInfo()

    private var negotiatedColorFormat =
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible

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

        val format = MediaFormat.createVideoFormat(
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

        val codecName = findEncoderCodec()

        val codec = MediaCodec.createByCodecName(codecName)

        codec.configure(
            format,
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )

        negotiatedColorFormat =
            codec.inputFormat.getInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )

        encoder = codec

        muxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )

        codec.start()

        started = true
        frameCount = 0L
        writtenSampleCount = 0L
        presentationTimeUs = 0L
    }

    fun encodeFrame(bitmap: Bitmap) {
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

        val codec = encoder
            ?: error("Encoder ist nicht verfügbar.")

        val timeoutUs = 100_000L

        val inputIndex = codec.dequeueInputBuffer(timeoutUs)

        if (inputIndex >= 0) {
            val image = codec.getInputImage(inputIndex)

            if (image == null) {
                throw IllegalStateException(
                    "MediaCodec.getInputImage() hat ein null Image geliefert."
                )
            }

            try {
                val planes = image.planes

                require(planes.size >= 3) {
                    "Der Encoder stellt weniger als drei YUV-Planes bereit."
                }

                fillImageFromBitmap(
                    bitmap = bitmap,
                    image = image
                )
            } finally {
                image.close()
            }

            val pts = presentationTimeUs

            codec.queueInputBuffer(
                inputIndex,
                0,
                0,
                pts,
                0
            )

            presentationTimeUs += 1_000_000L / frameRate
            frameCount++
        }

        drainEncoder(endOfStream = false)
    }

    fun finish() {
        if (!started) {
            return
        }

        val codec = encoder
            ?: throw IllegalStateException("Encoder ist nicht verfügbar.")

        try {
            var eosQueued = false

            while (!eosQueued) {
                val inputIndex = codec.dequeueInputBuffer(100_000L)

                if (inputIndex >= 0) {
                    codec.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        presentationTimeUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )

                    eosQueued = true
                }

                drainEncoder(endOfStream = true)
            }

            var eosReached = false

            while (!eosReached) {
                eosReached = drainEncoder(endOfStream = true)
            }

            if (!muxerStarted) {
                throw IllegalStateException(
                    "Der MP4-Muxer wurde nie gestartet. Es wurde kein gültiger Video-Track erzeugt."
                )
            }

            if (writtenSampleCount <= 0L) {
                throw IllegalStateException(
                    "Der Encoder hat keine Videoframes in die MP4-Datei geschrieben."
                )
            }

            if (!outputFile.exists() || outputFile.length() <= 1024L) {
                throw IllegalStateException(
                    "Die erzeugte MP4-Datei ist ungültig oder leer: ${outputFile.absolutePath}"
                )
            }
        } finally {
            release()
        }
    }

    private fun drainEncoder(endOfStream: Boolean): Boolean {
        val codec = encoder ?: return false

        var eosReached = false

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(
                bufferInfo,
                if (endOfStream) 100_000L else 0L
            )

            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) {
                        return eosReached
                    }

                    return eosReached
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) {
                        throw IllegalStateException(
                            "Die Encoder-Ausgabeformatänderung trat mehrfach auf."
                        )
                    }

                    val outputFormat = codec.outputFormat

                    val currentMuxer = muxer
                        ?: throw IllegalStateException(
                            "Muxer ist nicht verfügbar."
                        )

                    trackIndex = currentMuxer.addTrack(outputFormat)

                    currentMuxer.start()

                    muxerStarted = true
                }

                outputIndex >= 0 -> {
                    val encodedData = codec.getOutputBuffer(outputIndex)

                    if (encodedData == null) {
                        codec.releaseOutputBuffer(outputIndex, false)
                        continue
                    }

                    if (bufferInfo.size > 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(
                            bufferInfo.offset + bufferInfo.size
                        )

                        muxer?.writeSampleData(
                            trackIndex,
                            encodedData,
                            bufferInfo
                        )

                        writtenSampleCount++
                    }

                    val isEndOfStream =
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

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
        val planes = image.planes

        if (planes.size < 3) {
            throw IllegalStateException(
                "YUV-Image besitzt nicht genügend Planes."
            )
        }

        val pixelBuffer = IntArray(
            bitmap.width * bitmap.height
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
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        if (rowStride <= 0 || pixelStride <= 0) {
            throw IllegalStateException(
                "Ungültige Y-Plane-Strides: rowStride=$rowStride pixelStride=$pixelStride"
            )
        }

        val requiredRows = height

        if (buffer.remaining() <= 0) {
            throw IllegalStateException(
                "Y-Plane besitzt keinen beschreibbaren Speicher."
            )
        }

        val basePosition = buffer.position()

        try {
            for (y in 0 until height) {
                val rowStart = basePosition + y * rowStride

                for (x in 0 until width) {
                    val pixel = pixels[y * width + x]

                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF

                    val yValue =
                        ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16

                    val position =
                        rowStart + x * pixelStride

                    if (position < basePosition ||
                        position >= basePosition + buffer.capacity()
                    ) {
                        throw IllegalStateException(
                            "Y-Plane überschreitet die verfügbare Buffer-Kapazität."
                        )
                    }

                    buffer.put(
                        position,
                        yValue.coerceIn(0, 255).toByte()
                    )
                }
            }
        } catch (e: IndexOutOfBoundsException) {
            throw IllegalStateException(
                "Fehler beim Schreiben der Y-Plane.",
                e
            )
        }
    }

    private fun writeChromaPlane(
        pixels: IntArray,
        width: Int,
        height: Int,
        plane: android.media.Image.Plane,
        uPlane: Boolean
    ) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        if (rowStride <= 0 || pixelStride <= 0) {
            throw IllegalStateException(
                "Ungültige Chroma-Plane-Strides: rowStride=$rowStride pixelStride=$pixelStride"
            )
        }

        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2

        val basePosition = buffer.position()

        if (buffer.remaining() <= 0) {
            throw IllegalStateException(
                "Chroma-Plane besitzt keinen beschreibbaren Speicher."
            )
        }

        try {
            for (y in 0 until chromaHeight) {
                val sourceY = min(
                    y * 2,
                    height - 1
                )

                val rowStart =
                    basePosition + y * rowStride

                for (x in 0 until chromaWidth) {
                    val sourceX = min(
                        x * 2,
                        width - 1
                    )

                    val pixel =
                        pixels[sourceY * width + sourceX]

                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF

                    val value = if (uPlane) {
                        ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    } else {
                        ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    }

                    val position =
                        rowStart + x * pixelStride

                    if (position < basePosition ||
                        position >= basePosition + buffer.capacity()
                    ) {
                        throw IllegalStateException(
                            "Chroma-Plane überschreitet die verfügbare Buffer-Kapazität."
                        )
                    }

                    buffer.put(
                        position,
                        value.coerceIn(0, 255).toByte()
                    )
                }
            }
        } catch (e: IndexOutOfBoundsException) {
            throw IllegalStateException(
                "Fehler beim Schreiben der Chroma-Plane.",
                e
            )
        }
    }

    private fun findEncoderCodec(): String {
        val preferredNames = listOf(
            "c2.android.avc.encoder",
            "OMX.google.h264.encoder"
        )

        for (name in preferredNames) {
            try {
                val codecInfo =
                    MediaCodec.createByCodecName(name)

                codecInfo.release()

                return name
            } catch (_: Exception) {
            }
        }

        val codecList =
            MediaCodecList(MediaCodecList.ALL_CODECS)

        for (codecInfo in codecList.codecInfos) {
            if (codecInfo.isEncoder) {
                val supportsAvc =
                    codecInfo.supportedTypes.any {
                        it.equals(
                            MediaFormat.MIMETYPE_VIDEO_AVC,
                            ignoreCase = true
                        )
                    }

                if (supportsAvc) {
                    return codecInfo.name
                }
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

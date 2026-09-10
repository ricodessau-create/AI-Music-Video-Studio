package com.example.aivideostudio.render

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

class VideoEncoder(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val bitrate: Int
) {

    private lateinit var encoder: MediaCodec
    private lateinit var muxer: MediaMuxer
    private var trackIndex = -1
    private var muxerStarted = false
    private var writtenSampleCount = 0
    private var totalWrittenBytes = 0L
    private var encodeFrameCallCount = 0
    private var negotiatedColorFormat = -1
    private var usedEncoderName = "unbekannt"

    private val pixelBuffer = IntArray(width * height)

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)

        encoder = createPreferredEncoder()
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        negotiatedColorFormat = try {
            encoder.inputFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT)
        } catch (exception: Exception) {
            -1
        }

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun createPreferredEncoder(): MediaCodec {
        val preferredNames = listOf("c2.android.avc.encoder", "OMX.google.h264.encoder")
        try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (name in preferredNames) {
                val info = codecList.codecInfos.firstOrNull { it.name == name && it.isEncoder }
                if (info != null) {
                    usedEncoderName = name
                    return MediaCodec.createByCodecName(name)
                }
            }
        } catch (exception: Exception) {
            // Fällt unten auf den Standard-Encoder zurück
        }
        val fallback = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        usedEncoderName = fallback.name
        return fallback
    }

    fun encodeFrame(bitmap: Bitmap, presentationTimeUs: Long) {
        encodeFrameCallCount++
        var inputIndex = -1
        var waitAttempts = 0
        while (inputIndex < 0 && waitAttempts < 1000) {
            inputIndex = encoder.dequeueInputBuffer(10000)
            if (inputIndex < 0) {
                drainEncoder(false)
                waitAttempts++
            }
        }

        if (inputIndex < 0) {
            throw IllegalStateException("Encoder hat über einen langen Zeitraum keinen Eingabepuffer bereitgestellt.")
        }

        val image = encoder.getInputImage(inputIndex)
            ?: throw IllegalStateException("Encoder liefert kein Image für den Eingabepuffer (Buffer-Modus nicht verfügbar).")

        fillImageFromBitmap(image, bitmap)
        encoder.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)

        drainEncoder(false)
    }

    private fun fillImageFromBitmap(image: Image, bitmap: Bitmap) {
        bitmap.getPixels(pixelBuffer, 0, width, 0, 0, width, height)
        val planes = image.planes

        writeLumaPlane(planes[0], pixelBuffer)
        writeChromaPlane(planes[1], pixelBuffer, isU = true)
        writeChromaPlane(planes[2], pixelBuffer, isU = false)
    }

    private fun writeLumaPlane(plane: Image.Plane, pixels: IntArray) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowBytes = ByteArray(width)

        for (row in 0 until height) {
            val rowBase = row * width
            if (pixelStride == 1) {
                for (col in 0 until width) {
                    rowBytes[col] = computeY(pixels[rowBase + col])
                }
                buffer.position(row * rowStride)
                buffer.put(rowBytes, 0, width)
            } else {
                val rowStart = row * rowStride
                for (col in 0 until width) {
                    buffer.put(rowStart + col * pixelStride, computeY(pixels[rowBase + col]))
                }
            }
        }
    }

    private fun writeChromaPlane(plane: Image.Plane, pixels: IntArray, isU: Boolean) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val rowBytes = ByteArray(chromaWidth)

        for (row in 0 until chromaHeight) {
            val srcRow = (row * 2).coerceAtMost(height - 1)
            if (pixelStride == 1) {
                for (col in 0 until chromaWidth) {
                    val srcCol = (col * 2).coerceAtMost(width - 1)
                    val pixel = pixels[srcRow * width + srcCol]
                    rowBytes[col] = if (isU) computeU(pixel) else computeV(pixel)
                }
                buffer.position(row * rowStride)
                buffer.put(rowBytes, 0, chromaWidth)
            } else {
                val rowStart = row * rowStride
                for (col in 0 until chromaWidth) {
                    val srcCol = (col * 2).coerceAtMost(width - 1)
                    val pixel = pixels[srcRow * width + srcCol]
                    val value = if (isU) computeU(pixel) else computeV(pixel)
                    buffer.put(rowStart + col * pixelStride, value)
                }
            }
        }
    }

    private fun computeY(pixel: Int): Byte {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val y = 0.257 * r + 0.504 * g + 0.098 * b + 16.0
        return y.toInt().coerceIn(0, 255).toByte()
    }

    private fun computeU(pixel: Int): Byte {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val u = -0.148 * r - 0.291 * g + 0.439 * b + 128.0
        return u.toInt().coerceIn(0, 255).toByte()
    }

    private fun computeV(pixel: Int): Byte {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val v = 0.439 * r - 0.368 * g - 0.071 * b + 128.0
        return v.toInt().coerceIn(0, 255).toByte()
    }

    private fun signalEndOfStreamViaBuffer() {
        var inputIndex = -1
        var waitAttempts = 0
        while (inputIndex < 0 && waitAttempts < 1000) {
            inputIndex = encoder.dequeueInputBuffer(10000)
            if (inputIndex < 0) {
                drainEncoder(false)
                waitAttempts++
            }
        }

        if (inputIndex < 0) {
            throw IllegalStateException("Encoder hat keinen Eingabepuffer für das Streamende bereitgestellt.")
        }

        encoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outputIndex >= 0 -> {
                    val encodedData: ByteBuffer? = encoder.getOutputBuffer(outputIndex)
                    val isConfigFrame = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (encodedData != null && bufferInfo.size > 0 && muxerStarted && !isConfigFrame) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        writtenSampleCount++
                        totalWrittenBytes += bufferInfo.size
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
            }
        }
    }

    fun finish() {
        signalEndOfStreamViaBuffer()
        drainEncoder(true)

        try {
            encoder.stop()
        } catch (exception: Exception) {
            throw IllegalStateException(
                "encoder.stop() ist fehlgeschlagen (Encoder: $usedEncoderName, Farbformat: $negotiatedColorFormat, ${writtenSampleCount} Samples / ${totalWrittenBytes} Bytes, ${encodeFrameCallCount} Frame-Aufrufe): ${exception.message}",
                exception
            )
        }
        encoder.release()

        if (!muxerStarted) {
            throw IllegalStateException(
                "Video-Encoder ($usedEncoderName, Farbformat: $negotiatedColorFormat) hat kein gültiges Ausgabeformat geliefert bei ${encodeFrameCallCount} Frame-Aufrufen."
            )
        }
        if (writtenSampleCount == 0) {
            throw IllegalStateException(
                "Video-Encoder ($usedEncoderName, Farbformat: $negotiatedColorFormat) hat ein Ausgabeformat gemeldet, aber 0 von ${encodeFrameCallCount} Frames tatsächlich geschrieben."
            )
        }

        try {
            muxer.stop()
        } catch (exception: Exception) {
            throw IllegalStateException(
                "muxer.stop() ist fehlgeschlagen (${writtenSampleCount} Samples / ${totalWrittenBytes} Bytes geschrieben): ${exception.message}",
                exception
            )
        }
        muxer.release()

        if (!outputFile.exists() || outputFile.length() < 1024L) {
            throw IllegalStateException(
                "Rohvideo-Datei ist nach dem Muxen ungültig oder zu klein: ${outputFile.length()} Bytes bei ${writtenSampleCount} geschriebenen Samples."
            )
        }
    }
}

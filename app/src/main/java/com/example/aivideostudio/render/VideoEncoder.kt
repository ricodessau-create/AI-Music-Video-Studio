package com.example.aivideostudio.render

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
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
    private lateinit var inputSurface: Surface
    private lateinit var muxer: MediaMuxer
    private var trackIndex = -1
    private var muxerStarted = false
    private var frameIndex = 0L

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun getInputSurface(): Surface = inputSurface

    fun encodeFrame(bitmap: Bitmap, presentationTimeUs: Long) {
        val canvas = inputSurface.lockCanvas(null)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        inputSurface.unlockCanvasAndPost(canvas)
        drainEncoder(false)
        frameIndex++
    }

    private fun drainEncoder(endOfStream: Boolean) {
        if (endOfStream) {
            encoder.signalEndOfInputStream()
        }

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
                    if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
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
        drainEncoder(true)
        encoder.stop()
        encoder.release()
        if (muxerStarted) {
            muxer.stop()
        }
        muxer.release()
    }
}

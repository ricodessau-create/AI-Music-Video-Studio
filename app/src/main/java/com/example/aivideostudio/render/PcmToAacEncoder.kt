package com.example.aivideostudio.render

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import kotlin.math.max
import kotlin.math.min

class PcmToAacEncoder(
    private val outputFile: File,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val bitRate: Int = 128000
) {

    fun encode(samples: FloatArray) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false

        val samplesPerChunkPerChannel = 1024
        val bytesPerFrame = 2 * channelCount
        val chunkByteSize = samplesPerChunkPerChannel * bytesPerFrame
        val ptsIncrementUs = (samplesPerChunkPerChannel.toLong() * 1_000_000L) / sampleRate.toLong()

        var floatIndex = 0
        var presentationTimeUs = 0L
        var inputDone = false
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            while (true) {
                if (!inputDone) {
                    val inputIndex = encoder.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputIndex)!!
                        inputBuffer.clear()

                        val remainingSamples = samples.size - floatIndex
                        if (remainingSamples <= 0) {
                            encoder.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val samplesToWrite = min(samplesPerChunkPerChannel * channelCount, remainingSamples)
                            for (i in 0 until samplesToWrite) {
                                val floatValue = samples[floatIndex + i]
                                val clamped = (floatValue * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
                                inputBuffer.put((clamped.toInt() and 0xFF).toByte())
                                inputBuffer.put(((clamped.toInt() shr 8) and 0xFF).toByte())
                            }
                            val bytesWritten = samplesToWrite * 2
                            encoder.queueInputBuffer(inputIndex, 0, bytesWritten, presentationTimeUs, 0)
                            floatIndex += samplesToWrite
                            val framesWritten = samplesToWrite / channelCount
                            presentationTimeUs += (framesWritten.toLong() * 1_000_000L) / sampleRate.toLong()
                        }
                    }
                }

                val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone) {
                            // weiter warten, bis der Encoder das Ende signalisiert
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outputIndex >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(outputIndex)
                        if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            return finish(encoder, muxer, muxerStarted)
                        }
                    }
                }
            }
        } catch (exception: Exception) {
            finish(encoder, muxer, muxerStarted)
            throw exception
        }
    }

    private fun finish(encoder: MediaCodec, muxer: MediaMuxer, muxerStarted: Boolean) {
        try {
            encoder.stop()
        } catch (exception: Exception) {
            // Encoder war bereits in einem Fehlerzustand, nichts weiter zu tun
        }
        encoder.release()
        if (muxerStarted) {
            try {
                muxer.stop()
            } catch (exception: Exception) {
                // Muxer war bereits in einem Fehlerzustand
            }
        }
        muxer.release()
    }
}

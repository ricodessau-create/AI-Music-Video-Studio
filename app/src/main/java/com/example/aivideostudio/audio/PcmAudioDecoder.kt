package com.example.aivideostudio.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer

class PcmAudioDecoder(private val filePath: String) {

    data class DecodedAudio(
        val samples: FloatArray,
        val sampleRate: Int,
        val channelCount: Int,
        val durationMicros: Long
    )

    private class GrowableFloatBuffer(initialCapacity: Int) {
        private var backingArray = FloatArray(initialCapacity.coerceAtLeast(1024))
        private var size = 0

        fun append(value: Float) {
            if (size >= backingArray.size) {
                grow()
            }
            backingArray[size] = value
            size++
        }

        private fun grow() {
            val newCapacity = (backingArray.size.toLong() * 2L).coerceAtMost(Int.MAX_VALUE.toLong() - 8L).toInt()
            val grownArray = FloatArray(newCapacity)
            System.arraycopy(backingArray, 0, grownArray, 0, size)
            backingArray = grownArray
        }

        fun toFloatArray(): FloatArray {
            val trimmedArray = FloatArray(size)
            System.arraycopy(backingArray, 0, trimmedArray, 0, size)
            return trimmedArray
        }
    }

    fun decode(): DecodedAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(filePath)

        var trackIndex = -1
        var format: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(i)
            val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = candidate
                break
            }
        }

        require(trackIndex >= 0 && format != null) { "Keine Audiospur gefunden" }

        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durationMicros = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else {
            0L
        }

        val bufferInfo = MediaCodec.BufferInfo()
        val estimatedSampleCount = if (durationMicros > 0L) {
            ((durationMicros / 1_000_000.0) * sampleRate * channelCount).toInt().coerceAtLeast(65536)
        } else {
            sampleRate * channelCount * 60
        }
        val outputSamples = GrowableFloatBuffer(estimatedSampleCount)
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(10000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val presentationTime = extractor.sampleTime
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
            if (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    appendPcmSamples(outputBuffer, bufferInfo, outputSamples)
                }
                codec.releaseOutputBuffer(outputIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        return DecodedAudio(
            samples = outputSamples.toFloatArray(),
            sampleRate = sampleRate,
            channelCount = channelCount,
            durationMicros = durationMicros
        )
    }

    private fun appendPcmSamples(buffer: ByteBuffer, info: MediaCodec.BufferInfo, out: GrowableFloatBuffer) {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val shortBuffer = buffer.asShortBuffer()
        val shortCount = shortBuffer.remaining()
        for (i in 0 until shortCount) {
            val sample = shortBuffer.get(i)
            out.append(sample / 32768.0f)
        }
    }
}

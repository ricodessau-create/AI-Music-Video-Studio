package com.example.aivideostudio.render

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.example.aivideostudio.audio.PcmAudioDecoder
import java.io.File
import java.nio.ByteBuffer

class AudioVideoMuxer(
    private val videoOnlyFile: File,
    private val originalAudioFilePath: String,
    private val outputFile: File
) {

    fun mux() {
        val tempAacAudioFile = File(outputFile.parentFile, "temp_audio_${System.currentTimeMillis()}.m4a")

        try {
            transcodeAudioToAac(tempAacAudioFile)
            muxVideoAndAac(tempAacAudioFile)
        } finally {
            if (tempAacAudioFile.exists()) {
                tempAacAudioFile.delete()
            }
        }
    }

    private fun transcodeAudioToAac(targetFile: File) {
        val decoded = PcmAudioDecoder(originalAudioFilePath).decode()
        val encoder = PcmToAacEncoder(
            outputFile = targetFile,
            sampleRate = decoded.sampleRate,
            channelCount = decoded.channelCount
        )
        encoder.encode(decoded.samples)
    }

    private fun muxVideoAndAac(aacAudioFile: File) {
        val videoExtractor = MediaExtractor()
        videoExtractor.setDataSource(videoOnlyFile.absolutePath)

        val audioExtractor = MediaExtractor()
        audioExtractor.setDataSource(aacAudioFile.absolutePath)

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val videoTrackIndex = selectTrack(videoExtractor, "video/")
        val audioTrackIndex = selectTrack(audioExtractor, "audio/")

        require(videoTrackIndex >= 0) { "Keine Videospur im Rohvideo gefunden" }
        require(audioTrackIndex >= 0) { "Keine Audiospur nach der Umwandlung gefunden" }

        videoExtractor.selectTrack(videoTrackIndex)
        audioExtractor.selectTrack(audioTrackIndex)

        val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
        val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)

        val muxerVideoTrack = muxer.addTrack(videoFormat)
        val muxerAudioTrack = muxer.addTrack(audioFormat)

        muxer.start()

        copyTrack(videoExtractor, muxer, muxerVideoTrack)
        copyTrack(audioExtractor, muxer, muxerAudioTrack)

        muxer.stop()
        muxer.release()
        videoExtractor.release()
        audioExtractor.release()
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) {
                return i
            }
        }
        return -1
    }

    private fun copyTrack(extractor: MediaExtractor, muxer: MediaMuxer, muxerTrackIndex: Int) {
        val bufferSize = 1 * 1024 * 1024
        val buffer = ByteBuffer.allocate(bufferSize)
        val bufferInfo = android.media.MediaCodec.BufferInfo()

        while (true) {
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) {
                break
            }
            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = extractor.sampleTime
            bufferInfo.flags = extractor.sampleFlags
            muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
            extractor.advance()
        }
    }
}

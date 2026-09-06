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
            validateEncodedAudioFile(tempAacAudioFile)
            muxVideoAndAac(tempAacAudioFile)
        } finally {
            if (tempAacAudioFile.exists()) {
                tempAacAudioFile.delete()
            }
        }
    }

    private fun transcodeAudioToAac(targetFile: File) {
        val decoded = PcmAudioDecoder(originalAudioFilePath).decode()
        require(decoded.samples.isNotEmpty()) { "Audiodatei enthält keine lesbaren Audiodaten (leeres PCM-Ergebnis)." }
        val encoder = PcmToAacEncoder(
            outputFile = targetFile,
            sampleRate = decoded.sampleRate,
            channelCount = decoded.channelCount
        )
        encoder.encode(decoded.samples)
    }

    private fun validateEncodedAudioFile(file: File) {
        if (!file.exists() || file.length() < 512L) {
            throw IllegalStateException(
                "Audioumwandlung nach AAC ist fehlgeschlagen: erzeugte Datei ist leer oder ungültig (${file.length()} Bytes)."
            )
        }
    }

    private fun muxVideoAndAac(aacAudioFile: File) {
        val videoExtractor = MediaExtractor()
        try {
            videoExtractor.setDataSource(videoOnlyFile.absolutePath)
        } catch (exception: Exception) {
            throw IllegalStateException(
                "Konnte Rohvideo-Datei nicht öffnen (${videoOnlyFile.absolutePath}): ${exception.message}",
                exception
            )
        }

        val audioExtractor = MediaExtractor()
        try {
            audioExtractor.setDataSource(aacAudioFile.absolutePath)
        } catch (exception: Exception) {
            throw IllegalStateException(
                "Konnte umgewandelte Audiodatei nicht öffnen (${aacAudioFile.absolutePath}, ${aacAudioFile.length()} Bytes): ${exception.message}",
                exception
            )
        }

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

package com.example.aivideostudio.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

data class GeneratedVideoInfo(
    val filePath: String,
    val durationMicros: Long,
    val width: Int,
    val height: Int
)

class GeneratedMediaLoader {

    fun loadImage(filePath: String): Bitmap? {
        return try {
            BitmapFactory.decodeFile(filePath)
        } catch (exception: Exception) {
            null
        }
    }

    fun inspectVideo(filePath: String): GeneratedVideoInfo? {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(filePath)
            var width = 0
            var height = 0
            var durationMicros = 0L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    width = format.getInteger(MediaFormat.KEY_WIDTH)
                    height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        durationMicros = format.getLong(MediaFormat.KEY_DURATION)
                    }
                    break
                }
            }
            extractor.release()
            if (width == 0 || height == 0) return null
            GeneratedVideoInfo(filePath, durationMicros, width, height)
        } catch (exception: Exception) {
            null
        }
    }

    fun fileExists(filePath: String?): Boolean {
        if (filePath == null) return false
        return File(filePath).exists()
    }
}

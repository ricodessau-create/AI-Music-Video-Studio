package com.example.aivideostudio.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.example.aivideostudio.data.GeneratedMediaType

class AiSceneFrameSource(
    private val filePath: String,
    private val mediaType: GeneratedMediaType,
    private val outputWidth: Int,
    private val outputHeight: Int
) {

    private var videoRetriever: MediaMetadataRetriever? = null
    private var videoDurationMicros: Long = 0L
    private var staticBitmap: Bitmap? = null

    fun prepare() {
        when (mediaType) {
            GeneratedMediaType.IMAGE -> {
                staticBitmap = decodeAndScale(filePath)
            }
            GeneratedMediaType.VIDEO -> {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(filePath)
                videoRetriever = retriever
                val durationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                videoDurationMicros = (durationString?.toLongOrNull() ?: 0L) * 1000L
            }
            GeneratedMediaType.NONE -> Unit
        }
    }

    fun getFrameBitmapAtSceneProgress(sceneProgress: Float): Bitmap? {
        return when (mediaType) {
            GeneratedMediaType.IMAGE -> staticBitmap
            GeneratedMediaType.VIDEO -> {
                val retriever = videoRetriever ?: return null
                if (videoDurationMicros <= 0L) return null
                val loopedProgress = sceneProgress % 1.0f
                val targetTimeMicros = (loopedProgress * videoDurationMicros).toLong()
                val frame = retriever.getFrameAtTime(targetTimeMicros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                frame?.let { scaleBitmap(it) }
            }
            GeneratedMediaType.NONE -> null
        }
    }

    fun release() {
        videoRetriever?.release()
        videoRetriever = null
        staticBitmap?.recycle()
        staticBitmap = null
    }

    private fun decodeAndScale(path: String): Bitmap? {
        val original = BitmapFactory.decodeFile(path) ?: return null
        val scaled = scaleBitmap(original)
        if (scaled != original) {
            original.recycle()
        }
        return scaled
    }

    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        if (bitmap.width == outputWidth && bitmap.height == outputHeight) return bitmap
        return Bitmap.createScaledBitmap(bitmap, outputWidth, outputHeight, true)
    }
}

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
    private var videoDurationMicros = 0L
    private var staticBitmap: Bitmap? = null

    fun prepare() {
        release()

        when (mediaType) {
            GeneratedMediaType.IMAGE -> {
                staticBitmap = decodeAndScale(filePath)
            }

            GeneratedMediaType.VIDEO -> {
                val file = java.io.File(filePath)

                require(
                    file.exists() &&
                        file.isFile &&
                        file.length() > 0L
                ) {
                    "Generiertes Video nicht gefunden: $filePath"
                }

                val retriever =
                    MediaMetadataRetriever()

                retriever.setDataSource(
                    file.absolutePath
                )

                videoRetriever = retriever

                val duration =
                    retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull()

                videoDurationMicros =
                    (duration ?: 0L) * 1000L

                require(videoDurationMicros > 0L) {
                    "Das generierte Video besitzt keine gültige Dauer."
                }
            }

            GeneratedMediaType.NONE -> Unit
        }
    }

    fun getFrameBitmapAtSceneProgress(
        sceneProgress: Float
    ): Bitmap? {
        return when (mediaType) {
            GeneratedMediaType.IMAGE -> {
                val bitmap =
                    staticBitmap ?: return null

                if (bitmap.isRecycled) {
                    return null
                }

                bitmap.copy(
                    Bitmap.Config.ARGB_8888,
                    false
                )
            }

            GeneratedMediaType.VIDEO -> {
                val retriever =
                    videoRetriever ?: return null

                if (videoDurationMicros <= 0L) {
                    return null
                }

                val progress =
                    sceneProgress.coerceIn(
                        0f,
                        1f
                    )

                val timestamp =
                    (
                        progress *
                            videoDurationMicros
                        ).toLong()
                            .coerceIn(
                                0L,
                                videoDurationMicros - 1L
                            )

                val frame =
                    retriever.getFrameAtTime(
                        timestamp,
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )
                        ?: return null

                scaleBitmap(frame)
            }

            GeneratedMediaType.NONE -> null
        }
    }

    fun release() {
        videoRetriever?.release()
        videoRetriever = null

        staticBitmap?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }

        staticBitmap = null
        videoDurationMicros = 0L
    }

    private fun decodeAndScale(
        path: String
    ): Bitmap? {
        val file =
            java.io.File(path)

        if (
            !file.exists() ||
            !file.isFile ||
            file.length() <= 0L
        ) {
            return null
        }

        val bitmap =
            BitmapFactory.decodeFile(
                file.absolutePath
            )
                ?: return null

        return scaleAndRecycleSource(
            bitmap
        )
    }

    private fun scaleAndRecycleSource(
        bitmap: Bitmap
    ): Bitmap {
        if (
            bitmap.width == outputWidth &&
            bitmap.height == outputHeight
        ) {
            return bitmap
        }

        val scaled =
            scaleBitmap(bitmap)

        if (
            scaled !== bitmap &&
            !bitmap.isRecycled
        ) {
            bitmap.recycle()
        }

        return scaled
    }

    private fun scaleBitmap(
        bitmap: Bitmap
    ): Bitmap {
        if (
            bitmap.width == outputWidth &&
            bitmap.height == outputHeight
        ) {
            return bitmap
        }

        return Bitmap.createScaledBitmap(
            bitmap,
            outputWidth,
            outputHeight,
            true
        )
    }
}
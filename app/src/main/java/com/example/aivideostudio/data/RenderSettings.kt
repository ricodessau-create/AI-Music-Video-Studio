package com.example.aivideostudio.data

data class RenderSettings(
    val resolutionWidth: Int,
    val resolutionHeight: Int,
    val aspectRatio: String,
    val frameRate: Int,
    val videoBitrate: Int
)

object RenderPresets {

    fun forResolutionLabel(label: String, aspectRatio: String): RenderSettings {
        val baseHeight = when (label) {
            "720p" -> 720
            "1080p" -> 1080
            "4K" -> 2160
            else -> 1080
        }
        val dimensions = computeDimensions(baseHeight, aspectRatio)
        val bitrate = when (label) {
            "720p" -> 6_000_000
            "1080p" -> 12_000_000
            "4K" -> 40_000_000
            else -> 12_000_000
        }
        return RenderSettings(
            resolutionWidth = dimensions.first,
            resolutionHeight = dimensions.second,
            aspectRatio = aspectRatio,
            frameRate = 30,
            videoBitrate = bitrate
        )
    }

    private fun computeDimensions(baseHeight: Int, aspectRatio: String): Pair<Int, Int> {
        return when (aspectRatio) {
            "16:9" -> {
                val width = (baseHeight * 16 / 9).roundUpToEven()
                width to baseHeight
            }
            "9:16" -> {
                val width = (baseHeight * 9 / 16).roundUpToEven()
                baseHeight to width
            }
            "1:1" -> baseHeight to baseHeight
            else -> {
                val width = (baseHeight * 16 / 9).roundUpToEven()
                width to baseHeight
            }
        }
    }

    private fun Int.roundUpToEven(): Int {
        return if (this % 2 == 0) this else this + 1
    }
}

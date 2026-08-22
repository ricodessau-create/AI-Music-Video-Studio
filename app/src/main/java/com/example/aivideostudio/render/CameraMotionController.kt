package com.example.aivideostudio.render

data class CameraState(
    val x: Float,
    val y: Float,
    val zoom: Float
)

class CameraMotionController(private val movementType: String) {

    fun computeState(progress: Float, intensity: Float): CameraState {
        val clampedProgress = progress.coerceIn(0f, 1f)
        return when (movementType) {
            "pan_left" -> CameraState(x = -clampedProgress * 80f, y = 0f, zoom = 1.05f)
            "pan_right" -> CameraState(x = clampedProgress * 80f, y = 0f, zoom = 1.05f)
            "zoom_in" -> CameraState(x = 0f, y = 0f, zoom = 1.0f + clampedProgress * 0.3f * (0.5f + intensity))
            "zoom_out" -> CameraState(x = 0f, y = 0f, zoom = 1.3f - clampedProgress * 0.3f * (0.5f + intensity))
            "fast_zoom" -> CameraState(x = 0f, y = 0f, zoom = 1.0f + clampedProgress * 0.6f * (0.5f + intensity))
            "slow_pan" -> CameraState(x = (clampedProgress - 0.5f) * 40f, y = (clampedProgress - 0.5f) * 20f, zoom = 1.05f)
            "static_with_parallax" -> CameraState(x = 0f, y = 0f, zoom = 1.02f)
            else -> CameraState(x = 0f, y = 0f, zoom = 1.0f)
        }
    }
}

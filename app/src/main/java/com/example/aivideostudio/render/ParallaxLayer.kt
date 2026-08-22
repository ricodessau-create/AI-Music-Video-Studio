package com.example.aivideostudio.render

data class ParallaxLayer(
    val bitmapKey: String,
    val depthFactor: Float,
    var offsetX: Float = 0f,
    var offsetY: Float = 0f,
    var scale: Float = 1.0f
)

class ParallaxEngine(private val layers: List<ParallaxLayer>) {

    fun update(cameraX: Float, cameraY: Float, cameraZoom: Float) {
        for (layer in layers) {
            layer.offsetX = -cameraX * layer.depthFactor
            layer.offsetY = -cameraY * layer.depthFactor
            layer.scale = 1.0f + (cameraZoom - 1.0f) * layer.depthFactor
        }
    }

    fun getLayers(): List<ParallaxLayer> = layers
}

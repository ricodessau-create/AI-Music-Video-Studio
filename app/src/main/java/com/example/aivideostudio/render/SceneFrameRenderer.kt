package com.example.aivideostudio.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.example.aivideostudio.storyboard.GeneratedScene
import com.example.aivideostudio.storyboard.VisualParameters
import kotlin.math.cos
import kotlin.math.sin

class SceneFrameRenderer(
    private val outputWidth: Int,
    private val outputHeight: Int
) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPool = HashMap<String, Bitmap>()
    private var frameIndex = 0
    private var smoothedAmplitude = 0f

    fun registerBitmap(key: String, bitmap: Bitmap) {
        bitmapPool[key] = bitmap
    }

    fun unregisterBitmap(key: String) {
        val bitmap = bitmapPool.remove(key)
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    fun renderFrame(
        scene: GeneratedScene,
        visualParameters: VisualParameters,
        parallaxEngine: ParallaxEngine,
        cameraState: CameraState,
        particleSnapshots: Map<ParticleType, List<Particle>>,
        audioAmplitude: Float,
        backgroundKey: String?,
        aiFrameOverride: Bitmap? = null
    ): Bitmap {
        val outputBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)

        frameIndex++
        smoothedAmplitude = smoothedAmplitude * 0.6f + audioAmplitude * 0.4f
        val performanceState = applyPerformanceMotion(cameraState, smoothedAmplitude * smoothedAmplitude)

        drawBaseBackground(canvas, visualParameters)

        if (aiFrameOverride != null) {
            drawAiFrameWithMotion(canvas, aiFrameOverride, performanceState)
        } else {
            drawParallaxLayers(canvas, parallaxEngine, performanceState, backgroundKey)
        }

        drawParticles(canvas, particleSnapshots)
        drawVignette(canvas)
        if (audioAmplitude > 0.6f) {
            drawBassPulseOverlay(canvas, audioAmplitude)
        }
        if (visualParameters.hasRuneOverlay) {
            drawRuneOverlay(canvas)
        }
        drawFilmGrain(canvas)

        return outputBitmap
    }

    private fun applyPerformanceMotion(cameraState: CameraState, pulse: Float): CameraState {
        val time = frameIndex.toFloat()
        val swayX = sin(time * 0.045f) * outputWidth * 0.006f
        val swayY = cos(time * 0.037f) * outputHeight * 0.004f
        val jitterX = sin(time * 1.7f) * pulse * outputWidth * 0.004f
        val jitterY = cos(time * 2.3f) * pulse * outputHeight * 0.004f
        return CameraState(
            x = cameraState.x + swayX + jitterX,
            y = cameraState.y + swayY + jitterY,
            zoom = cameraState.zoom * (1.03f + 0.035f * pulse)
        )
    }

    private fun drawAiFrameWithMotion(canvas: Canvas, aiFrame: Bitmap, cameraState: CameraState) {
        val matrix = Matrix()
        val scaleX = outputWidth.toFloat() / aiFrame.width.toFloat() * cameraState.zoom
        val scaleY = outputHeight.toFloat() / aiFrame.height.toFloat() * cameraState.zoom
        val scale = maxOf(scaleX, scaleY)
        matrix.postScale(scale, scale)
        val scaledWidth = aiFrame.width * scale
        val scaledHeight = aiFrame.height * scale
        val translateX = (outputWidth - scaledWidth) / 2f + cameraState.x
        val translateY = (outputHeight - scaledHeight) / 2f + cameraState.y
        matrix.postTranslate(translateX, translateY)
        paint.alpha = 255
        canvas.drawBitmap(aiFrame, matrix, paint)
    }

    private fun drawBaseBackground(canvas: Canvas, visualParameters: VisualParameters) {
        val baseColor = when (visualParameters.colorPaletteKey) {
            "frozen_dark" -> Color.rgb(10, 18, 28)
            "infernal_dark" -> Color.rgb(28, 8, 6)
            "frozen" -> Color.rgb(30, 45, 60)
            "infernal" -> Color.rgb(50, 15, 10)
            "gothic_dark" -> Color.rgb(15, 15, 18)
            else -> Color.rgb(20, 20, 24)
        }
        canvas.drawColor(baseColor)
    }

    private fun drawParallaxLayers(
        canvas: Canvas,
        parallaxEngine: ParallaxEngine,
        cameraState: CameraState,
        backgroundKey: String?
    ) {
        parallaxEngine.update(cameraState.x, cameraState.y, cameraState.zoom)
        for (layer in parallaxEngine.getLayers()) {
            val bitmap = bitmapPool[layer.bitmapKey] ?: bitmapPool[backgroundKey] ?: continue
            val matrix = Matrix()
            val scaleX = outputWidth.toFloat() / bitmap.width.toFloat() * layer.scale
            val scaleY = outputHeight.toFloat() / bitmap.height.toFloat() * layer.scale
            val scale = maxOf(scaleX, scaleY)
            matrix.postScale(scale, scale)
            val scaledWidth = bitmap.width * scale
            val scaledHeight = bitmap.height * scale
            val translateX = (outputWidth - scaledWidth) / 2f + layer.offsetX
            val translateY = (outputHeight - scaledHeight) / 2f + layer.offsetY
            matrix.postTranslate(translateX, translateY)
            paint.alpha = 255
            canvas.drawBitmap(bitmap, matrix, paint)
        }
    }

    private fun drawParticles(canvas: Canvas, particleSnapshots: Map<ParticleType, List<Particle>>) {
        for ((type, particles) in particleSnapshots) {
            overlayPaint.color = colorForParticleType(type)
            for (particle in particles) {
                overlayPaint.alpha = (particle.alpha * 255).toInt().coerceIn(0, 255)
                canvas.drawCircle(particle.x, particle.y, particle.size, overlayPaint)
            }
        }
    }

    private fun colorForParticleType(type: ParticleType): Int {
        return when (type) {
            ParticleType.SNOW -> Color.WHITE
            ParticleType.FIRE -> Color.rgb(255, 120, 30)
            ParticleType.SPARK -> Color.rgb(255, 210, 120)
            ParticleType.RAIN -> Color.rgb(150, 180, 220)
            ParticleType.SMOKE -> Color.rgb(90, 90, 95)
        }
    }

    private fun drawVignette(canvas: Canvas) {
        val centerX = outputWidth / 2f
        val centerY = outputHeight / 2f
        val radius = maxOf(outputWidth, outputHeight) * 0.75f
        val shader = android.graphics.RadialGradient(
            centerX, centerY, radius,
            intArrayOf(Color.TRANSPARENT, Color.argb(160, 0, 0, 0)),
            floatArrayOf(0.6f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        overlayPaint.shader = shader
        canvas.drawRect(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat(), overlayPaint)
        overlayPaint.shader = null
    }

    private fun drawBassPulseOverlay(canvas: Canvas, amplitude: Float) {
        overlayPaint.color = Color.WHITE
        overlayPaint.alpha = ((amplitude - 0.6f) * 100f).toInt().coerceIn(0, 60)
        canvas.drawRect(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat(), overlayPaint)
        overlayPaint.alpha = 255
    }

    private fun drawRuneOverlay(canvas: Canvas) {
        overlayPaint.color = Color.argb(60, 180, 200, 220)
        val markSize = outputWidth * 0.05f
        val positions = listOf(
            RectF(outputWidth * 0.1f, outputHeight * 0.15f, outputWidth * 0.1f + markSize, outputHeight * 0.15f + markSize),
            RectF(outputWidth * 0.85f, outputHeight * 0.75f, outputWidth * 0.85f + markSize, outputHeight * 0.75f + markSize)
        )
        for (rect in positions) {
            canvas.drawLine(rect.left, rect.top, rect.right, rect.bottom, overlayPaint)
            canvas.drawLine(rect.left, rect.bottom, rect.right, rect.top, overlayPaint)
        }
    }

    private fun drawFilmGrain(canvas: Canvas) {
        overlayPaint.color = Color.BLACK
        overlayPaint.alpha = 12
        canvas.drawRect(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat(), overlayPaint)
        overlayPaint.alpha = 255
    }

    fun recycle() {
        for (bitmap in bitmapPool.values) {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        bitmapPool.clear()
    }
}

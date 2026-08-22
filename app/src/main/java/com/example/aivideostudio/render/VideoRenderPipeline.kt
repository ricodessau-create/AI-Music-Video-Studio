package com.example.aivideostudio.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.aivideostudio.audio.AudioFeatures
import com.example.aivideostudio.data.RenderSettings
import com.example.aivideostudio.storyboard.GeneratedScene
import com.example.aivideostudio.storyboard.VisualParameters
import java.io.File

sealed class RenderProgress {
    data class InProgress(val currentFrame: Int, val totalFrames: Int, val currentSceneLabel: String) : RenderProgress()
    data class Failed(val message: String) : RenderProgress()
    data class Completed(val outputFilePath: String) : RenderProgress()
}

class VideoRenderPipeline(
    private val outputDirectory: File,
    private val renderSettings: RenderSettings
) {

    fun render(
        projectId: String,
        songFilePath: String,
        scenes: List<GeneratedScene>,
        visualParameters: VisualParameters,
        audioFeatures: AudioFeatures,
        backgroundImagePaths: Map<String, String>,
        onProgress: (RenderProgress) -> Unit
    ) {
        try {
            val tempVideoFile = File(outputDirectory, "temp_video_$projectId.mp4")
            if (tempVideoFile.exists()) {
                tempVideoFile.delete()
            }

            val encoder = VideoEncoder(
                outputFile = tempVideoFile,
                width = renderSettings.resolutionWidth,
                height = renderSettings.resolutionHeight,
                frameRate = renderSettings.frameRate,
                bitrate = renderSettings.videoBitrate
            )
            encoder.start()

            val frameRenderer = SceneFrameRenderer(renderSettings.resolutionWidth, renderSettings.resolutionHeight)
            loadBitmaps(frameRenderer, backgroundImagePaths)

            val totalDuration = audioFeatures.durationSeconds
            val totalFrames = (totalDuration * renderSettings.frameRate).toInt().coerceAtLeast(1)

            val particleSystems = buildParticleSystems(visualParameters)
            var currentSceneIndex = 0

            for (frameNumber in 0 until totalFrames) {
                val timeSeconds = frameNumber.toDouble() / renderSettings.frameRate.toDouble()

                while (currentSceneIndex < scenes.size - 1 && timeSeconds >= scenes[currentSceneIndex].endTimeSeconds) {
                    currentSceneIndex++
                }
                val scene = scenes[currentSceneIndex.coerceIn(0, scenes.size - 1)]

                val sceneDuration = (scene.endTimeSeconds - scene.startTimeSeconds).coerceAtLeast(0.01)
                val sceneProgress = ((timeSeconds - scene.startTimeSeconds) / sceneDuration).toFloat().coerceIn(0f, 1f)

                val cameraController = CameraMotionController(scene.cameraMovement)
                val cameraState = cameraController.computeState(sceneProgress, scene.intensity)

                val parallaxLayers = listOf(
                    ParallaxLayer(bitmapKey = "background_${scene.id}", depthFactor = 0.3f),
                    ParallaxLayer(bitmapKey = "background_${scene.id}", depthFactor = 0.6f),
                    ParallaxLayer(bitmapKey = "background_${scene.id}", depthFactor = 1.0f)
                )
                val parallaxEngine = ParallaxEngine(parallaxLayers)

                val particleSnapshots = HashMap<ParticleType, List<Particle>>()
                for ((type, system) in particleSystems) {
                    particleSnapshots[type] = system.update(1f / renderSettings.frameRate.toFloat(), scene.intensity)
                }

                val amplitude = sampleAmplitude(audioFeatures, timeSeconds)

                val frameBitmap = frameRenderer.renderFrame(
                    scene = scene,
                    visualParameters = visualParameters,
                    parallaxEngine = parallaxEngine,
                    cameraState = cameraState,
                    particleSnapshots = particleSnapshots,
                    audioAmplitude = amplitude,
                    backgroundKey = backgroundImagePaths[scene.id]?.let { "background_${scene.id}" }
                )

                val presentationTimeUs = (timeSeconds * 1_000_000).toLong()
                encoder.encodeFrame(frameBitmap, presentationTimeUs)
                frameBitmap.recycle()

                onProgress(RenderProgress.InProgress(frameNumber, totalFrames, scene.label))
            }

            encoder.finish()
            frameRenderer.recycle()

            val finalOutputFile = File(outputDirectory, "ai_music_video_$projectId.mp4")
            if (finalOutputFile.exists()) {
                finalOutputFile.delete()
            }

            val muxer = AudioVideoMuxer(
                videoOnlyFile = tempVideoFile,
                originalAudioFilePath = songFilePath,
                outputFile = finalOutputFile
            )
            muxer.mux()
            tempVideoFile.delete()

            onProgress(RenderProgress.Completed(finalOutputFile.absolutePath))
        } catch (outOfMemory: OutOfMemoryError) {
            onProgress(RenderProgress.Failed("Nicht genügend Speicherplatz."))
        } catch (exception: Exception) {
            onProgress(RenderProgress.Failed(exception.message ?: "Rendern wurde abgebrochen."))
        }
    }

    private fun loadBitmaps(frameRenderer: SceneFrameRenderer, backgroundImagePaths: Map<String, String>) {
        for ((sceneId, path) in backgroundImagePaths) {
            val bitmap = decodeBitmapSafely(path)
            if (bitmap != null) {
                frameRenderer.registerBitmap("background_$sceneId", bitmap)
            }
        }
    }

    private fun decodeBitmapSafely(path: String): Bitmap? {
        return try {
            val options = BitmapFactory.Options()
            options.inSampleSize = 1
            BitmapFactory.decodeFile(path, options)
        } catch (exception: Exception) {
            null
        }
    }

    private fun buildParticleSystems(visualParameters: VisualParameters): Map<ParticleType, ParticleSystem> {
        val systems = HashMap<ParticleType, ParticleSystem>()
        if (visualParameters.hasSnowParticles) {
            systems[ParticleType.SNOW] = ParticleSystem(ParticleType.SNOW, renderSettings.resolutionWidth, renderSettings.resolutionHeight, 150)
        }
        if (visualParameters.hasFireParticles) {
            systems[ParticleType.FIRE] = ParticleSystem(ParticleType.FIRE, renderSettings.resolutionWidth, renderSettings.resolutionHeight, 80)
        }
        if (visualParameters.hasRain) {
            systems[ParticleType.RAIN] = ParticleSystem(ParticleType.RAIN, renderSettings.resolutionWidth, renderSettings.resolutionHeight, 200)
        }
        if (visualParameters.hasSmoke) {
            systems[ParticleType.SMOKE] = ParticleSystem(ParticleType.SMOKE, renderSettings.resolutionWidth, renderSettings.resolutionHeight, 40)
        }
        return systems
    }

    private fun sampleAmplitude(audioFeatures: AudioFeatures, timeSeconds: Double): Float {
        if (audioFeatures.rmsEnergyCurve.isEmpty()) return 0f
        val frameCount = audioFeatures.rmsEnergyCurve.size
        val duration = audioFeatures.durationSeconds
        if (duration <= 0.0) return 0f
        val index = ((timeSeconds / duration) * frameCount).toInt().coerceIn(0, frameCount - 1)
        val maxEnergy = audioFeatures.rmsEnergyCurve.max().coerceAtLeast(0.0001f)
        return (audioFeatures.rmsEnergyCurve[index] / maxEnergy).coerceIn(0f, 1f)
    }
}

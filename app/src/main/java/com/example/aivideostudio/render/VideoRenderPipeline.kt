package com.example.aivideostudio.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.aivideostudio.audio.AudioFeatures
import com.example.aivideostudio.data.GeneratedMediaType
import com.example.aivideostudio.data.RenderSettings
import com.example.aivideostudio.storyboard.GeneratedScene
import com.example.aivideostudio.storyboard.VisualParameters
import java.io.File

sealed class RenderProgress {
    data class InProgress(
        val currentFrame: Int,
        val totalFrames: Int,
        val currentSceneLabel: String
    ) : RenderProgress()

    data class Failed(
        val message: String,
        val throwable: Throwable? = null
    ) : RenderProgress()

    data class Completed(
        val outputFilePath: String
    ) : RenderProgress()
}

data class SceneMediaInfo(
    val filePath: String,
    val mediaType: GeneratedMediaType
)

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
        aiGeneratedMedia: Map<String, SceneMediaInfo> = emptyMap(),
        characterReferenceImagePath: String? = null,
        onProgress: (RenderProgress) -> Unit
    ) {
        var encoder: VideoEncoder? = null
        var frameRenderer: SceneFrameRenderer? = null
        val aiFrameSources =
            HashMap<String, AiSceneFrameSource>()

        try {
            require(scenes.isNotEmpty()) {
                "Keine Szenen für das Rendern vorhanden."
            }

            if (!outputDirectory.exists()) {
                outputDirectory.mkdirs()
            }

            val tempVideoFile =
                File(
                    outputDirectory,
                    "temp_video_$projectId.mp4"
                )

            if (tempVideoFile.exists()) {
                tempVideoFile.delete()
            }

            val createdEncoder =
                VideoEncoder(
                    outputFile = tempVideoFile,
                    width = renderSettings.resolutionWidth,
                    height = renderSettings.resolutionHeight,
                    frameRate = renderSettings.frameRate,
                    bitrate = renderSettings.videoBitrate
                )

            encoder = createdEncoder

            createdEncoder.start()

            val createdFrameRenderer =
                SceneFrameRenderer(
                    renderSettings.resolutionWidth,
                    renderSettings.resolutionHeight
                )

            frameRenderer =
                createdFrameRenderer

            val effectiveBackgroundPaths =
                HashMap(backgroundImagePaths)

            if (!characterReferenceImagePath.isNullOrBlank()) {
                for (scene in scenes) {
                    if (
                        !effectiveBackgroundPaths.containsKey(
                            scene.id
                        )
                    ) {
                        effectiveBackgroundPaths[
                            scene.id
                        ] =
                            characterReferenceImagePath
                    }
                }
            }

            loadBitmaps(
                createdFrameRenderer,
                effectiveBackgroundPaths
            )

            for (
                (sceneId, mediaInfo)
                in aiGeneratedMedia
            ) {
                val file =
                    File(mediaInfo.filePath)

                if (
                    !file.exists() ||
                    !file.isFile ||
                    file.length() <= 0L
                ) {
                    continue
                }

                val source =
                    AiSceneFrameSource(
                        filePath =
                            mediaInfo.filePath,
                        mediaType =
                            mediaInfo.mediaType,
                        outputWidth =
                            renderSettings.resolutionWidth,
                        outputHeight =
                            renderSettings.resolutionHeight
                    )

                source.prepare()

                aiFrameSources[sceneId] =
                    source
            }

            val totalDuration =
                audioFeatures.durationSeconds
                    .coerceAtLeast(0.01)

            val totalFrames =
                (
                    totalDuration *
                        renderSettings.frameRate
                    )
                    .toInt()
                    .coerceAtLeast(1)

            val particleSystems =
                buildParticleSystems(
                    visualParameters
                )

            var currentSceneIndex = 0

            for (
                frameNumber in 0 until totalFrames
            ) {
                val timeSeconds =
                    frameNumber.toDouble() /
                        renderSettings.frameRate
                            .toDouble()

                while (
                    currentSceneIndex <
                    scenes.size - 1 &&
                    timeSeconds >=
                    scenes[currentSceneIndex]
                        .endTimeSeconds
                ) {
                    currentSceneIndex++
                }

                val scene =
                    scenes[
                        currentSceneIndex.coerceIn(
                            0,
                            scenes.size - 1
                        )
                    ]

                val sceneDuration =
                    (
                        scene.endTimeSeconds -
                            scene.startTimeSeconds
                        )
                        .coerceAtLeast(0.01)

                val sceneProgress =
                    (
                        (
                            timeSeconds -
                                scene.startTimeSeconds
                            ) /
                            sceneDuration
                        )
                        .toFloat()
                        .coerceIn(
                            0f,
                            1f
                        )

                val cameraController =
                    CameraMotionController(
                        scene.cameraMovement
                    )

                val cameraState =
                    cameraController.computeState(
                        sceneProgress,
                        scene.intensity
                    )

                val aiSource =
                    aiFrameSources[scene.id]

                val aiFrameOverride =
                    aiSource
                        ?.getFrameBitmapAtSceneProgress(
                            sceneProgress
                        )

                val parallaxLayers =
                    listOf(
                        ParallaxLayer(
                            bitmapKey =
                                "background_${scene.id}",
                            depthFactor = 0.3f
                        ),
                        ParallaxLayer(
                            bitmapKey =
                                "background_${scene.id}",
                            depthFactor = 0.6f
                        ),
                        ParallaxLayer(
                            bitmapKey =
                                "background_${scene.id}",
                            depthFactor = 1.0f
                        )
                    )

                val parallaxEngine =
                    ParallaxEngine(
                        parallaxLayers
                    )

                val particleSnapshots =
                    HashMap<
                        ParticleType,
                        List<Particle>
                    >()

                for (
                    (type, system)
                    in particleSystems
                ) {
                    particleSnapshots[type] =
                        system.update(
                            1f /
                                renderSettings
                                    .frameRate
                                    .toFloat(),
                            scene.intensity
                        )
                }

                val amplitude =
                    sampleAmplitude(
                        audioFeatures,
                        timeSeconds
                    )

                val frameBitmap =
                    createdFrameRenderer.renderFrame(
                        scene = scene,
                        visualParameters =
                            visualParameters,
                        parallaxEngine =
                            parallaxEngine,
                        cameraState =
                            cameraState,
                        particleSnapshots =
                            particleSnapshots,
                        audioAmplitude =
                            amplitude,
                        backgroundKey =
                            effectiveBackgroundPaths[
                                scene.id
                            ]?.let {
                                "background_${scene.id}"
                            },
                        aiFrameOverride =
                            aiFrameOverride
                    )

                val presentationTimeUs =
                    (
                        timeSeconds *
                            1_000_000
                        )
                        .toLong()

                createdEncoder.encodeFrame(
                    frameBitmap,
                    presentationTimeUs
                )

                if (!frameBitmap.isRecycled) {
                    frameBitmap.recycle()
                }

                if (
                    aiFrameOverride != null &&
                    !aiFrameOverride.isRecycled
                ) {
                    aiFrameOverride.recycle()
                }

                onProgress(
                    RenderProgress.InProgress(
                        currentFrame =
                            frameNumber + 1,
                        totalFrames =
                            totalFrames,
                        currentSceneLabel =
                            scene.label
                    )
                )
            }

            createdEncoder.finish()

            val finalOutputFile =
                File(
                    outputDirectory,
                    "ai_music_video_$projectId.mp4"
                )

            if (finalOutputFile.exists()) {
                finalOutputFile.delete()
            }

            val muxer =
                AudioVideoMuxer(
                    videoOnlyFile =
                        tempVideoFile,
                    originalAudioFilePath =
                        songFilePath,
                    outputFile =
                        finalOutputFile
                )

            muxer.mux()

            if (tempVideoFile.exists()) {
                tempVideoFile.delete()
            }

            if (
                !finalOutputFile.exists() ||
                finalOutputFile.length() < 1024L
            ) {
                throw IllegalStateException(
                    "Die fertige Videodatei wurde nicht korrekt erzeugt."
                )
            }

            onProgress(
                RenderProgress.Completed(
                    finalOutputFile.absolutePath
                )
            )
        } catch (
            outOfMemory: OutOfMemoryError
        ) {
            onProgress(
                RenderProgress.Failed(
                    "Nicht genügend Arbeitsspeicher zum Rendern des Videos."
                )
            )
        } catch (
            exception: Exception
        ) {
            onProgress(
                RenderProgress.Failed(
                    exception.message
                        ?: "Rendern wurde abgebrochen.",
                    exception
                )
            )
        } finally {
            for (
                source in aiFrameSources.values
            ) {
                source.release()
            }

            frameRenderer?.recycle()
        }
    }

    private fun loadBitmaps(
        frameRenderer: SceneFrameRenderer,
        backgroundImagePaths: Map<String, String>
    ) {
        for (
            (sceneId, path)
            in backgroundImagePaths
        ) {
            val bitmap =
                decodeBitmapSafely(path)

            if (bitmap != null) {
                frameRenderer.registerBitmap(
                    "background_$sceneId",
                    bitmap
                )
            }
        }
    }

    private fun decodeBitmapSafely(
        path: String
    ): Bitmap? {
        return try {
            val file =
                File(path)

            if (
                !file.exists() ||
                !file.isFile ||
                file.length() <= 0L
            ) {
                return null
            }

            val options =
                BitmapFactory.Options().apply {
                    inPreferredConfig =
                        Bitmap.Config.ARGB_8888
                    inScaled = false
                }

            BitmapFactory.decodeFile(
                file.absolutePath,
                options
            )
        } catch (
            exception: Exception
        ) {
            null
        }
    }

    private fun buildParticleSystems(
        visualParameters: VisualParameters
    ): Map<ParticleType, ParticleSystem> {
        val systems =
            HashMap<
                ParticleType,
                ParticleSystem
            >()

        if (
            visualParameters.hasSnowParticles
        ) {
            systems[ParticleType.SNOW] =
                ParticleSystem(
                    ParticleType.SNOW,
                    renderSettings.resolutionWidth,
                    renderSettings.resolutionHeight,
                    150
                )
        }

        if (
            visualParameters.hasFireParticles
        ) {
            systems[ParticleType.FIRE] =
                ParticleSystem(
                    ParticleType.FIRE,
                    renderSettings.resolutionWidth,
                    renderSettings.resolutionHeight,
                    80
                )
        }

        if (
            visualParameters.hasRain
        ) {
            systems[ParticleType.RAIN] =
                ParticleSystem(
                    ParticleType.RAIN,
                    renderSettings.resolutionWidth,
                    renderSettings.resolutionHeight,
                    200
                )
        }

        if (
            visualParameters.hasSmoke
        ) {
            systems[ParticleType.SMOKE] =
                ParticleSystem(
                    ParticleType.SMOKE,
                    renderSettings.resolutionWidth,
                    renderSettings.resolutionHeight,
                    40
                )
        }

        return systems
    }

    private fun sampleAmplitude(
        audioFeatures: AudioFeatures,
        timeSeconds: Double
    ): Float {
        if (
            audioFeatures.rmsEnergyCurve.isEmpty()
        ) {
            return 0f
        }

        val frameCount =
            audioFeatures.rmsEnergyCurve.size

        val duration =
            audioFeatures.durationSeconds

        if (
            duration <= 0.0
        ) {
            return 0f
        }

        val index =
            (
                (
                    timeSeconds /
                        duration
                    ) *
                        frameCount
                )
                .toInt()
                .coerceIn(
                    0,
                    frameCount - 1
                )

        val maxEnergy =
            audioFeatures.rmsEnergyCurve
                .max()
                .coerceAtLeast(
                    0.0001f
                )

        return (
            audioFeatures
                .rmsEnergyCurve[index] /
                maxEnergy
            )
            .coerceIn(
                0f,
                1f
            )
    }
}
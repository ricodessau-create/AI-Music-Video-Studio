package com.example.aivideostudio.render

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.aivideostudio.audio.BeatDetector
import com.example.aivideostudio.audio.PcmAudioDecoder
import com.example.aivideostudio.data.AppDatabase
import com.example.aivideostudio.data.ProjectRepository
import com.example.aivideostudio.data.RenderPresets
import com.example.aivideostudio.storyboard.GeneratedScene
import com.example.aivideostudio.storyboard.VisualPromptAnalyzer
import java.io.File

class RenderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val projectId = inputData.getString(KEY_PROJECT_ID) ?: return Result.failure(
            workDataOf(KEY_ERROR_MESSAGE to "Ungültige Datei.")
        )

        val database = AppDatabase.getInstance(applicationContext)
        val repository = ProjectRepository(database)

        val project = repository.getProject(projectId) ?: return Result.failure(
            workDataOf(KEY_ERROR_MESSAGE to "Ungültige Datei.")
        )
        val sceneEntities = repository.getScenes(projectId)
        if (sceneEntities.isEmpty()) {
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Rendern wurde abgebrochen."))
        }

        return try {
            val decoded = PcmAudioDecoder(project.songFilePath).decode()
            val beatDetector = BeatDetector(decoded.samples, decoded.sampleRate, decoded.channelCount)
            val audioFeatures = beatDetector.analyze()

            val visualParameters = VisualPromptAnalyzer().analyze(project.visualPrompt)

            val generatedScenes = sceneEntities.map { entity ->
                GeneratedScene(
                    id = entity.id,
                    orderIndex = entity.orderIndex,
                    label = entity.label,
                    startTimeSeconds = entity.startTimeSeconds,
                    endTimeSeconds = entity.endTimeSeconds,
                    prompt = entity.prompt,
                    cameraMovement = entity.cameraMovement,
                    transitionType = entity.transitionType,
                    effects = entity.effectsJson.split(",").filter { it.isNotBlank() },
                    intensity = entity.intensity
                )
            }

            val backgroundPaths = sceneEntities
                .filter { it.backgroundImagePath != null }
                .associate { it.id to it.backgroundImagePath!! }

            val renderSettings = RenderPresets.forResolutionLabel(
                label = resolutionLabelFromHeight(project.resolutionHeight),
                aspectRatio = project.aspectRatio
            )

            val outputDirectory = File(applicationContext.getExternalFilesDir(null), "rendered_videos")
            if (!outputDirectory.exists()) {
                outputDirectory.mkdirs()
            }

            var lastProgressPercent = -1
            var failureMessage: String? = null
            var outputPath: String? = null

            val pipeline = VideoRenderPipeline(outputDirectory, renderSettings)
            pipeline.render(
                projectId = projectId,
                songFilePath = project.songFilePath,
                scenes = generatedScenes,
                visualParameters = visualParameters,
                audioFeatures = audioFeatures,
                backgroundImagePaths = backgroundPaths
            ) { progress ->
                when (progress) {
                    is RenderProgress.InProgress -> {
                        val percent = ((progress.currentFrame.toFloat() / progress.totalFrames.toFloat()) * 100).toInt()
                        if (percent != lastProgressPercent) {
                            lastProgressPercent = percent
                            setProgressAsync(
                                workDataOf(
                                    KEY_PROGRESS_PERCENT to percent,
                                    KEY_CURRENT_SCENE to progress.currentSceneLabel
                                )
                            )
                        }
                    }
                    is RenderProgress.Failed -> {
                        failureMessage = progress.message
                    }
                    is RenderProgress.Completed -> {
                        outputPath = progress.outputFilePath
                    }
                }
            }

            if (failureMessage != null) {
                Result.failure(workDataOf(KEY_ERROR_MESSAGE to failureMessage))
            } else if (outputPath != null) {
                Result.success(workDataOf(KEY_OUTPUT_PATH to outputPath))
            } else {
                Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Rendern wurde abgebrochen."))
            }
        } catch (exception: Exception) {
            Result.failure(workDataOf(KEY_ERROR_MESSAGE to (exception.message ?: "Audio konnte nicht analysiert werden.")))
        }
    }

    private fun resolutionLabelFromHeight(height: Int): String {
        return when (height) {
            720 -> "720p"
            2160 -> "4K"
            else -> "1080p"
        }
    }

    companion object {
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_CURRENT_SCENE = "current_scene"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_ERROR_MESSAGE = "error_message"
    }
}

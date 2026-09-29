package com.example.aivideostudio.render

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.aivideostudio.audio.BeatDetector
import com.example.aivideostudio.audio.PcmAudioDecoder
import com.example.aivideostudio.comfyui.WorkflowRepository
import com.example.aivideostudio.data.AppDatabase
import com.example.aivideostudio.data.GeneratedMediaType
import com.example.aivideostudio.data.GenerationStatus
import com.example.aivideostudio.data.ProjectRepository
import com.example.aivideostudio.data.RenderMode
import com.example.aivideostudio.data.RenderPresets
import com.example.aivideostudio.storyboard.GeneratedScene
import com.example.aivideostudio.storyboard.VisualPromptAnalyzer
import com.example.aivideostudio.util.RenderErrorLogger
import java.io.File

class RenderWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val projectId =
            inputData.getString(KEY_PROJECT_ID)
                ?: return Result.failure(
                    workDataOf(
                        KEY_ERROR_MESSAGE to "Ungültige Projekt-ID."
                    )
                )

        try {
            setForeground(
                RenderNotifications.buildForegroundInfo(
                    applicationContext,
                    "Rendern wird vorbereitet",
                    0
                )
            )
        } catch (exception: Exception) {
        }

        val database =
            AppDatabase.getInstance(applicationContext)

        val repository =
            ProjectRepository(database)

        val project =
            repository.getProject(projectId)
                ?: return Result.failure(
                    workDataOf(
                        KEY_ERROR_MESSAGE to "Projekt wurde nicht gefunden."
                    )
                )

        var sceneEntities =
            repository.getScenes(projectId)

        if (sceneEntities.isEmpty()) {
            return Result.failure(
                workDataOf(
                    KEY_ERROR_MESSAGE to "Keine Szenen für dieses Projekt vorhanden."
                )
            )
        }

        val renderMode =
            try {
                RenderMode.valueOf(project.renderMode)
            } catch (exception: Exception) {
                RenderMode.OFFLINE
            }

        return try {
            val decoded =
                PcmAudioDecoder(project.songFilePath)
                    .decode()

            val beatDetector =
                BeatDetector(
                    decoded.samples,
                    decoded.sampleRate,
                    decoded.channelCount
                )

            val audioFeatures =
                beatDetector.analyze()

            val visualParameters =
                VisualPromptAnalyzer()
                    .analyze(project.visualPrompt)

            val aiGeneratedMedia =
                HashMap<String, SceneMediaInfo>()

            if (renderMode == RenderMode.COMFYUI) {
                val comfyUiBaseUrl =
                    project.comfyUiBaseUrl

                val workflowId =
                    project.selectedWorkflowId

                if (
                    comfyUiBaseUrl.isNullOrBlank() ||
                    workflowId.isNullOrBlank()
                ) {
                    return Result.failure(
                        workDataOf(
                            KEY_ERROR_MESSAGE to
                                "ComfyUI-Server und Workflow müssen eingerichtet sein."
                        )
                    )
                }

                val storedWorkflow =
                    WorkflowRepository()
                        .listWorkflows(applicationContext)
                        .firstOrNull {
                            it.id == workflowId
                        }
                        ?: return Result.failure(
                            workDataOf(
                                KEY_ERROR_MESSAGE to
                                    "KI-Workflow wurde nicht gefunden."
                            )
                        )

                val orchestrator =
                    SceneGenerationOrchestrator(
                        applicationContext,
                        comfyUiBaseUrl
                    )

                if (!orchestrator.checkConnection()) {
                    return Result.failure(
                        workDataOf(
                            KEY_ERROR_MESSAGE to
                                "ComfyUI-Server nicht erreichbar."
                        )
                    )
                }

                val sortedScenes =
                    sceneEntities.sortedBy {
                        it.orderIndex
                    }

                val failedSceneLabels =
                    ArrayList<String>()

                for (
                    (index, scene)
                    in sortedScenes.withIndex()
                ) {
                    val progressPercent =
                        (index * 100) /
                            sortedScenes.size.coerceAtLeast(1)

                    val statusLabel =
                        "Szene ${index + 1} von ${sortedScenes.size}: ${scene.label}"

                    setProgressAsync(
                        workDataOf(
                            KEY_PROGRESS_PERCENT to progressPercent,
                            KEY_CURRENT_SCENE to statusLabel
                        )
                    )

                    updateNotification(
                        statusLabel,
                        progressPercent
                    )

                    val alreadyGenerated =
                        scene.generationStatus ==
                            GenerationStatus.GENERATED.name &&
                            scene.generatedMediaPath != null &&
                            File(scene.generatedMediaPath).exists()

                    if (alreadyGenerated) {
                        aiGeneratedMedia[scene.id] =
                            SceneMediaInfo(
                                filePath =
                                    scene.generatedMediaPath!!,
                                mediaType =
                                    GeneratedMediaType.valueOf(
                                        scene.generatedMediaType
                                    )
                            )

                        continue
                    }

                    repository.markSceneGenerating(
                        scene
                    )

                    val referenceImagePath =
                        scene.referenceImagePath
                            ?: project.characterReferenceImagePath

                    val result =
                        orchestrator.generateScene(
                            scene = scene,
                            workflow = storedWorkflow,
                            negativePrompt =
                                project.negativePrompt,
                            width =
                                project.resolutionWidth,
                            height =
                                project.resolutionHeight,
                            characterReferenceImagePath =
                                referenceImagePath
                        ) { statusText ->
                            val combinedLabel =
                                "Szene ${index + 1} von ${sortedScenes.size}: $statusText"

                            setProgressAsync(
                                workDataOf(
                                    KEY_PROGRESS_PERCENT to
                                        progressPercent,
                                    KEY_CURRENT_SCENE to
                                        combinedLabel
                                )
                            )

                            updateNotification(
                                combinedLabel,
                                progressPercent
                            )
                        }

                    when (result) {
                        is SceneGenerationResult.Success -> {
                            repository.markSceneGenerated(
                                scene,
                                result.filePath,
                                result.mediaType
                            )

                            aiGeneratedMedia[scene.id] =
                                SceneMediaInfo(
                                    result.filePath,
                                    result.mediaType
                                )
                        }

                        is SceneGenerationResult.Failure -> {
                            repository.markSceneFailed(
                                scene,
                                result.message
                            )

                            failedSceneLabels.add(
                                "${scene.label} (${result.message})"
                            )
                        }
                    }
                }

                if (failedSceneLabels.isNotEmpty()) {
                    return Result.failure(
                        workDataOf(
                            KEY_ERROR_MESSAGE to
                                "KI-Generierung für ${failedSceneLabels.size} von ${sortedScenes.size} Szenen fehlgeschlagen: ${failedSceneLabels.joinToString("; ")}"
                        )
                    )
                }

                sceneEntities =
                    repository.getScenes(projectId)
            }

            val generatedScenes =
                sceneEntities.map { entity ->
                    GeneratedScene(
                        id = entity.id,
                        orderIndex = entity.orderIndex,
                        label = entity.label,
                        startTimeSeconds =
                            entity.startTimeSeconds,
                        endTimeSeconds =
                            entity.endTimeSeconds,
                        prompt = entity.prompt,
                        cameraMovement =
                            entity.cameraMovement,
                        transitionType =
                            entity.transitionType,
                        effects =
                            entity.effectsJson
                                .split(",")
                                .filter {
                                    it.isNotBlank()
                                },
                        intensity =
                            entity.intensity,
                        seed =
                            entity.seed
                    )
                }

            val backgroundPaths =
                sceneEntities
                    .filter {
                        it.backgroundImagePath != null
                    }
                    .associate {
                        it.id to it.backgroundImagePath!!
                    }

            val renderSettings =
                RenderPresets.forResolutionLabel(
                    label =
                        resolutionLabelFromHeight(
                            project.resolutionHeight
                        ),
                    aspectRatio =
                        project.aspectRatio
                )

            val outputDirectory =
                File(
                    applicationContext.getExternalFilesDir(null),
                    "rendered_videos"
                )

            if (!outputDirectory.exists()) {
                outputDirectory.mkdirs()
            }

            var lastProgressPercent = -1
            var failureMessage: String? = null
            var outputPath: String? = null

            val pipeline =
                VideoRenderPipeline(
                    outputDirectory,
                    renderSettings
                )

            pipeline.render(
                projectId = projectId,
                songFilePath = project.songFilePath,
                scenes = generatedScenes,
                visualParameters = visualParameters,
                audioFeatures = audioFeatures,
                backgroundImagePaths = backgroundPaths,
                aiGeneratedMedia = aiGeneratedMedia,
                characterReferenceImagePath =
                    project.characterReferenceImagePath
            ) { progress ->
                when (progress) {
                    is RenderProgress.InProgress -> {
                        val percent =
                            (
                                progress.currentFrame.toFloat() /
                                    progress.totalFrames
                            )
                                .coerceIn(0f, 1f)
                                .times(100f)
                                .toInt()

                        if (
                            percent != lastProgressPercent
                        ) {
                            lastProgressPercent =
                                percent

                            val label =
                                "Rendering: ${progress.currentSceneLabel}"

                            setProgressAsync(
                                workDataOf(
                                    KEY_PROGRESS_PERCENT to
                                        percent,
                                    KEY_CURRENT_SCENE to
                                        label
                                )
                            )

                            updateNotification(
                                label,
                                percent
                            )
                        }
                    }

                    is RenderProgress.Failed -> {
                        failureMessage =
                            progress.message.ifBlank {
                                "Rendern wurde abgebrochen."
                            }

                        progress.throwable?.let {
                            RenderErrorLogger.logRenderFailure(
                                applicationContext,
                                it,
                                "VideoRenderPipeline"
                            )
                        }
                    }

                    is RenderProgress.Completed -> {
                        outputPath =
                            progress.outputFilePath
                    }
                }
            }

            when {
                failureMessage != null ->
                    Result.failure(
                        workDataOf(
                            KEY_ERROR_MESSAGE to
                                failureMessage
                        )
                    )

                outputPath != null ->
                    Result.success(
                        workDataOf(
                            KEY_OUTPUT_PATH to
                                outputPath
                        )
                    )

                else ->
                    Result.failure(
                        workDataOf(
                            KEY_ERROR_MESSAGE to
                                "Rendern wurde ohne Ausgabedatei beendet."
                        )
                    )
            }
        } catch (throwable: Throwable) {
            RenderErrorLogger.logRenderFailure(
                applicationContext,
                throwable,
                "RenderWorker.doWork"
            )

            val message =
                throwable.message?.ifBlank {
                    null
                } ?: "Rendern ist fehlgeschlagen."

            Result.failure(
                workDataOf(
                    KEY_ERROR_MESSAGE to message
                )
            )
        }
    }

    private fun updateNotification(
        contentText: String,
        progressPercent: Int
    ) {
        try {
            val notification =
                RenderNotifications.buildNotification(
                    applicationContext,
                    contentText,
                    progressPercent
                )

            NotificationManagerCompat
                .from(applicationContext)
                .notify(
                    RenderNotifications.NOTIFICATION_ID,
                    notification
                )
        } catch (exception: SecurityException) {
        }
    }

    private fun resolutionLabelFromHeight(
        height: Int
    ): String {
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

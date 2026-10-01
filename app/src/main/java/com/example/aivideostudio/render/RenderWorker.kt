package com.example.aivideostudio.render

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
import com.example.aivideostudio.localai.LocalI2VEngine
import com.example.aivideostudio.localai.LocalI2VGenerationProgress
import com.example.aivideostudio.localai.LocalI2VGenerationRequest
import com.example.aivideostudio.storyboard.GeneratedScene
import com.example.aivideostudio.storyboard.VisualPromptAnalyzer
import com.example.aivideostudio.util.RenderErrorLogger
import java.io.File

class RenderWorker(
    context: android.content.Context,
    parameters: WorkerParameters
) : CoroutineWorker(
    context,
    parameters
) {

    override suspend fun doWork(): Result {
        val projectId =
            inputData.getString(
                KEY_PROJECT_ID
            )
                ?: return Result.failure(
                    workDataOf(
                        KEY_ERROR_MESSAGE to
                            "Ungültige Projekt-ID."
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
        } catch (_: Exception) {
        }

        val database =
            AppDatabase.getInstance(
                applicationContext
            )

        val repository =
            ProjectRepository(
                database
            )

        val project =
            repository.getProject(
                projectId
            )
                ?: return Result.failure(
                    workDataOf(
                        KEY_ERROR_MESSAGE to
                            "Projekt wurde nicht gefunden."
                    )
                )

        var sceneEntities =
            repository.getScenes(
                projectId
            )

        if (sceneEntities.isEmpty()) {
            return Result.failure(
                workDataOf(
                    KEY_ERROR_MESSAGE to
                        "Keine Szenen für dieses Projekt vorhanden."
                )
            )
        }

        val renderMode =
            try {
                RenderMode.valueOf(
                    project.renderMode
                )
            } catch (_: Exception) {
                RenderMode.OFFLINE
            }

        return try {
            val decoded =
                PcmAudioDecoder(
                    project.songFilePath
                ).decode()

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
                    .analyze(
                        project.visualPrompt
                    )

            val aiGeneratedMedia =
                HashMap<String, SceneMediaInfo>()

            if (
                renderMode ==
                RenderMode.COMFYUI
            ) {
                generateComfyUiScenes(
                    repository,
                    project,
                    sceneEntities,
                    aiGeneratedMedia
                )

                sceneEntities =
                    repository.getScenes(
                        projectId
                    )
            } else {
                generateOfflineScenes(
                    repository,
                    project,
                    sceneEntities,
                    aiGeneratedMedia
                )

                sceneEntities =
                    repository.getScenes(
                        projectId
                    )
            }

            val generatedScenes =
                sceneEntities
                    .map { entity ->
                        GeneratedScene(
                            id = entity.id,
                            orderIndex =
                                entity.orderIndex,
                            label =
                                entity.label,
                            startTimeSeconds =
                                entity.startTimeSeconds,
                            endTimeSeconds =
                                entity.endTimeSeconds,
                            prompt =
                                entity.prompt,
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
                        it.id to
                            it.backgroundImagePath!!
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
                    applicationContext
                        .getExternalFilesDir(null),
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
                projectId =
                    projectId,
                songFilePath =
                    project.songFilePath,
                scenes =
                    generatedScenes,
                visualParameters =
                    visualParameters,
                audioFeatures =
                    audioFeatures,
                backgroundImagePaths =
                    backgroundPaths,
                aiGeneratedMedia =
                    aiGeneratedMedia
            ) { progress ->
                when (progress) {
                    is RenderProgress.InProgress -> {
                        val percent =
                            (
                                progress.currentFrame
                                    .toFloat() /
                                    progress.totalFrames
                            )
                                .coerceIn(
                                    0f,
                                    1f
                                )
                                .times(100f)
                                .toInt()

                        if (
                            percent !=
                            lastProgressPercent
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
                    KEY_ERROR_MESSAGE to
                        message
                )
            )
        }
    }

    private suspend fun generateOfflineScenes(
        repository: ProjectRepository,
        project: com.example.aivideostudio.data.ProjectEntity,
        scenes: List<com.example.aivideostudio.data.SceneEntity>,
        aiGeneratedMedia: MutableMap<String, SceneMediaInfo>
    ) {
        val referenceImage =
            project.characterReferenceImagePath

        if (
            referenceImage.isNullOrBlank() ||
            !File(referenceImage).exists()
        ) {
            throw IllegalStateException(
                "Für die lokale Bild-zu-Video-KI wurde kein Band/Artist-Referenzbild gefunden."
            )
        }

        val engine =
            LocalI2VEngine(
                applicationContext
            )

        val sortedScenes =
            scenes.sortedBy {
                it.orderIndex
            }

        for (
            (index, scene)
            in sortedScenes.withIndex()
        ) {
            val basePercent =
                (
                    index.toFloat() /
                        sortedScenes.size
                ) * 100f

            val label =
                "Lokale KI – Szene ${index + 1} von ${sortedScenes.size}: ${scene.label}"

            setProgressAsync(
                workDataOf(
                    KEY_PROGRESS_PERCENT to
                        basePercent.toInt(),
                    KEY_CURRENT_SCENE to
                        label
                )
            )

            updateNotification(
                label,
                basePercent.toInt()
            )

            val alreadyGenerated =
                scene.generationStatus ==
                    GenerationStatus.GENERATED.name &&
                    scene.generatedMediaPath != null &&
                    File(
                        scene.generatedMediaPath
                    ).exists() &&
                    scene.generatedMediaType ==
                    GeneratedMediaType.VIDEO.name

            if (alreadyGenerated) {
                aiGeneratedMedia[scene.id] =
                    SceneMediaInfo(
                        scene.generatedMediaPath!!,
                        GeneratedMediaType.VIDEO
                    )

                continue
            }

            repository.markSceneGenerating(
                scene
            )

            val sceneDuration =
                (
                    scene.endTimeSeconds -
                        scene.startTimeSeconds
                    )
                    .coerceAtLeast(0.7)

            val sceneFrames =
                (
                    sceneDuration *
                        24.0
                    )
                    .toInt()
                    .coerceAtLeast(17)

            val outputDirectory =
                File(
                    applicationContext
                        .getExternalFilesDir(null),
                    "local_i2v_generated"
                )

            if (!outputDirectory.exists()) {
                outputDirectory.mkdirs()
            }

            val outputFile =
                File(
                    outputDirectory,
                    "${project.id}_${scene.id}.mp4"
                )

            val prompt =
                scene.prompt
                    .ifBlank {
                        project.visualPrompt
                    }

            try {
                val generatedFile =
                    engine.generate(
                        LocalI2VGenerationRequest(
                            referenceImagePath =
                                referenceImage,
                            prompt =
                                prompt,
                            negativePrompt =
                                project.negativePrompt,
                            outputFile =
                                outputFile,
                            width =
                                project.resolutionWidth,
                            height =
                                project.resolutionHeight,
                            frameRate =
                                24,
                            frames =
                                17,
                            diffusionSteps =
                                2,
                            seed =
                                scene.seed
                        )
                    ) { progress ->
                        val localProgress =
                            progress.progress
                                .coerceIn(
                                    0f,
                                    1f
                                )

                        val totalPercent =
                            (
                                basePercent +
                                    localProgress *
                                    (
                                        100f /
                                            sortedScenes.size
                                    )
                                )
                                .toInt()
                                .coerceIn(
                                    0,
                                    100
                                )

                        val status =
                            "$label – ${progress.stage}"

                        setProgressAsync(
                            workDataOf(
                                KEY_PROGRESS_PERCENT to
                                    totalPercent,
                                KEY_CURRENT_SCENE to
                                    status
                            )
                        )

                        updateNotification(
                            status,
                            totalPercent
                        )
                    }

                repository.markSceneGenerated(
                    scene,
                    generatedFile.absolutePath,
                    GeneratedMediaType.VIDEO
                )

                aiGeneratedMedia[scene.id] =
                    SceneMediaInfo(
                        generatedFile.absolutePath,
                        GeneratedMediaType.VIDEO
                    )
            } catch (exception: Exception) {
                repository.markSceneFailed(
                    scene,
                    exception.message
                        ?: "Lokale Bild-zu-Video-Generierung fehlgeschlagen."
                )

                throw IllegalStateException(
                    "Lokale KI für Szene '${scene.label}' fehlgeschlagen: " +
                        (
                            exception.message
                                ?: "unbekannter Fehler"
                            )
                )
            }
        }
    }

    private suspend fun generateComfyUiScenes(
        repository: ProjectRepository,
        project: com.example.aivideostudio.data.ProjectEntity,
        scenes: List<com.example.aivideostudio.data.SceneEntity>,
        aiGeneratedMedia: MutableMap<String, SceneMediaInfo>
    ) {
        val comfyUiBaseUrl =
            project.comfyUiBaseUrl

        val workflowId =
            project.selectedWorkflowId

        if (
            comfyUiBaseUrl.isNullOrBlank() ||
            workflowId.isNullOrBlank()
        ) {
            throw IllegalStateException(
                "ComfyUI-Server und Workflow müssen eingerichtet sein."
            )
        }

        val storedWorkflow =
            WorkflowRepository()
                .listWorkflows(
                    applicationContext
                )
                .firstOrNull {
                    it.id == workflowId
                }
                ?: throw IllegalStateException(
                    "KI-Workflow wurde nicht gefunden."
                )

        val orchestrator =
            SceneGenerationOrchestrator(
                applicationContext,
                comfyUiBaseUrl
            )

        if (!orchestrator.checkConnection()) {
            throw IllegalStateException(
                "ComfyUI-Server nicht erreichbar."
            )
        }

        val sortedScenes =
            scenes.sortedBy {
                it.orderIndex
            }

        for (
            (index, scene)
            in sortedScenes.withIndex()
        ) {
            val progressPercent =
                (
                    index * 100
                ) /
                    sortedScenes.size
                        .coerceAtLeast(1)

            val statusLabel =
                "ComfyUI – Szene ${index + 1} von ${sortedScenes.size}: ${scene.label}"

            setProgressAsync(
                workDataOf(
                    KEY_PROGRESS_PERCENT to
                        progressPercent,
                    KEY_CURRENT_SCENE to
                        statusLabel
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
                    File(
                        scene.generatedMediaPath
                    ).exists()

            if (alreadyGenerated) {
                aiGeneratedMedia[scene.id] =
                    SceneMediaInfo(
                        scene.generatedMediaPath!!,
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
                        "$statusLabel – $statusText"

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

                    throw IllegalStateException(
                        "ComfyUI-Szene '${scene.label}' fehlgeschlagen: ${result.message}"
                    )
                }
            }
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
        } catch (_: SecurityException) {
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
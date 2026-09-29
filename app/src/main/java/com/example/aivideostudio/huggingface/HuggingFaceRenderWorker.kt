package com.example.aivideostudio.huggingface

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.aivideostudio.audio.BeatDetector
import com.example.aivideostudio.audio.PcmAudioDecoder
import com.example.aivideostudio.data.AppDatabase
import com.example.aivideostudio.data.GeneratedMediaType
import com.example.aivideostudio.data.GenerationStatus
import com.example.aivideostudio.data.ProjectRepository
import com.example.aivideostudio.render.RenderNotifications
import com.example.aivideostudio.util.RenderErrorLogger
import java.io.File
import kotlin.math.roundToInt

class HuggingFaceRenderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    private val targetFps = 8
    private val maxFramesPerRequest = 64

    override suspend fun doWork(): Result {
        val projectId = inputData.getString(KEY_PROJECT_ID) ?: return Result.failure(
            workDataOf(KEY_ERROR_MESSAGE to "Ungültige Datei.")
        )
        val onlySceneId = inputData.getString(KEY_SCENE_ID)

        try {
            setForeground(RenderNotifications.buildForegroundInfo(applicationContext, "Hugging-Face-Generierung wird vorbereitet", 0))
        } catch (exception: Exception) {
            // Läuft ohne Vordergrunddienst weiter, falls das nicht gestartet werden kann
        }

        val database = AppDatabase.getInstance(applicationContext)
        val repository = ProjectRepository(database)

        val project = repository.getProject(projectId) ?: return Result.failure(
            workDataOf(KEY_ERROR_MESSAGE to "Ungültige Datei.")
        )

        val apiToken = project.huggingFaceApiToken
        val modelId = project.huggingFaceModelId
        if (apiToken.isNullOrBlank() || modelId.isNullOrBlank()) {
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Kein Hugging-Face-Token oder Modell in den Projekteinstellungen hinterlegt."))
        }

        return try {
            var scenes = repository.getScenes(projectId)

            if (scenes.isEmpty() && onlySceneId == null) {
                val decoded = PcmAudioDecoder(project.songFilePath).decode()
                val audioFeatures = BeatDetector(decoded.samples, decoded.sampleRate, decoded.channelCount).analyze()
                val slicer = SceneAudioSlicer()
                val slicedScenes = slicer.slice(
                    audioFeatures = audioFeatures,
                    targetSceneDurationSeconds = project.huggingFaceSceneDurationSeconds,
                    basePrompt = project.visualPrompt,
                    globalVisualStyle = project.globalVisualStyle
                )
                repository.saveScenes(projectId, slicedScenes)
                scenes = repository.getScenes(projectId)
            }

            if (scenes.isEmpty()) {
                return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Rendern wurde abgebrochen."))
            }

            val client = HuggingFaceClient(apiToken, modelId)
            val clipsDirectory = File(applicationContext.getExternalFilesDir(null), "huggingface_clips")
            if (!clipsDirectory.exists()) clipsDirectory.mkdirs()

            val scenesToProcess = if (onlySceneId != null) {
                scenes.filter { it.id == onlySceneId }
            } else {
                scenes.sortedBy { it.orderIndex }
            }

            val failedSceneLabels = ArrayList<String>()

            for ((index, scene) in scenesToProcess.withIndex()) {
                val progressPercent = (index * 100) / scenesToProcess.size
                val statusLabel = "Szene ${index + 1} von ${scenesToProcess.size}: ${scene.label}"
                setProgressAsync(workDataOf(KEY_PROGRESS_PERCENT to progressPercent, KEY_CURRENT_SCENE to statusLabel))
                updateNotification(statusLabel, progressPercent)

                repository.markSceneGenerating(scene)

                val sceneDuration = (scene.endTimeSeconds - scene.startTimeSeconds).coerceAtLeast(1.0)
                val idealFrameCount = (sceneDuration * targetFps).roundToInt()
                val requestedFrameCount = idealFrameCount.coerceIn(8, maxFramesPerRequest)

                val referenceNote = if (scene.referenceImagePath != null || project.characterReferenceImagePath != null) {
                    " Hinweis: Ein Referenzbild wurde für diese Szene hinterlegt, wird aber vom aktuellen Hugging-Face-Text-zu-Video-Pfad nicht als Bildvorlage verwendet (nur als Text-Kontext), da keine verifizierte Bild-zu-Video-Schnittstelle verfügbar ist."
                } else {
                    ""
                }

                val requestParameters = HuggingFaceRequestParameters(
                    prompt = scene.prompt + referenceNote,
                    negativePrompt = project.negativePrompt,
                    numFrames = requestedFrameCount,
                    fps = targetFps,
                    width = project.resolutionWidth.coerceAtMost(768),
                    height = project.resolutionHeight.coerceAtMost(432)
                )
                val targetFile = File(clipsDirectory, "${scene.id}.mp4")

                val result = client.generateVideoClipWithRetry(requestParameters, targetFile)
                when (result) {
                    is HuggingFaceVideoResult.Success -> {
                        repository.markSceneGenerated(scene, result.localFilePath, GeneratedMediaType.VIDEO)
                    }
                    is HuggingFaceVideoResult.Failure -> {
                        repository.markSceneFailed(scene, result.message)
                        failedSceneLabels.add("${scene.label} (${result.message})")
                    }
                    is HuggingFaceVideoResult.ModelLoading -> {
                        val message = "Hugging-Face-Modell lädt weiterhin, bitte später erneut versuchen."
                        repository.markSceneFailed(scene, message)
                        failedSceneLabels.add("${scene.label} ($message)")
                    }
                }
            }

            if (onlySceneId != null) {
                return if (failedSceneLabels.isEmpty()) {
                    Result.success()
                } else {
                    Result.failure(workDataOf(KEY_ERROR_MESSAGE to failedSceneLabels.first()))
                }
            }

            if (failedSceneLabels.isNotEmpty()) {
                return Result.failure(
                    workDataOf(
                        KEY_ERROR_MESSAGE to "KI-Generierung für ${failedSceneLabels.size} von ${scenesToProcess.size} Szenen fehlgeschlagen: ${failedSceneLabels.joinToString("; ")}"
                    )
                )
            }

            setProgressAsync(workDataOf(KEY_PROGRESS_PERCENT to 90, KEY_CURRENT_SCENE to "Videoclips werden zusammengefügt"))
            updateNotification("Videoclips werden zusammengefügt", 90)

            val refreshedScenes = repository.getScenes(projectId).sortedBy { it.orderIndex }
            val clipsToStitch = refreshedScenes.map { entity ->
                val path = entity.generatedMediaPath
                    ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Szene \"${entity.label}\" hat kein generiertes Video, Abbruch."))
                ClipToStitch(
                    filePath = path,
                    targetDurationSeconds = (entity.endTimeSeconds - entity.startTimeSeconds).coerceAtLeast(1.0),
                    width = project.resolutionWidth,
                    height = project.resolutionHeight
                )
            }

            val workingDirectory = File(applicationContext.cacheDir, "huggingface_stitch_$projectId")
            if (!workingDirectory.exists()) workingDirectory.mkdirs()

            val outputDirectory = File(applicationContext.getExternalFilesDir(null), "rendered_videos")
            if (!outputDirectory.exists()) outputDirectory.mkdirs()
            val outputFile = File(outputDirectory, "ai_music_video_$projectId.mp4")

            val stitcher = VideoStitcher(workingDirectory)
            val success = stitcher.stitch(clipsToStitch, project.songFilePath, outputFile)

            workingDirectory.deleteRecursively()

            if (success && outputFile.exists()) {
                Result.success(workDataOf(KEY_OUTPUT_PATH to outputFile.absolutePath))
            } else {
                Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Zusammenfügen der Videoclips ist fehlgeschlagen."))
            }
        } catch (exception: Exception) {
            RenderErrorLogger.logRenderFailure(applicationContext, exception, "HuggingFaceRenderWorker")
            Result.failure(workDataOf(KEY_ERROR_MESSAGE to (exception.message ?: "Rendern wurde abgebrochen.")))
        }
    }

    private fun updateNotification(contentText: String, progressPercent: Int) {
        try {
            val notification = RenderNotifications.buildNotification(applicationContext, contentText, progressPercent)
            NotificationManagerCompat.from(applicationContext).notify(RenderNotifications.NOTIFICATION_ID, notification)
        } catch (exception: SecurityException) {
            // Keine Benachrichtigungsberechtigung erteilt – der Job läuft trotzdem weiter
        }
    }

    companion object {
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_SCENE_ID = "scene_id"
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_CURRENT_SCENE = "current_scene"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_ERROR_MESSAGE = "error_message"
    }
}

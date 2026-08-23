package com.example.aivideostudio.render

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.aivideostudio.comfyui.WorkflowRepository
import com.example.aivideostudio.data.AppDatabase
import com.example.aivideostudio.data.ProjectRepository
import com.example.aivideostudio.data.RenderMode

class SingleSceneRegenerationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val projectId = inputData.getString(KEY_PROJECT_ID) ?: return Result.failure()
        val sceneId = inputData.getString(KEY_SCENE_ID) ?: return Result.failure()
        val useNewSeed = inputData.getBoolean(KEY_USE_NEW_SEED, false)

        val database = AppDatabase.getInstance(applicationContext)
        val repository = ProjectRepository(database)

        val project = repository.getProject(projectId) ?: return Result.failure(
            workDataOf(KEY_ERROR_MESSAGE to "Ungültige Datei.")
        )

        val renderMode = try {
            RenderMode.valueOf(project.renderMode)
        } catch (exception: Exception) {
            RenderMode.OFFLINE
        }

        if (renderMode != RenderMode.COMFYUI) {
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Einzelszenen-Neugenerierung ist nur im ComfyUI-Modus möglich."))
        }

        val comfyUiBaseUrl = project.comfyUiBaseUrl
        val workflowId = project.selectedWorkflowId
        if (comfyUiBaseUrl.isNullOrBlank() || workflowId.isNullOrBlank()) {
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "ComfyUI-Server nicht erreichbar."))
        }

        val scenes = repository.getScenes(projectId)
        val scene = scenes.firstOrNull { it.id == sceneId } ?: return Result.failure(
            workDataOf(KEY_ERROR_MESSAGE to "Ungültige Datei.")
        )

        val workflowRepository = WorkflowRepository()
        val storedWorkflow = workflowRepository.listWorkflows(applicationContext).firstOrNull { it.id == workflowId }
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "KI-Generierung fehlgeschlagen."))

        val newSeed = if (useNewSeed) kotlin.random.Random.nextLong().let { if (it < 0) -it else it } else scene.seed
        repository.resetSceneForRegeneration(scene, if (useNewSeed) newSeed else null)
        val refreshedScene = scene.copy(seed = newSeed)
        repository.markSceneGenerating(refreshedScene)

        val orchestrator = SceneGenerationOrchestrator(applicationContext, comfyUiBaseUrl)
        val isAvailable = orchestrator.checkConnection()
        if (!isAvailable) {
            repository.markSceneFailed(refreshedScene, "ComfyUI-Server nicht erreichbar.")
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "ComfyUI-Server nicht erreichbar."))
        }

        val result = orchestrator.generateScene(
            scene = refreshedScene,
            workflow = storedWorkflow,
            negativePrompt = project.negativePrompt,
            width = project.resolutionWidth,
            height = project.resolutionHeight,
            characterReferenceImagePath = project.characterReferenceImagePath
        ) { statusText ->
            setProgressAsync(workDataOf(KEY_STATUS_TEXT to statusText))
        }

        return when (result) {
            is SceneGenerationResult.Success -> {
                repository.markSceneGenerated(refreshedScene, result.filePath, result.mediaType)
                Result.success()
            }
            is SceneGenerationResult.Failure -> {
                repository.markSceneFailed(refreshedScene, result.message)
                Result.failure(workDataOf(KEY_ERROR_MESSAGE to result.message))
            }
        }
    }

    companion object {
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_SCENE_ID = "scene_id"
        const val KEY_USE_NEW_SEED = "use_new_seed"
        const val KEY_STATUS_TEXT = "status_text"
        const val KEY_ERROR_MESSAGE = "error_message"
    }
}

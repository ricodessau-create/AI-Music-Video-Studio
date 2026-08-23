package com.example.aivideostudio.data

import com.example.aivideostudio.audio.AudioFeatures
import com.example.aivideostudio.storyboard.GeneratedScene
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ProjectRepository(private val database: AppDatabase) {

    fun observeProjects(): Flow<List<ProjectEntity>> = database.projectDao().observeProjects()

    fun observeScenes(projectId: String): Flow<List<SceneEntity>> = database.projectDao().observeScenes(projectId)

    suspend fun getProject(projectId: String): ProjectEntity? = database.projectDao().getProject(projectId)

    suspend fun getScenes(projectId: String): List<SceneEntity> = database.projectDao().getScenes(projectId)

    suspend fun createProject(
        name: String,
        songFilePath: String,
        audioFeatures: AudioFeatures,
        visualPrompt: String,
        resolutionWidth: Int,
        resolutionHeight: Int,
        aspectRatio: String,
        renderMode: RenderMode,
        comfyUiBaseUrl: String?,
        globalVisualStyle: String,
        negativePrompt: String,
        characterReferenceImagePath: String?,
        selectedWorkflowId: String?
    ): String {
        val projectId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val entity = ProjectEntity(
            id = projectId,
            name = name,
            songFilePath = songFilePath,
            songDurationSeconds = audioFeatures.durationSeconds,
            bpm = audioFeatures.bpm,
            visualPrompt = visualPrompt,
            resolutionWidth = resolutionWidth,
            resolutionHeight = resolutionHeight,
            aspectRatio = aspectRatio,
            renderMode = renderMode.name,
            comfyUiBaseUrl = comfyUiBaseUrl,
            globalVisualStyle = globalVisualStyle,
            negativePrompt = negativePrompt,
            characterReferenceImagePath = characterReferenceImagePath,
            selectedWorkflowId = selectedWorkflowId,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now
        )
        database.projectDao().upsertProject(entity)
        return projectId
    }

    suspend fun updateProjectSettings(
        project: ProjectEntity,
        renderMode: RenderMode,
        comfyUiBaseUrl: String?,
        selectedWorkflowId: String?
    ) {
        val updated = project.copy(
            renderMode = renderMode.name,
            comfyUiBaseUrl = comfyUiBaseUrl,
            selectedWorkflowId = selectedWorkflowId,
            updatedAtEpochMillis = System.currentTimeMillis()
        )
        database.projectDao().upsertProject(updated)
    }

    suspend fun saveScenes(projectId: String, scenes: List<GeneratedScene>) {
        database.projectDao().deleteScenesForProject(projectId)
        val entities = scenes.map { scene ->
            SceneEntity(
                id = scene.id,
                projectId = projectId,
                orderIndex = scene.orderIndex,
                label = scene.label,
                startTimeSeconds = scene.startTimeSeconds,
                endTimeSeconds = scene.endTimeSeconds,
                prompt = scene.prompt,
                backgroundImagePath = null,
                cameraMovement = scene.cameraMovement,
                transitionType = scene.transitionType,
                effectsJson = scene.effects.joinToString(","),
                intensity = scene.intensity,
                seed = scene.seed,
                generationStatus = GenerationStatus.NOT_GENERATED.name,
                generatedMediaPath = null,
                generatedMediaType = GeneratedMediaType.NONE.name,
                generationErrorMessage = null
            )
        }
        database.projectDao().upsertScenes(entities)
    }

    suspend fun updateScene(scene: SceneEntity) {
        database.projectDao().updateScene(scene)
    }

    suspend fun deleteScene(scene: SceneEntity) {
        database.projectDao().deleteScene(scene)
    }

    suspend fun deleteProject(project: ProjectEntity) {
        database.projectDao().deleteProject(project)
    }

    suspend fun markSceneGenerating(scene: SceneEntity) {
        database.projectDao().updateScene(
            scene.copy(generationStatus = GenerationStatus.GENERATING.name, generationErrorMessage = null)
        )
    }

    suspend fun markSceneGenerated(scene: SceneEntity, filePath: String, mediaType: GeneratedMediaType) {
        database.projectDao().updateScene(
            scene.copy(
                generationStatus = GenerationStatus.GENERATED.name,
                generatedMediaPath = filePath,
                generatedMediaType = mediaType.name,
                generationErrorMessage = null
            )
        )
    }

    suspend fun markSceneFailed(scene: SceneEntity, errorMessage: String) {
        database.projectDao().updateScene(
            scene.copy(
                generationStatus = GenerationStatus.FAILED.name,
                generationErrorMessage = errorMessage
            )
        )
    }

    suspend fun resetSceneForRegeneration(scene: SceneEntity, newSeed: Long?) {
        database.projectDao().updateScene(
            scene.copy(
                generationStatus = GenerationStatus.NOT_GENERATED.name,
                generatedMediaPath = null,
                generatedMediaType = GeneratedMediaType.NONE.name,
                generationErrorMessage = null,
                seed = newSeed ?: scene.seed
            )
        )
    }
}

suspend fun ProjectRepository.saveScenesRaw(scenes: List<SceneEntity>) {
    for (scene in scenes) {
        this.updateScene(scene)
    }
}

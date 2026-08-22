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
        aspectRatio: String
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
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now
        )
        database.projectDao().upsertProject(entity)
        return projectId
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
                intensity = scene.intensity
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
}

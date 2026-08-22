package com.example.aivideostudio.ui.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aivideostudio.data.ProjectEntity
import com.example.aivideostudio.data.SceneEntity
import com.example.aivideostudio.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class SceneEditorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ServiceLocator.getProjectRepository(application)

    private val _scenes = MutableStateFlow<List<SceneEntity>>(emptyList())
    val scenes: StateFlow<List<SceneEntity>> = _scenes.asStateFlow()

    private val _project = MutableStateFlow<ProjectEntity?>(null)
    val project: StateFlow<ProjectEntity?> = _project.asStateFlow()

    private var currentProjectId: String = ""

    fun load(projectId: String) {
        if (currentProjectId == projectId) return
        currentProjectId = projectId
        viewModelScope.launch {
            repository.observeScenes(projectId).collect { list ->
                _scenes.value = list
            }
        }
        viewModelScope.launch {
            _project.value = repository.getProject(projectId)
        }
    }

    fun updateScenePrompt(scene: SceneEntity, newPrompt: String) {
        viewModelScope.launch {
            repository.updateScene(scene.copy(prompt = newPrompt))
        }
    }

    fun updateSceneTiming(scene: SceneEntity, startTime: Double, endTime: Double) {
        if (endTime <= startTime) return
        viewModelScope.launch {
            repository.updateScene(scene.copy(startTimeSeconds = startTime, endTimeSeconds = endTime))
        }
    }

    fun deleteScene(scene: SceneEntity) {
        viewModelScope.launch {
            repository.deleteScene(scene)
        }
    }

    fun duplicateScene(scene: SceneEntity) {
        viewModelScope.launch {
            val duplicated = scene.copy(
                id = UUID.randomUUID().toString(),
                orderIndex = scene.orderIndex + 1
            )
            val currentScenes = _scenes.value.toMutableList()
            currentScenes.add(duplicated)
            repository.saveScenesRaw(currentScenes)
        }
    }

    fun moveSceneUp(scene: SceneEntity) {
        reorderScene(scene, moveUp = true)
    }

    fun moveSceneDown(scene: SceneEntity) {
        reorderScene(scene, moveUp = false)
    }

    private fun reorderScene(scene: SceneEntity, moveUp: Boolean) {
        viewModelScope.launch {
            val currentScenes = _scenes.value.sortedBy { it.orderIndex }.toMutableList()
            val index = currentScenes.indexOfFirst { it.id == scene.id }
            if (index < 0) return@launch
            val targetIndex = if (moveUp) index - 1 else index + 1
            if (targetIndex < 0 || targetIndex >= currentScenes.size) return@launch
            val temp = currentScenes[index]
            currentScenes[index] = currentScenes[targetIndex]
            currentScenes[targetIndex] = temp
            val reindexed = currentScenes.mapIndexed { newIndex, item -> item.copy(orderIndex = newIndex) }
            repository.saveScenesRaw(reindexed)
        }
    }
}

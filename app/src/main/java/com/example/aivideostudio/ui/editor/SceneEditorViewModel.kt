package com.example.aivideostudio.ui.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.aivideostudio.data.ProjectEntity
import com.example.aivideostudio.data.RenderMode
import com.example.aivideostudio.data.SceneEntity
import com.example.aivideostudio.data.saveScenesRaw
import com.example.aivideostudio.di.ServiceLocator
import com.example.aivideostudio.huggingface.HuggingFaceRenderWorker
import com.example.aivideostudio.render.SingleSceneRegenerationWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

sealed class HuggingFaceGenerationState {
    object Idle : HuggingFaceGenerationState()
    data class Running(val percent: Int, val statusLabel: String) : HuggingFaceGenerationState()
    data class Failed(val message: String) : HuggingFaceGenerationState()
    data class Completed(val outputPath: String) : HuggingFaceGenerationState()
}

class SceneEditorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ServiceLocator.getProjectRepository(application)
    private val workManager = WorkManager.getInstance(application)

    private val _scenes = MutableStateFlow<List<SceneEntity>>(emptyList())
    val scenes: StateFlow<List<SceneEntity>> = _scenes.asStateFlow()

    private val _project = MutableStateFlow<ProjectEntity?>(null)
    val project: StateFlow<ProjectEntity?> = _project.asStateFlow()

    private val _huggingFaceState = MutableStateFlow<HuggingFaceGenerationState>(HuggingFaceGenerationState.Idle)
    val huggingFaceState: StateFlow<HuggingFaceGenerationState> = _huggingFaceState.asStateFlow()

    private var currentProjectId: String = ""
    private var observedLiveData: LiveData<List<WorkInfo>>? = null
    private var currentObserver: Observer<List<WorkInfo>>? = null

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
        viewModelScope.launch { repository.updateScene(scene.copy(prompt = newPrompt)) }
    }

    fun updateSceneBackgroundImage(scene: SceneEntity, imagePath: String) {
        viewModelScope.launch { repository.updateScene(scene.copy(backgroundImagePath = imagePath)) }
    }

    fun clearSceneBackgroundImage(scene: SceneEntity) {
        viewModelScope.launch { repository.updateScene(scene.copy(backgroundImagePath = null)) }
    }

    fun updateSceneReferenceImage(scene: SceneEntity, imagePath: String) {
        viewModelScope.launch { repository.updateScene(scene.copy(referenceImagePath = imagePath)) }
    }

    fun clearSceneReferenceImage(scene: SceneEntity) {
        viewModelScope.launch { repository.updateScene(scene.copy(referenceImagePath = null)) }
    }

    fun updateSceneTiming(scene: SceneEntity, startTime: Double, endTime: Double) {
        if (endTime <= startTime) return
        viewModelScope.launch {
            val currentScenes = _scenes.value.sortedBy { it.orderIndex }
            val sceneIndex = currentScenes.indexOfFirst { it.id == scene.id }
            if (sceneIndex < 0) {
                repository.updateScene(scene.copy(startTimeSeconds = startTime, endTimeSeconds = endTime))
                return@launch
            }
            val nextScene = currentScenes.getOrNull(sceneIndex + 1)
            val minimumNextDuration = 1.0
            if (nextScene != null && endTime >= nextScene.endTimeSeconds - minimumNextDuration) {
                return@launch
            }
            repository.updateScene(scene.copy(startTimeSeconds = startTime, endTimeSeconds = endTime))
            if (nextScene != null && nextScene.startTimeSeconds != endTime) {
                repository.updateScene(nextScene.copy(startTimeSeconds = endTime))
            }
        }
    }

    fun deleteScene(scene: SceneEntity) {
        viewModelScope.launch { repository.deleteScene(scene) }
    }

    fun duplicateScene(scene: SceneEntity) {
        viewModelScope.launch {
            val duplicated = scene.copy(id = UUID.randomUUID().toString(), orderIndex = scene.orderIndex + 1)
            val currentScenes = _scenes.value.toMutableList()
            currentScenes.add(duplicated)
            repository.saveScenesRaw(currentScenes)
        }
    }

    fun moveSceneUp(scene: SceneEntity) = reorderScene(scene, moveUp = true)

    fun moveSceneDown(scene: SceneEntity) = reorderScene(scene, moveUp = false)

    fun regenerateScene(scene: SceneEntity, useNewSeed: Boolean) {
        val currentProject = _project.value
        if (currentProject?.renderMode == RenderMode.HUGGINGFACE.name) {
            regenerateSceneWithHuggingFace(scene)
            return
        }
        val inputData = Data.Builder()
            .putString(SingleSceneRegenerationWorker.KEY_PROJECT_ID, currentProjectId)
            .putString(SingleSceneRegenerationWorker.KEY_SCENE_ID, scene.id)
            .putBoolean(SingleSceneRegenerationWorker.KEY_USE_NEW_SEED, useNewSeed)
            .build()
        val request = OneTimeWorkRequestBuilder<SingleSceneRegenerationWorker>().setInputData(inputData).build()
        workManager.enqueue(request)
    }

    private fun regenerateSceneWithHuggingFace(scene: SceneEntity) {
        val inputData = Data.Builder()
            .putString(HuggingFaceRenderWorker.KEY_PROJECT_ID, currentProjectId)
            .putString(HuggingFaceRenderWorker.KEY_SCENE_ID, scene.id)
            .build()
        val request = OneTimeWorkRequestBuilder<HuggingFaceRenderWorker>().setInputData(inputData).build()
        workManager.enqueue(request)
    }

    fun startHuggingFaceGeneration() {
        val projectId = currentProjectId
        if (projectId.isBlank()) return
        val uniqueWorkName = HUGGINGFACE_WORK_PREFIX + projectId

        clearObserver()
        _huggingFaceState.value = HuggingFaceGenerationState.Running(0, "Wird vorbereitet")

        val inputData = Data.Builder().putString(HuggingFaceRenderWorker.KEY_PROJECT_ID, projectId).build()
        val request = OneTimeWorkRequestBuilder<HuggingFaceRenderWorker>()
            .setInputData(inputData)
            .addTag(uniqueWorkName)
            .build()

        workManager.enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.KEEP, request)

        val liveData = workManager.getWorkInfosForUniqueWorkLiveData(uniqueWorkName)
        val observer = Observer<List<WorkInfo>> { workInfos ->
            val workInfo = workInfos?.firstOrNull() ?: return@Observer
            when (workInfo.state) {
                WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                    val percent = workInfo.progress.getInt(HuggingFaceRenderWorker.KEY_PROGRESS_PERCENT, 0)
                    val label = workInfo.progress.getString(HuggingFaceRenderWorker.KEY_CURRENT_SCENE) ?: "Wird vorbereitet"
                    _huggingFaceState.value = HuggingFaceGenerationState.Running(percent, label)
                }
                WorkInfo.State.SUCCEEDED -> {
                    val outputPath = workInfo.outputData.getString(HuggingFaceRenderWorker.KEY_OUTPUT_PATH)
                    _huggingFaceState.value = if (outputPath != null) {
                        HuggingFaceGenerationState.Completed(outputPath)
                    } else {
                        HuggingFaceGenerationState.Failed("Rendern wurde abgebrochen.")
                    }
                }
                WorkInfo.State.FAILED -> {
                    val message = workInfo.outputData.getString(HuggingFaceRenderWorker.KEY_ERROR_MESSAGE) ?: "Rendern wurde abgebrochen."
                    _huggingFaceState.value = HuggingFaceGenerationState.Failed(message)
                }
                WorkInfo.State.CANCELLED -> {
                    _huggingFaceState.value = HuggingFaceGenerationState.Failed("Rendern wurde abgebrochen.")
                }
                else -> Unit
            }
        }
        observedLiveData = liveData
        currentObserver = observer
        liveData.observeForever(observer)
    }

    private fun clearObserver() {
        val liveData = observedLiveData
        val observer = currentObserver
        if (liveData != null && observer != null) {
            liveData.removeObserver(observer)
        }
        observedLiveData = null
        currentObserver = null
    }

    override fun onCleared() {
        super.onCleared()
        clearObserver()
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

    companion object {
        private const val HUGGINGFACE_WORK_PREFIX = "huggingface_render_"
    }
}

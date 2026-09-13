package com.example.aivideostudio.ui.preview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.aivideostudio.render.RenderWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class PreviewUiState {
    object Idle : PreviewUiState()
    data class Rendering(val percent: Int, val sceneLabel: String) : PreviewUiState()
    data class Failed(val message: String) : PreviewUiState()
    data class Completed(val outputPath: String) : PreviewUiState()
}

class PreviewViewModel(application: Application) : AndroidViewModel(application) {

    private val workManager = WorkManager.getInstance(application)

    private val _uiState = MutableStateFlow<PreviewUiState>(PreviewUiState.Idle)
    val uiState: StateFlow<PreviewUiState> = _uiState.asStateFlow()

    private var observedLiveData: LiveData<WorkInfo>? = null
    private var currentObserver: Observer<WorkInfo>? = null

    fun startRender(projectId: String) {
        clearObserver()
        viewModelScope.launch {
            _uiState.value = PreviewUiState.Rendering(0, "")

            val inputData = Data.Builder()
                .putString(RenderWorker.KEY_PROJECT_ID, projectId)
                .build()

            val request = OneTimeWorkRequestBuilder<RenderWorker>()
                .setInputData(inputData)
                .addTag(RENDER_TAG_PREFIX + projectId)
                .build()

            workManager.enqueue(request)

            val liveData = workManager.getWorkInfoByIdLiveData(request.id)
            val observer = Observer<WorkInfo> { workInfo ->
                if (workInfo == null) return@Observer
                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> {
                        val percent = workInfo.progress.getInt(RenderWorker.KEY_PROGRESS_PERCENT, 0)
                        val sceneLabel = workInfo.progress.getString(RenderWorker.KEY_CURRENT_SCENE) ?: ""
                        _uiState.value = PreviewUiState.Rendering(percent, sceneLabel)
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val outputPath = workInfo.outputData.getString(RenderWorker.KEY_OUTPUT_PATH)
                        if (outputPath != null) {
                            _uiState.value = PreviewUiState.Completed(outputPath)
                        } else {
                            _uiState.value = PreviewUiState.Failed("Rendern wurde abgebrochen.")
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        val message = workInfo.outputData.getString(RenderWorker.KEY_ERROR_MESSAGE)
                            ?: "Rendern wurde abgebrochen."
                        _uiState.value = PreviewUiState.Failed(message)
                    }
                    WorkInfo.State.CANCELLED -> {
                        _uiState.value = PreviewUiState.Failed("Rendern wurde abgebrochen.")
                    }
                    else -> Unit
                }
            }

            observedLiveData = liveData
            currentObserver = observer
            liveData.observeForever(observer)
        }
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

    companion object {
        private const val RENDER_TAG_PREFIX = "render_job_"
    }
}

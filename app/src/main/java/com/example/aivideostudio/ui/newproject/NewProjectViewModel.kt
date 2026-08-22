package com.example.aivideostudio.ui.newproject

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aivideostudio.audio.BeatDetector
import com.example.aivideostudio.audio.PcmAudioDecoder
import com.example.aivideostudio.data.RenderPresets
import com.example.aivideostudio.di.ServiceLocator
import com.example.aivideostudio.storyboard.StoryboardGenerator
import com.example.aivideostudio.storyboard.VisualPromptAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class NewProjectUiState {
    object Idle : NewProjectUiState()
    object Analyzing : NewProjectUiState()
    data class Error(val message: String) : NewProjectUiState()
    data class Created(val projectId: String) : NewProjectUiState()
}

class NewProjectViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ServiceLocator.getProjectRepository(application)

    private val _uiState = MutableStateFlow<NewProjectUiState>(NewProjectUiState.Idle)
    val uiState: StateFlow<NewProjectUiState> = _uiState.asStateFlow()

    fun createProject(
        projectName: String,
        songFilePath: String,
        visualPrompt: String,
        resolutionLabel: String,
        aspectRatio: String
    ) {
        viewModelScope.launch {
            _uiState.value = NewProjectUiState.Analyzing
            try {
                val audioFeatures = withContext(Dispatchers.Default) {
                    val decoded = PcmAudioDecoder(songFilePath).decode()
                    BeatDetector(decoded.samples, decoded.sampleRate, decoded.channelCount).analyze()
                }

                val renderSettings = RenderPresets.forResolutionLabel(resolutionLabel, aspectRatio)

                val projectId = repository.createProject(
                    name = projectName,
                    songFilePath = songFilePath,
                    audioFeatures = audioFeatures,
                    visualPrompt = visualPrompt,
                    resolutionWidth = renderSettings.resolutionWidth,
                    resolutionHeight = renderSettings.resolutionHeight,
                    aspectRatio = aspectRatio
                )

                val visualParameters = VisualPromptAnalyzer().analyze(visualPrompt)
                val scenes = StoryboardGenerator().generate(
                    projectId = projectId,
                    audioFeatures = audioFeatures,
                    visualParameters = visualParameters,
                    basePrompt = visualPrompt
                )
                repository.saveScenes(projectId, scenes)

                _uiState.value = NewProjectUiState.Created(projectId)
            } catch (exception: Exception) {
                _uiState.value = NewProjectUiState.Error(exception.message ?: "Audio konnte nicht analysiert werden.")
            }
        }
    }
}

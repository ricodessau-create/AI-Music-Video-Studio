package com.example.aivideostudio.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aivideostudio.comfyui.StoredWorkflow
import com.example.aivideostudio.comfyui.WorkflowMediaType
import com.example.aivideostudio.comfyui.WorkflowRepository
import com.example.aivideostudio.data.ProjectEntity
import com.example.aivideostudio.data.RenderMode
import com.example.aivideostudio.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProjectSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ServiceLocator.getProjectRepository(application)
    private val workflowRepository = WorkflowRepository()

    private val _project = MutableStateFlow<ProjectEntity?>(null)
    val project: StateFlow<ProjectEntity?> = _project.asStateFlow()

    private val _workflows = MutableStateFlow<List<StoredWorkflow>>(emptyList())
    val workflows: StateFlow<List<StoredWorkflow>> = _workflows.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    private var currentProjectId: String = ""

    fun load(projectId: String) {
        currentProjectId = projectId
        viewModelScope.launch {
            _project.value = repository.getProject(projectId)
        }
        refreshWorkflows()
    }

    fun refreshWorkflows() {
        viewModelScope.launch {
            _workflows.value = withContext(Dispatchers.IO) {
                workflowRepository.listWorkflows(getApplication())
            }
        }
    }

    fun importWorkflow(uri: Uri, name: String, mediaType: WorkflowMediaType) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                workflowRepository.importWorkflow(getApplication(), uri, name, mediaType)
            }
            if (result == null) {
                _importError.value = "Workflow-Datei konnte nicht importiert werden."
            } else {
                _importError.value = null
                refreshWorkflows()
            }
        }
    }

    fun deleteWorkflow(workflow: StoredWorkflow) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                workflowRepository.deleteWorkflow(getApplication(), workflow)
            }
            refreshWorkflows()
        }
    }

    fun saveSettings(renderMode: RenderMode, comfyUiBaseUrl: String?, selectedWorkflowId: String?) {
        viewModelScope.launch {
            val currentProject = _project.value ?: return@launch
            repository.updateProjectSettings(
                project = currentProject,
                renderMode = renderMode,
                comfyUiBaseUrl = comfyUiBaseUrl,
                selectedWorkflowId = selectedWorkflowId
            )
            _project.value = repository.getProject(currentProjectId)
        }
    }
}

package com.example.aivideostudio.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aivideostudio.comfyui.WorkflowMediaType
import com.example.aivideostudio.data.RenderMode

@Composable
fun ProjectSettingsScreen(
    projectId: String,
    onBack: () -> Unit,
    viewModel: ProjectSettingsViewModel = viewModel()
) {
    LaunchedEffect(projectId) {
        viewModel.load(projectId)
    }

    val project by viewModel.project.collectAsState()
    val workflows by viewModel.workflows.collectAsState()
    val importError by viewModel.importError.collectAsState()

    var renderMode by remember(project) {
        mutableStateOf(project?.renderMode?.let { RenderMode.valueOf(it) } ?: RenderMode.OFFLINE)
    }
    var comfyUiBaseUrl by remember(project) {
        mutableStateOf(project?.comfyUiBaseUrl ?: "http://192.168.178.50:8188")
    }
    var selectedWorkflowId by remember(project) {
        mutableStateOf(project?.selectedWorkflowId)
    }
    var newWorkflowName by remember { mutableStateOf("Mein Workflow") }
    var newWorkflowMediaType by remember { mutableStateOf(WorkflowMediaType.IMAGE) }
    var mediaTypeExpanded by remember { mutableStateOf(false) }

    val workflowPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            viewModel.importWorkflow(uri, newWorkflowName, newWorkflowMediaType)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Projekteinstellungen") }) }) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Render-Modus")
            Row {
                RadioButton(selected = renderMode == RenderMode.OFFLINE, onClick = { renderMode = RenderMode.OFFLINE })
                Text("Offline Renderer", modifier = Modifier.padding(top = 12.dp, end = 16.dp))
                RadioButton(selected = renderMode == RenderMode.COMFYUI, onClick = { renderMode = RenderMode.COMFYUI })
                Text("ComfyUI KI", modifier = Modifier.padding(top = 12.dp))
            }

            if (renderMode == RenderMode.COMFYUI) {
                OutlinedTextField(
                    value = comfyUiBaseUrl,
                    onValueChange = { comfyUiBaseUrl = it },
                    label = { Text("ComfyUI-Server-Adresse") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Vorhandene Workflows")
                if (workflows.isEmpty()) {
                    Text("Noch keine Workflows importiert.")
                } else {
                    workflows.forEach { workflow ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row {
                                    RadioButton(
                                        selected = selectedWorkflowId == workflow.id,
                                        onClick = { selectedWorkflowId = workflow.id }
                                    )
                                    Column(modifier = Modifier.padding(start = 8.dp)) {
                                        Text(workflow.name)
                                        Text(if (workflow.mediaType == WorkflowMediaType.IMAGE) "Bild-Workflow" else "Video-Workflow")
                                    }
                                }
                                IconButton(onClick = {
                                    if (selectedWorkflowId == workflow.id) {
                                        selectedWorkflowId = null
                                    }
                                    viewModel.deleteWorkflow(workflow)
                                }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Löschen")
                                }
                            }
                        }
                    }
                }

                Text("Neuen Workflow importieren")
                OutlinedTextField(
                    value = newWorkflowName,
                    onValueChange = { newWorkflowName = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth()
                )

                ExposedDropdownMenuBox(expanded = mediaTypeExpanded, onExpandedChange = { mediaTypeExpanded = it }) {
                    OutlinedTextField(
                        value = if (newWorkflowMediaType == WorkflowMediaType.IMAGE) "Bild-Workflow" else "Video-Workflow",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Workflow-Typ") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = mediaTypeExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    DropdownMenu(expanded = mediaTypeExpanded, onDismissRequest = { mediaTypeExpanded = false }) {
                        DropdownMenuItem(text = { Text("Bild-Workflow") }, onClick = {
                            newWorkflowMediaType = WorkflowMediaType.IMAGE
                            mediaTypeExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Video-Workflow") }, onClick = {
                            newWorkflowMediaType = WorkflowMediaType.VIDEO
                            mediaTypeExpanded = false
                        })
                    }
                }

                Button(onClick = { workflowPickerLauncher.launch("application/json") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Workflow-JSON-Datei auswählen")
                }

                val currentImportError = importError
                if (currentImportError != null) {
                    Text(text = currentImportError)
                }
            }

            Button(
                onClick = {
                    viewModel.saveSettings(
                        renderMode = renderMode,
                        comfyUiBaseUrl = if (renderMode == RenderMode.COMFYUI) comfyUiBaseUrl else null,
                        selectedWorkflowId = if (renderMode == RenderMode.COMFYUI) selectedWorkflowId else null
                    )
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Speichern")
            }
        }
    }
}

package com.example.aivideostudio.ui.newproject

import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aivideostudio.comfyui.StoredWorkflow
import com.example.aivideostudio.comfyui.WorkflowRepository
import com.example.aivideostudio.data.RenderMode
import com.example.aivideostudio.util.FileCopyUtils

@Composable
fun NewProjectScreen(onProjectCreated: (String) -> Unit, viewModel: NewProjectViewModel = viewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    var projectName by remember { mutableStateOf("Mein Musikvideo") }
    var visualPrompt by remember { mutableStateOf("") }
    var globalVisualStyle by remember { mutableStateOf("dark fantasy, cinematic, dramatic lighting, high detail") }
    var negativePrompt by remember { mutableStateOf("low quality, blurry, distorted, deformed, bad anatomy, duplicate, text, watermark, logo") }
    var selectedSongUri by remember { mutableStateOf<Uri?>(null) }
    var selectedReferenceUri by remember { mutableStateOf<Uri?>(null) }
    var resolutionExpanded by remember { mutableStateOf(false) }
    var selectedResolution by remember { mutableStateOf("1080p") }
    var aspectExpanded by remember { mutableStateOf(false) }
    var selectedAspect by remember { mutableStateOf("16:9") }
    var renderMode by remember { mutableStateOf(RenderMode.OFFLINE) }
    var comfyUiBaseUrl by remember { mutableStateOf("http://192.168.178.50:8188") }
    var workflowExpanded by remember { mutableStateOf(false) }
    var availableWorkflows by remember { mutableStateOf<List<StoredWorkflow>>(emptyList()) }
    var selectedWorkflow by remember { mutableStateOf<StoredWorkflow?>(null) }

    val workflowRepository = remember { WorkflowRepository() }

    LaunchedEffect(renderMode) {
        if (renderMode == RenderMode.COMFYUI) {
            availableWorkflows = workflowRepository.listWorkflows(context)
        }
    }

    val songPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedSongUri = uri
    }

    val referencePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedReferenceUri = uri
    }

    LaunchedEffect(uiState) {
        val currentState = uiState
        if (currentState is NewProjectUiState.Created) {
            onProjectCreated(currentState.projectId)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Neues Projekt") }) }) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = projectName,
                onValueChange = { projectName = it },
                label = { Text("Projektname") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(onClick = { songPickerLauncher.launch("audio/*") }, modifier = Modifier.fillMaxWidth()) {
                Text(if (selectedSongUri == null) "Song auswählen" else "Song ausgewählt")
            }

            OutlinedTextField(
                value = visualPrompt,
                onValueChange = { visualPrompt = it },
                label = { Text("Visueller Stil / Beschreibung") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            OutlinedTextField(
                value = globalVisualStyle,
                onValueChange = { globalVisualStyle = it },
                label = { Text("Globaler visueller Stil (wird an jede Szene angehängt)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )

            ExposedDropdownMenuBox(expanded = resolutionExpanded, onExpandedChange = { resolutionExpanded = it }) {
                OutlinedTextField(
                    value = selectedResolution,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Auflösung") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = resolutionExpanded) },
                    modifier = Modifier.fillMaxWidth()
                )
                DropdownMenu(expanded = resolutionExpanded, onDismissRequest = { resolutionExpanded = false }) {
                    listOf("720p", "1080p", "4K").forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = {
                            selectedResolution = option
                            resolutionExpanded = false
                        })
                    }
                }
            }

            ExposedDropdownMenuBox(expanded = aspectExpanded, onExpandedChange = { aspectExpanded = it }) {
                OutlinedTextField(
                    value = selectedAspect,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Seitenverhältnis") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = aspectExpanded) },
                    modifier = Modifier.fillMaxWidth()
                )
                DropdownMenu(expanded = aspectExpanded, onDismissRequest = { aspectExpanded = false }) {
                    listOf("16:9", "9:16", "1:1").forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = {
                            selectedAspect = option
                            aspectExpanded = false
                        })
                    }
                }
            }

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

                OutlinedTextField(
                    value = negativePrompt,
                    onValueChange = { negativePrompt = it },
                    label = { Text("Negative Prompt") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                Button(onClick = { referencePickerLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (selectedReferenceUri == null) "Referenzbild wählen (optional)" else "Referenzbild ausgewählt")
                }

                if (availableWorkflows.isEmpty()) {
                    Text("Kein Workflow importiert. Workflows können nach Erstellung des Projekts in den Projekteinstellungen importiert werden.")
                } else {
                    ExposedDropdownMenuBox(expanded = workflowExpanded, onExpandedChange = { workflowExpanded = it }) {
                        OutlinedTextField(
                            value = selectedWorkflow?.name ?: "Workflow wählen",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("ComfyUI-Workflow") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = workflowExpanded) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        DropdownMenu(expanded = workflowExpanded, onDismissRequest = { workflowExpanded = false }) {
                            availableWorkflows.forEach { workflow ->
                                DropdownMenuItem(text = { Text(workflow.name) }, onClick = {
                                    selectedWorkflow = workflow
                                    workflowExpanded = false
                                })
                            }
                        }
                    }
                }
            }

            when (val currentState = uiState) {
                is NewProjectUiState.Analyzing -> CircularProgressIndicator()
                is NewProjectUiState.Error -> Text(text = currentState.message)
                else -> Unit
            }

            Button(
                onClick = {
                    val songUri = selectedSongUri
                    if (songUri != null) {
                        val localSongPath = FileCopyUtils.copyUriToInternalStorage(context, songUri, "song_input")
                        val localReferencePath = selectedReferenceUri?.let {
                            FileCopyUtils.copyUriToInternalStorage(context, it, "reference_image")
                        }
                        if (localSongPath != null) {
                            viewModel.createProject(
                                projectName = projectName,
                                songFilePath = localSongPath,
                                visualPrompt = visualPrompt,
                                resolutionLabel = selectedResolution,
                                aspectRatio = selectedAspect,
                                renderMode = renderMode,
                                comfyUiBaseUrl = if (renderMode == RenderMode.COMFYUI) comfyUiBaseUrl else null,
                                globalVisualStyle = globalVisualStyle,
                                negativePrompt = negativePrompt,
                                characterReferenceImagePath = localReferencePath,
                                selectedWorkflowId = selectedWorkflow?.id
                            )
                        }
                    }
                },
                enabled = selectedSongUri != null &&
                    uiState !is NewProjectUiState.Analyzing &&
                    (renderMode == RenderMode.OFFLINE || selectedWorkflow != null),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Musikvideo generieren")
            }
        }
    }
}

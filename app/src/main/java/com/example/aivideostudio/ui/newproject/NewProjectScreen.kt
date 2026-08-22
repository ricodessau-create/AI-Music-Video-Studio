package com.example.aivideostudio.ui.newproject

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
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
import com.example.aivideostudio.util.FileCopyUtils

@Composable
fun NewProjectScreen(onProjectCreated: (String) -> Unit, viewModel: NewProjectViewModel = viewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    var projectName by remember { mutableStateOf("Mein Musikvideo") }
    var visualPrompt by remember { mutableStateOf("") }
    var selectedSongUri by remember { mutableStateOf<Uri?>(null) }
    var resolutionExpanded by remember { mutableStateOf(false) }
    var selectedResolution by remember { mutableStateOf("1080p") }
    var aspectExpanded by remember { mutableStateOf(false) }
    var selectedAspect by remember { mutableStateOf("16:9") }

    val songPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedSongUri = uri
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
                .padding(16.dp),
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
                label = { Text("Visueller Stil") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
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

            when (val currentState = uiState) {
                is NewProjectUiState.Analyzing -> CircularProgressIndicator()
                is NewProjectUiState.Error -> Text(text = currentState.message)
                else -> Unit
            }

            Button(
                onClick = {
                    val uri = selectedSongUri
                    if (uri != null) {
                        val localPath = FileCopyUtils.copyUriToInternalStorage(context, uri, "song_input")
                        if (localPath != null) {
                            viewModel.createProject(
                                projectName = projectName,
                                songFilePath = localPath,
                                visualPrompt = visualPrompt,
                                resolutionLabel = selectedResolution,
                                aspectRatio = selectedAspect
                            )
                        }
                    }
                },
                enabled = selectedSongUri != null && uiState !is NewProjectUiState.Analyzing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Musikvideo generieren")
            }
        }
    }
}

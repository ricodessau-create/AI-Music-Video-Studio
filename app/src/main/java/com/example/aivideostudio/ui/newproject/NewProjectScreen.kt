package com.example.aivideostudio.ui.newproject

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aivideostudio.audio.SongGenre
import com.example.aivideostudio.comfyui.StoredWorkflow
import com.example.aivideostudio.comfyui.WorkflowRepository
import com.example.aivideostudio.data.RenderMode
import com.example.aivideostudio.util.FileCopyUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun genreDisplayName(genre: SongGenre?): String {
    return when (genre) {
        null -> "Automatisch erkennen"
        SongGenre.BALLADE -> "Ballade"
        SongGenre.POP -> "Pop"
        SongGenre.ROCK -> "Rock"
        SongGenre.METAL -> "Metal"
        SongGenre.HIP_HOP -> "Hip-Hop"
        SongGenre.OTHER -> "Sonstiges"
    }
}

private fun decodePreview(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 1024) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeFile(path, options)
}

@Composable
fun NewProjectScreen(onProjectCreated: (String) -> Unit, viewModel: NewProjectViewModel = viewModel()) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()

    var projectName by remember { mutableStateOf("Mein Musikvideo") }
    var visualPrompt by remember { mutableStateOf("") }
    var globalVisualStyle by remember { mutableStateOf("dark fantasy, cinematic, dramatic lighting, high detail") }
    var negativePrompt by remember { mutableStateOf("low quality, blurry, distorted, deformed, bad anatomy, duplicate, text, watermark, logo") }
    var selectedSongUri by remember { mutableStateOf<Uri?>(null) }
    var referenceImagePath by remember { mutableStateOf<String?>(null) }
    var referencePreview by remember { mutableStateOf<Bitmap?>(null) }
    var resolutionExpanded by remember { mutableStateOf(false) }
    var selectedResolution by remember { mutableStateOf("1080p") }
    var aspectExpanded by remember { mutableStateOf(false) }
    var selectedAspect by remember { mutableStateOf("16:9") }
    var renderMode by remember { mutableStateOf(RenderMode.OFFLINE) }
    var comfyUiBaseUrl by remember { mutableStateOf("http://192.168.178.50:8188") }
    var workflowExpanded by remember { mutableStateOf(false) }
    var availableWorkflows by remember { mutableStateOf<List<StoredWorkflow>>(emptyList()) }
    var selectedWorkflow by remember { mutableStateOf<StoredWorkflow?>(null) }
    var genreExpanded by remember { mutableStateOf(false) }
    var selectedGenre by remember { mutableStateOf<SongGenre?>(null) }

    val workflowRepository = remember { WorkflowRepository() }

    LaunchedEffect(renderMode) {
        if (renderMode == RenderMode.COMFYUI) {
            availableWorkflows = withContext(Dispatchers.IO) {
                workflowRepository.listWorkflows(context)
            }
        }
    }

    val songPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedSongUri = uri
    }

    val referencePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    val path = FileCopyUtils.copyImageNormalized(context, uri, "reference_image")
                    val preview = path?.let { decodePreview(it) }
                    Pair(path, preview)
                }
                if (result.first != null) {
                    referenceImagePath = result.first
                    referencePreview = result.second
                }
            }
        }
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

            val previewBitmap = referencePreview
            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = "Vorschau Bild der Band oder des Interpreten",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )
            }

            Button(onClick = { referencePickerLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                Text(if (referenceImagePath == null) "Bild von Band / Interpret auswählen" else "Bild ändern")
            }

            if (referenceImagePath != null) {
                OutlinedButton(
                    onClick = {
                        referenceImagePath = null
                        referencePreview = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Bild entfernen")
                }
            }

            Text(
                if (renderMode == RenderMode.COMFYUI) {
                    "Das Bild wird als Startbild an den ComfyUI-Workflow übergeben. Für echte Bewegungen von Sänger und Band brauchst du einen Bild-zu-Video-Workflow mit dem Platzhalter {{REFERENCE_IMAGE_NAME}}."
                } else {
                    "Das Bild wird als Hauptmotiv verwendet und mit Kamerafahrten, Parallax und Beat-Bewegung animiert. Echte Körperbewegungen erzeugt nur der ComfyUI-Modus mit einem Bild-zu-Video-Workflow."
                }
            )

            ExposedDropdownMenuBox(expanded = genreExpanded, onExpandedChange = { genreExpanded = it }) {
                OutlinedTextField(
                    value = genreDisplayName(selectedGenre),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Genre") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = genreExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                DropdownMenu(expanded = genreExpanded, onDismissRequest = { genreExpanded = false }) {
                    val options = listOf<SongGenre?>(null, SongGenre.BALLADE, SongGenre.POP, SongGenre.ROCK, SongGenre.METAL, SongGenre.HIP_HOP)
                    options.forEach { option ->
                        DropdownMenuItem(text = { Text(genreDisplayName(option)) }, onClick = {
                            selectedGenre = option
                            genreExpanded = false
                        })
                    }
                }
            }
            Text("Bei \"Automatisch erkennen\" schätzt die App das Genre grob anhand von Tempo und Energie des Songs. Das ist eine Heuristik, keine exakte Erkennung — bei Bedarf hier manuell überschreiben.")

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
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
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

                if (availableWorkflows.isEmpty()) {
                    Text("Kein Workflow importiert. Workflows können nach Erstellung des Projekts in den Projekteinstellungen (Zahnrad-Symbol im Szeneneditor) importiert werden.")
                } else {
                    ExposedDropdownMenuBox(expanded = workflowExpanded, onExpandedChange = { workflowExpanded = it }) {
                        OutlinedTextField(
                            value = selectedWorkflow?.name ?: "Workflow wählen",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("ComfyUI-Workflow") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = workflowExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
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
                                characterReferenceImagePath = referenceImagePath,
                                selectedWorkflowId = selectedWorkflow?.id,
                                manualGenre = selectedGenre
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

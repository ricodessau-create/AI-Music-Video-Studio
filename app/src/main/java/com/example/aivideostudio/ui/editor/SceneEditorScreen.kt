package com.example.aivideostudio.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aivideostudio.data.GenerationStatus
import com.example.aivideostudio.data.RenderMode
import com.example.aivideostudio.data.SceneEntity
import com.example.aivideostudio.util.FileCopyUtils

@Composable
fun SceneEditorScreen(
    projectId: String,
    onOpenPreview: (String) -> Unit,
    onOpenSettings: (String) -> Unit,
    viewModel: SceneEditorViewModel = viewModel()
) {
    val context = LocalContext.current

    LaunchedEffect(projectId) {
        viewModel.load(projectId)
    }

    val scenes by viewModel.scenes.collectAsState()
    val project by viewModel.project.collectAsState()
    val isComfyUiMode = project?.renderMode == RenderMode.COMFYUI.name

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.name ?: "Storyboard") },
                actions = {
                    IconButton(onClick = { onOpenSettings(projectId) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Projekteinstellungen")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (!isComfyUiMode) {
                Text(
                    text = "Tipp: Ohne Hintergrundbild und ohne Wörter wie \"Schnee\", \"Feuer\" oder \"Rauch\" im Prompt bleibt eine Szene bewusst schlicht. Lade pro Szene ein Bild hoch, damit sie sichtbaren Inhalt zeigt.",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(scenes.sortedBy { it.orderIndex }) { scene ->
                    SceneRow(
                        scene = scene,
                        isComfyUiMode = isComfyUiMode,
                        onPromptChange = { newPrompt -> viewModel.updateScenePrompt(scene, newPrompt) },
                        onDelete = { viewModel.deleteScene(scene) },
                        onDuplicate = { viewModel.duplicateScene(scene) },
                        onMoveUp = { viewModel.moveSceneUp(scene) },
                        onMoveDown = { viewModel.moveSceneDown(scene) },
                        onExtend = {
                            viewModel.updateSceneTiming(scene, scene.startTimeSeconds, scene.endTimeSeconds + 1.0)
                        },
                        onShorten = {
                            viewModel.updateSceneTiming(scene, scene.startTimeSeconds, scene.endTimeSeconds - 1.0)
                        },
                        onRegenerate = { viewModel.regenerateScene(scene, useNewSeed = false) },
                        onRegenerateWithNewSeed = { viewModel.regenerateScene(scene, useNewSeed = true) },
                        onPickBackgroundImage = { uri ->
                            val localPath = FileCopyUtils.copyUriToInternalStorage(context, uri, "scene_background_${scene.id}")
                            if (localPath != null) {
                                viewModel.updateSceneBackgroundImage(scene, localPath)
                            }
                        },
                        onClearBackgroundImage = { viewModel.clearSceneBackgroundImage(scene) }
                    )
                }
            }

            Button(
                onClick = { onOpenPreview(projectId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("Weiter zur Vorschau")
            }
        }
    }
}

@Composable
private fun SceneRow(
    scene: SceneEntity,
    isComfyUiMode: Boolean,
    onPromptChange: (String) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onExtend: () -> Unit,
    onShorten: () -> Unit,
    onRegenerate: () -> Unit,
    onRegenerateWithNewSeed: () -> Unit,
    onPickBackgroundImage: (android.net.Uri) -> Unit,
    onClearBackgroundImage: () -> Unit
) {
    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            onPickBackgroundImage(uri)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = scene.label)
            Text(text = "Start: ${"%.1f".format(scene.startTimeSeconds)}s  Ende: ${"%.1f".format(scene.endTimeSeconds)}s")

            if (isComfyUiMode) {
                Text(text = "Status: ${statusLabel(scene.generationStatus)}")
                if (scene.generationStatus == GenerationStatus.FAILED.name && scene.generationErrorMessage != null) {
                    Text(text = scene.generationErrorMessage)
                }
            } else {
                Text(text = if (scene.backgroundImagePath != null) "Hintergrundbild ausgewählt" else "Kein Hintergrundbild")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { imagePickerLauncher.launch("image/*") }) {
                        Text(if (scene.backgroundImagePath != null) "Bild ändern" else "Hintergrundbild wählen")
                    }
                    if (scene.backgroundImagePath != null) {
                        Button(onClick = onClearBackgroundImage) {
                            Text("Entfernen")
                        }
                    }
                }
            }

            OutlinedTextField(
                value = scene.prompt,
                onValueChange = onPromptChange,
                label = { Text("Szenen-Prompt") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onMoveUp) { Icon(Icons.Filled.ArrowUpward, contentDescription = "Nach oben") }
                IconButton(onClick = onMoveDown) { Icon(Icons.Filled.ArrowDownward, contentDescription = "Nach unten") }
                IconButton(onClick = onDuplicate) { Icon(Icons.Filled.ContentCopy, contentDescription = "Duplizieren") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Löschen") }
                Button(onClick = onExtend) { Text("+1s") }
                Button(onClick = onShorten) { Text("-1s") }
            }
            if (isComfyUiMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = onRegenerate) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Neu generieren")
                        Text(" Neu generieren")
                    }
                    Button(onClick = onRegenerateWithNewSeed) {
                        Text("Neu mit anderem Seed")
                    }
                }
            }
        }
    }
}

private fun statusLabel(status: String): String {
    return when (status) {
        GenerationStatus.NOT_GENERATED.name -> "Noch nicht generiert"
        GenerationStatus.GENERATING.name -> "Wird generiert"
        GenerationStatus.GENERATED.name -> "Fertig generiert"
        GenerationStatus.FAILED.name -> "Fehlgeschlagen"
        else -> status
    }
}

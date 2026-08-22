package com.example.aivideostudio.ui.editor

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aivideostudio.data.SceneEntity

@Composable
fun SceneEditorScreen(
    projectId: String,
    onOpenPreview: (String) -> Unit,
    viewModel: SceneEditorViewModel = viewModel()
) {
    LaunchedEffect(projectId) {
        viewModel.load(projectId)
    }

    val scenes by viewModel.scenes.collectAsState()
    val project by viewModel.project.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text(project?.name ?: "Storyboard") }) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(scenes.sortedBy { it.orderIndex }) { scene ->
                    SceneRow(
                        scene = scene,
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
                        }
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
    onPromptChange: (String) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onExtend: () -> Unit,
    onShorten: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = scene.label)
            Text(text = "Start: ${"%.1f".format(scene.startTimeSeconds)}s  Ende: ${"%.1f".format(scene.endTimeSeconds)}s")
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
        }
    }
}

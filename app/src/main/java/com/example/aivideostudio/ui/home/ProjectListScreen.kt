package com.example.aivideostudio.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aivideostudio.data.ProjectEntity

@Composable
fun ProjectListScreen(
    onCreateNewProject: () -> Unit,
    onOpenProject: (String) -> Unit,
    viewModel: ProjectListViewModel = viewModel()
) {
    val projects by viewModel.projects.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("AI Music Video Studio") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateNewProject) {
                Icon(Icons.Filled.Add, contentDescription = "Neues Projekt")
            }
        }
    ) { paddingValues ->
        if (projects.isEmpty()) {
            EmptyState(paddingValues)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(projects) { project ->
                    ProjectRow(
                        project = project,
                        onClick = { onOpenProject(project.id) },
                        onDelete = { viewModel.deleteProject(project) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(paddingValues: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentAlignment = Alignment.Center
    ) {
        Text("Noch kein Projekt. Erstelle dein erstes Musikvideo.")
    }
}

@Composable
private fun ProjectRow(project: ProjectEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.clickableColumn(onClick)) {
                    Text(text = project.name)
                    Text(text = "${project.aspectRatio} • ${project.resolutionWidth}x${project.resolutionHeight}")
                    Text(text = "BPM: ${project.bpm.toInt()}")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Löschen")
                }
            }
        }
    }
}

private fun Modifier.clickableColumn(onClick: () -> Unit): Modifier {
    return this.then(
        androidx.compose.foundation.clickable(onClick = onClick)
    )
}

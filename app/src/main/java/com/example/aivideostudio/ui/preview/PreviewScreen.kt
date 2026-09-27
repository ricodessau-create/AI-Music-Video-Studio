package com.example.aivideostudio.ui.preview

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

@Composable
fun PreviewScreen(projectId: String, onBack: () -> Unit, viewModel: PreviewViewModel = viewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(projectId) {
        viewModel.startRender(projectId)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Rendering & Vorschau") }) }) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val currentState = uiState) {
                is PreviewUiState.Idle -> {
                    Text("Bereite Rendern vor...")
                }
                is PreviewUiState.Rendering -> {
                    Text(currentState.sceneLabel)
                    LinearProgressIndicator(
                        progress = { currentState.percent / 100f },
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text("${currentState.percent}%")
                    Text("Das Rendern läuft im Hintergrund weiter, auch wenn du die App minimierst.")
                }
                is PreviewUiState.Failed -> {
                    Text(text = currentState.message)
                    Button(onClick = { viewModel.retryRender(projectId) }) {
                        Text("Erneut versuchen")
                    }
                }
                is PreviewUiState.Completed -> {
                    Text("Video fertig gestellt.")
                    Button(onClick = {
                        val file = File(currentState.outputPath)
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                        val intent = Intent(Intent.ACTION_VIEW)
                        intent.setDataAndType(uri, "video/mp4")
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(intent)
                    }) {
                        Text("Video abspielen")
                    }
                    Button(onClick = {
                        val file = File(currentState.outputPath)
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                        val shareIntent = Intent(Intent.ACTION_SEND)
                        shareIntent.type = "video/mp4"
                        shareIntent.putExtra(Intent.EXTRA_STREAM, uri)
                        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(shareIntent, "Video teilen"))
                    }) {
                        Text("Video teilen")
                    }
                }
            }

            Button(onClick = onBack) {
                Text("Zurück zum Storyboard")
            }
        }
    }
}

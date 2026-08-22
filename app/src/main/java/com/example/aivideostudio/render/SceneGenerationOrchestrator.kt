package com.example.aivideostudio.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.aivideostudio.comfyui.ComfyUiClient
import com.example.aivideostudio.comfyui.ComfyUiConnectionConfig
import com.example.aivideostudio.comfyui.ComfyUiDownloadResult
import com.example.aivideostudio.comfyui.ComfyUiPollResult
import com.example.aivideostudio.comfyui.ComfyUiSubmitResult
import com.example.aivideostudio.comfyui.StoredWorkflow
import com.example.aivideostudio.comfyui.WorkflowMediaType
import com.example.aivideostudio.comfyui.WorkflowParameterFiller
import com.example.aivideostudio.comfyui.WorkflowParameters
import com.example.aivideostudio.comfyui.WorkflowRepository
import com.example.aivideostudio.data.GeneratedMediaType
import com.example.aivideostudio.data.SceneEntity
import java.io.File

sealed class SceneGenerationResult {
    data class Success(val filePath: String, val mediaType: GeneratedMediaType) : SceneGenerationResult()
    data class Failure(val message: String) : SceneGenerationResult()
}

class SceneGenerationOrchestrator(
    private val context: Context,
    private val comfyUiBaseUrl: String
) {

    private val workflowRepository = WorkflowRepository()
    private val parameterFiller = WorkflowParameterFiller()
    private val client = ComfyUiClient(ComfyUiConnectionConfig(baseUrl = comfyUiBaseUrl))

    suspend fun checkConnection(): Boolean {
        return client.checkAvailability()
    }

    suspend fun generateScene(
        scene: SceneEntity,
        workflow: StoredWorkflow,
        negativePrompt: String,
        width: Int,
        height: Int,
        characterReferenceImagePath: String?,
        onStatusUpdate: (String) -> Unit
    ): SceneGenerationResult {
        onStatusUpdate("Verbindung wird geprüft")
        val isAvailable = client.checkAvailability()
        if (!isAvailable) {
            return SceneGenerationResult.Failure("ComfyUI-Server nicht erreichbar.")
        }

        val rawWorkflowJson = workflowRepository.loadWorkflowRawJson(context, workflow)
        if (rawWorkflowJson.isBlank()) {
            return SceneGenerationResult.Failure("KI-Generierung fehlgeschlagen.")
        }

        val referenceImageBase64 = if (workflow.supportsReferenceImage && characterReferenceImagePath != null) {
            encodeImageToBase64(characterReferenceImagePath)
        } else {
            null
        }

        val parameters = WorkflowParameters(
            prompt = scene.prompt,
            negativePrompt = negativePrompt,
            seed = scene.seed,
            width = width,
            height = height,
            steps = 20,
            cfg = 7.0f,
            frames = estimateFrameCount(scene),
            fps = 24,
            referenceImageBase64 = referenceImageBase64
        )

        val filledWorkflowJson = parameterFiller.fill(rawWorkflowJson, parameters)

        onStatusUpdate("Wird an ComfyUI gesendet")
        val submitResult = client.submitWorkflow(filledWorkflowJson)
        val promptId = when (submitResult) {
            is ComfyUiSubmitResult.Success -> submitResult.promptId
            is ComfyUiSubmitResult.Failure -> return SceneGenerationResult.Failure(submitResult.message)
        }

        onStatusUpdate("Warten auf ComfyUI")
        val pollResult = client.waitForCompletion(promptId)
        val outputInfo = when (pollResult) {
            is ComfyUiPollResult.Success -> pollResult
            is ComfyUiPollResult.Failure -> return SceneGenerationResult.Failure(pollResult.message)
            is ComfyUiPollResult.Pending -> return SceneGenerationResult.Failure("KI-Generierung fehlgeschlagen.")
        }

        onStatusUpdate("Ausgabedatei wird heruntergeladen")
        val outputFileName = outputInfo.outputFileNames.first()
        val targetDirectory = File(context.filesDir, "generated_media")
        val downloadResult = client.downloadOutput(
            fileName = outputFileName,
            subfolder = outputInfo.outputSubfolder,
            type = outputInfo.outputType,
            targetDirectory = targetDirectory
        )

        return when (downloadResult) {
            is ComfyUiDownloadResult.Success -> {
                val mediaType = if (workflow.mediaType == WorkflowMediaType.VIDEO) {
                    GeneratedMediaType.VIDEO
                } else {
                    GeneratedMediaType.IMAGE
                }
                SceneGenerationResult.Success(downloadResult.localFilePath, mediaType)
            }
            is ComfyUiDownloadResult.Failure -> SceneGenerationResult.Failure(downloadResult.message)
        }
    }

    private fun estimateFrameCount(scene: SceneEntity): Int {
        val durationSeconds = scene.endTimeSeconds - scene.startTimeSeconds
        return (durationSeconds * 24.0).toInt().coerceAtLeast(24)
    }

    private fun encodeImageToBase64(path: String): String? {
        return try {
            val bitmap = BitmapFactory.decodeFile(path) ?: return null
            val outputStream = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (exception: Exception) {
            null
        }
    }
}

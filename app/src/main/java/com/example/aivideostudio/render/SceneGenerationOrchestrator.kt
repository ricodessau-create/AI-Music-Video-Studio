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
import com.example.aivideostudio.comfyui.ComfyUiUploadResult
import com.example.aivideostudio.comfyui.StoredWorkflow
import com.example.aivideostudio.comfyui.WorkflowMediaType
import com.example.aivideostudio.comfyui.WorkflowParameterFiller
import com.example.aivideostudio.comfyui.WorkflowParameters
import com.example.aivideostudio.comfyui.WorkflowPlaceholders
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
            return SceneGenerationResult.Failure("KI-Generierung fehlgeschlagen: Workflow-Datei leer oder nicht gefunden.")
        }

        val usesBase64Placeholder = rawWorkflowJson.contains(WorkflowPlaceholders.REFERENCE_IMAGE)
        val usesNamePlaceholder = rawWorkflowJson.contains(WorkflowPlaceholders.REFERENCE_IMAGE_NAME)
        val workflowUsesReference = usesBase64Placeholder || usesNamePlaceholder

        if (workflowUsesReference && characterReferenceImagePath == null) {
            return SceneGenerationResult.Failure("Dieser Workflow braucht ein Bild der Band oder des Interpreten. Bitte im Projekt oder in der Szene ein Bild auswählen.")
        }

        if (!workflowUsesReference && characterReferenceImagePath != null) {
            onStatusUpdate("Bild vorhanden, aber der Workflow enthält keinen Bild-Platzhalter – es wird ignoriert")
        }

        var referenceImageBase64: String? = null
        var referenceImageName: String? = null

        if (workflowUsesReference && characterReferenceImagePath != null) {
            if (usesBase64Placeholder) {
                referenceImageBase64 = encodeImageToBase64(characterReferenceImagePath)
                    ?: return SceneGenerationResult.Failure("Referenzbild konnte nicht gelesen/kodiert werden: $characterReferenceImagePath")
            }

            if (usesNamePlaceholder) {
                onStatusUpdate("Bild wird zu ComfyUI hochgeladen")
                val payload = readUploadPayload(characterReferenceImagePath)
                    ?: return SceneGenerationResult.Failure("Referenzbild konnte nicht gelesen werden: $characterReferenceImagePath")
                val uploadResult = client.uploadImage(payload.first, payload.second)
                referenceImageName = when (uploadResult) {
                    is ComfyUiUploadResult.Success -> uploadResult.imageName
                    is ComfyUiUploadResult.Failure -> return SceneGenerationResult.Failure(uploadResult.message)
                }
            }
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
            referenceImageBase64 = referenceImageBase64,
            referenceImageName = referenceImageName
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
            is ComfyUiPollResult.Pending -> return SceneGenerationResult.Failure("KI-Generierung fehlgeschlagen: Zeitüberschreitung beim Warten auf ComfyUI.")
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

    private fun readUploadPayload(path: String): Pair<String, ByteArray>? {
        return try {
            val file = File(path)
            if (!file.exists() || file.length() <= 0L) {
                return null
            }
            val bytes = file.readBytes()
            val isPng = bytes.size > 4 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte()
            val isJpeg = bytes.size > 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte()
            val baseName = file.nameWithoutExtension
            when {
                isPng -> Pair("band_$baseName.png", bytes)
                isJpeg -> Pair("band_$baseName.jpg", bytes)
                else -> {
                    val pngBytes = encodeImageToPngBytes(path) ?: return null
                    Pair("band_$baseName.png", pngBytes)
                }
            }
        } catch (exception: Exception) {
            null
        }
    }

    private fun encodeImageToPngBytes(path: String): ByteArray? {
        return try {
            val bitmap = BitmapFactory.decodeFile(path) ?: return null
            val outputStream = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            outputStream.toByteArray()
        } catch (exception: Exception) {
            null
        }
    }

    private fun encodeImageToBase64(path: String): String? {
        val pngBytes = encodeImageToPngBytes(path) ?: return null
        return Base64.encodeToString(pngBytes, Base64.NO_WRAP)
    }
}

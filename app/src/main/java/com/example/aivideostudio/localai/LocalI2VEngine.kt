package com.example.aivideostudio.localai

import android.content.Context
import java.io.File

data class LocalI2VGenerationRequest(
    val referenceImagePath: String,
    val prompt: String,
    val negativePrompt: String,
    val outputFile: File,
    val width: Int,
    val height: Int,
    val frameRate: Int = 24,
    val frames: Int = 17,
    val diffusionSteps: Int = 2,
    val seed: Long = 0L
)

data class LocalI2VGenerationProgress(
    val stage: String,
    val progress: Float
)

class LocalI2VEngine(
    context: Context
) {

    private val modelStore =
        LocalI2VModelStore(context.applicationContext)

    suspend fun generate(
        request: LocalI2VGenerationRequest,
        onProgress: (LocalI2VGenerationProgress) -> Unit = {}
    ): File {
        validateRequest(request)

        if (!modelStore.areAllModelsInstalled()) {
            throw IllegalStateException(
                buildMissingModelMessage()
            )
        }

        throw IllegalStateException(
            "Die lokale MobileI2V-Engine ist noch nicht freigegeben. " +
                "Die vorhandenen ONNX-Dateien müssen zuerst mit den echten " +
                "MobileI2V-Tensorverträgen konvertiert und validiert werden. " +
                "Es wird absichtlich keine simulierte oder geratene " +
                "Diffusionsberechnung ausgeführt."
        )
    }

    private fun validateRequest(
        request: LocalI2VGenerationRequest
    ) {
        require(request.referenceImagePath.isNotBlank()) {
            "Kein Referenzbild angegeben."
        }

        val referenceFile =
            File(request.referenceImagePath)

        require(
            referenceFile.exists() &&
                referenceFile.isFile &&
                referenceFile.length() > 0L
        ) {
            "Referenzbild nicht gefunden: ${request.referenceImagePath}"
        }

        require(request.width > 0) {
            "Ungültige Videobreite."
        }

        require(request.height > 0) {
            "Ungültige Videohöhe."
        }

        require(request.frameRate > 0) {
            "Ungültige Bildrate."
        }

        require(request.frames > 0) {
            "Ungültige Frame-Anzahl."
        }
    }

    private fun buildMissingModelMessage(): String {
        val missing =
            modelStore.getMissingModels()

        return buildString {
            append(
                "Die lokale Bild-zu-Video-KI ist noch nicht vollständig installiert.\n\n"
            )

            append("Fehlende Modelle:\n")

            missing.forEach { model ->
                append("• ")
                append(model)
                append('\n')
            }

            append("\nModellordner:\n")
            append(
                modelStore
                    .getDirectory()
                    .absolutePath
            )

            append(
                "\n\nDie Generierung erfolgt später vollständig lokal " +
                    "ohne ComfyUI."
            )
        }
    }
}
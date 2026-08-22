package com.example.aivideostudio.comfyui

data class WorkflowParameters(
    val prompt: String,
    val negativePrompt: String,
    val seed: Long,
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfg: Float,
    val frames: Int,
    val fps: Int,
    val referenceImageBase64: String?
)

class WorkflowParameterFiller {

    fun fill(rawWorkflowJson: String, parameters: WorkflowParameters): String {
        var result = rawWorkflowJson

        result = result.replace(WorkflowPlaceholders.PROMPT, escapeJson(parameters.prompt))
        result = result.replace(WorkflowPlaceholders.NEGATIVE_PROMPT, escapeJson(parameters.negativePrompt))
        result = result.replace(WorkflowPlaceholders.SEED, parameters.seed.toString())
        result = result.replace(WorkflowPlaceholders.WIDTH, parameters.width.toString())
        result = result.replace(WorkflowPlaceholders.HEIGHT, parameters.height.toString())
        result = result.replace(WorkflowPlaceholders.STEPS, parameters.steps.toString())
        result = result.replace(WorkflowPlaceholders.CFG, parameters.cfg.toString())
        result = result.replace(WorkflowPlaceholders.FRAMES, parameters.frames.toString())
        result = result.replace(WorkflowPlaceholders.FPS, parameters.fps.toString())

        if (parameters.referenceImageBase64 != null) {
            result = result.replace(WorkflowPlaceholders.REFERENCE_IMAGE, escapeJson(parameters.referenceImageBase64))
        }

        return result
    }

    private fun escapeJson(text: String): String {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
    }
}

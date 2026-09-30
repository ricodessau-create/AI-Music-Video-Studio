package com.example.aivideostudio.comfyui

enum class WorkflowMediaType {
    IMAGE,
    VIDEO
}

data class StoredWorkflow(
    val id: String,
    val name: String,
    val mediaType: WorkflowMediaType,
    val workflowFileName: String,
    val supportsNegativePrompt: Boolean,
    val supportsSeed: Boolean,
    val supportsWidthHeight: Boolean,
    val supportsSteps: Boolean,
    val supportsCfg: Boolean,
    val supportsFrames: Boolean,
    val supportsFps: Boolean,
    val supportsReferenceImage: Boolean
)

object WorkflowPlaceholders {
    const val PROMPT = "{{PROMPT}}"
    const val NEGATIVE_PROMPT = "{{NEGATIVE_PROMPT}}"
    const val SEED = "{{SEED}}"
    const val WIDTH = "{{WIDTH}}"
    const val HEIGHT = "{{HEIGHT}}"
    const val STEPS = "{{STEPS}}"
    const val CFG = "{{CFG}}"
    const val FRAMES = "{{FRAMES}}"
    const val FPS = "{{FPS}}"
    const val REFERENCE_IMAGE = "{{REFERENCE_IMAGE}}"
    const val REFERENCE_IMAGE_NAME = "{{REFERENCE_IMAGE_NAME}}"
}

package com.example.aivideostudio.comfyui

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

class WorkflowRepository {

    private val fieldDelimiter = "\u001F"
    private val lineDelimiter = "\u001E"

    fun importWorkflow(
        context: Context,
        sourceUri: Uri,
        name: String,
        mediaType: WorkflowMediaType
    ): StoredWorkflow? {
        return try {
            val inputStream = context.contentResolver.openInputStream(sourceUri) ?: return null
            val rawJson = inputStream.bufferedReader().use { it.readText() }
            inputStream.close()

            val id = UUID.randomUUID().toString()
            val workflowsDirectory = getWorkflowsDirectory(context)
            val fileName = "$id.json"
            val targetFile = File(workflowsDirectory, fileName)
            targetFile.writeText(rawJson)

            val storedWorkflow = StoredWorkflow(
                id = id,
                name = name,
                mediaType = mediaType,
                workflowFileName = fileName,
                supportsNegativePrompt = rawJson.contains(WorkflowPlaceholders.NEGATIVE_PROMPT),
                supportsSeed = rawJson.contains(WorkflowPlaceholders.SEED),
                supportsWidthHeight = rawJson.contains(WorkflowPlaceholders.WIDTH) && rawJson.contains(WorkflowPlaceholders.HEIGHT),
                supportsSteps = rawJson.contains(WorkflowPlaceholders.STEPS),
                supportsCfg = rawJson.contains(WorkflowPlaceholders.CFG),
                supportsFrames = rawJson.contains(WorkflowPlaceholders.FRAMES),
                supportsFps = rawJson.contains(WorkflowPlaceholders.FPS),
                supportsReferenceImage = rawJson.contains(WorkflowPlaceholders.REFERENCE_IMAGE) ||
                    rawJson.contains(WorkflowPlaceholders.REFERENCE_IMAGE_NAME)
            )

            appendToManifest(context, storedWorkflow)
            storedWorkflow
        } catch (exception: Exception) {
            null
        }
    }

    fun listWorkflows(context: Context): List<StoredWorkflow> {
        val manifestFile = getManifestFile(context)
        if (!manifestFile.exists()) return emptyList()
        val content = manifestFile.readText()
        if (content.isBlank()) return emptyList()
        return content.split(lineDelimiter).filter { it.isNotBlank() }.mapNotNull { line ->
            parseManifestLine(line)
        }
    }

    fun loadWorkflowRawJson(context: Context, workflow: StoredWorkflow): String {
        val file = File(getWorkflowsDirectory(context), workflow.workflowFileName)
        return if (file.exists()) file.readText() else ""
    }

    fun deleteWorkflow(context: Context, workflow: StoredWorkflow) {
        val file = File(getWorkflowsDirectory(context), workflow.workflowFileName)
        if (file.exists()) {
            file.delete()
        }
        val remaining = listWorkflows(context).filter { it.id != workflow.id }
        rewriteManifest(context, remaining)
    }

    private fun getWorkflowsDirectory(context: Context): File {
        val directory = File(context.filesDir, "workflows")
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return directory
    }

    private fun getManifestFile(context: Context): File {
        return File(getWorkflowsDirectory(context), "manifest.txt")
    }

    private fun appendToManifest(context: Context, workflow: StoredWorkflow) {
        val manifestFile = getManifestFile(context)
        val line = encodeManifestLine(workflow)
        manifestFile.appendText(line + lineDelimiter)
    }

    private fun rewriteManifest(context: Context, workflows: List<StoredWorkflow>) {
        val manifestFile = getManifestFile(context)
        val content = workflows.joinToString(separator = lineDelimiter) { encodeManifestLine(it) }
        manifestFile.writeText(content)
    }

    private fun encodeManifestLine(workflow: StoredWorkflow): String {
        return listOf(
            workflow.id,
            workflow.name,
            workflow.mediaType.name,
            workflow.workflowFileName,
            workflow.supportsNegativePrompt.toString(),
            workflow.supportsSeed.toString(),
            workflow.supportsWidthHeight.toString(),
            workflow.supportsSteps.toString(),
            workflow.supportsCfg.toString(),
            workflow.supportsFrames.toString(),
            workflow.supportsFps.toString(),
            workflow.supportsReferenceImage.toString()
        ).joinToString(separator = fieldDelimiter)
    }

    private fun parseManifestLine(line: String): StoredWorkflow? {
        val parts = line.split(fieldDelimiter)
        if (parts.size < 12) return null
        return try {
            StoredWorkflow(
                id = parts[0],
                name = parts[1],
                mediaType = WorkflowMediaType.valueOf(parts[2]),
                workflowFileName = parts[3],
                supportsNegativePrompt = parts[4].toBoolean(),
                supportsSeed = parts[5].toBoolean(),
                supportsWidthHeight = parts[6].toBoolean(),
                supportsSteps = parts[7].toBoolean(),
                supportsCfg = parts[8].toBoolean(),
                supportsFrames = parts[9].toBoolean(),
                supportsFps = parts[10].toBoolean(),
                supportsReferenceImage = parts[11].toBoolean()
            )
        } catch (exception: Exception) {
            null
        }
    }
}

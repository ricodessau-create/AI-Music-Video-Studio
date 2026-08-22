package com.example.aivideostudio.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val songFilePath: String,
    val songDurationSeconds: Double,
    val bpm: Double,
    val visualPrompt: String,
    val resolutionWidth: Int,
    val resolutionHeight: Int,
    val aspectRatio: String,
    val renderMode: String,
    val comfyUiBaseUrl: String?,
    val globalVisualStyle: String,
    val negativePrompt: String,
    val characterReferenceImagePath: String?,
    val selectedWorkflowId: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long
)

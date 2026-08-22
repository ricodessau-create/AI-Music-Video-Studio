package com.example.aivideostudio.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scenes")
data class SceneEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val orderIndex: Int,
    val label: String,
    val startTimeSeconds: Double,
    val endTimeSeconds: Double,
    val prompt: String,
    val backgroundImagePath: String?,
    val cameraMovement: String,
    val transitionType: String,
    val effectsJson: String,
    val intensity: Float,
    val seed: Long,
    val generationStatus: String,
    val generatedMediaPath: String?,
    val generatedMediaType: String,
    val generationErrorMessage: String?
)

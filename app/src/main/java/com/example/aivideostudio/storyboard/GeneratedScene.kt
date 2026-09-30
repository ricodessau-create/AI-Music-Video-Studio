package com.example.aivideostudio.storyboard

data class GeneratedScene(
    val id: String,
    val orderIndex: Int,
    val label: String,
    val startTimeSeconds: Double,
    val endTimeSeconds: Double,
    val prompt: String,
    val cameraMovement: String,
    val transitionType: String,
    val effects: List<String>,
    val intensity: Float,
    val seed: Long
)

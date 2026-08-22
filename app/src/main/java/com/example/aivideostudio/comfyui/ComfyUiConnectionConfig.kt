package com.example.aivideostudio.comfyui

data class ComfyUiConnectionConfig(
    val baseUrl: String,
    val timeoutSeconds: Long = 120
)

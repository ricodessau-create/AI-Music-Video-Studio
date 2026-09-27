package com.example.aivideostudio.huggingface

data class HuggingFaceRequestParameters(
    val prompt: String,
    val negativePrompt: String,
    val numFrames: Int,
    val fps: Int,
    val width: Int,
    val height: Int
)

sealed class HuggingFaceVideoResult {
    data class Success(val localFilePath: String) : HuggingFaceVideoResult()
    object ModelLoading : HuggingFaceVideoResult()
    data class Failure(val message: String) : HuggingFaceVideoResult()
}

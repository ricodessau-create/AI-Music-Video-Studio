package com.example.aivideostudio.comfyui

sealed class ComfyUiSubmitResult {
    data class Success(val promptId: String) : ComfyUiSubmitResult()
    data class Failure(val message: String) : ComfyUiSubmitResult()
}

sealed class ComfyUiPollResult {
    object Pending : ComfyUiPollResult()
    data class Success(val outputFileNames: List<String>, val outputSubfolder: String, val outputType: String) : ComfyUiPollResult()
    data class Failure(val message: String) : ComfyUiPollResult()
}

sealed class ComfyUiDownloadResult {
    data class Success(val localFilePath: String) : ComfyUiDownloadResult()
    data class Failure(val message: String) : ComfyUiDownloadResult()
}

sealed class ComfyUiUploadResult {
    data class Success(val imageName: String) : ComfyUiUploadResult()
    data class Failure(val message: String) : ComfyUiUploadResult()
}

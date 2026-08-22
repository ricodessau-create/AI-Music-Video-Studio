package com.example.aivideostudio.comfyui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class ComfyUiConnectionConfig(
    val baseUrl: String,
    val timeoutSeconds: Long = 60
)

sealed class ComfyUiResult {
    data class Success(val promptId: String, val rawResponse: String) : ComfyUiResult()
    data class Failure(val message: String) : ComfyUiResult()
}

class ComfyUiClient(private val config: ComfyUiConnectionConfig) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .build()

    suspend fun submitWorkflow(workflowJson: String): ComfyUiResult = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl("/prompt")
            val body = workflowJson.toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ComfyUiResult.Failure("ComfyUI-Workflow fehlgeschlagen")
                }
                val text = response.body?.string().orEmpty()
                val promptId = extractPromptId(text)
                ComfyUiResult.Success(promptId, text)
            }
        } catch (exception: Exception) {
            ComfyUiResult.Failure("KI-Server nicht erreichbar")
        }
    }

    suspend fun checkHistory(promptId: String): ComfyUiResult = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl("/history/$promptId")
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ComfyUiResult.Failure("ComfyUI-Workflow fehlgeschlagen")
                }
                val text = response.body?.string().orEmpty()
                ComfyUiResult.Success(promptId, text)
            }
        } catch (exception: Exception) {
            ComfyUiResult.Failure("KI-Server nicht erreichbar")
        }
    }

    suspend fun checkAvailability(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl("/system_stats")
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (exception: Exception) {
            false
        }
    }

    private fun buildUrl(path: String): String {
        val trimmedBase = config.baseUrl.trimEnd('/')
        return "$trimmedBase$path"
    }

    private fun extractPromptId(rawJson: String): String {
        val marker = "\"prompt_id\""
        val markerIndex = rawJson.indexOf(marker)
        if (markerIndex < 0) return ""
        val colonIndex = rawJson.indexOf(':', markerIndex)
        if (colonIndex < 0) return ""
        val quoteStart = rawJson.indexOf('"', colonIndex + 1)
        val quoteEnd = rawJson.indexOf('"', quoteStart + 1)
        if (quoteStart < 0 || quoteEnd < 0) return ""
        return rawJson.substring(quoteStart + 1, quoteEnd)
    }
}

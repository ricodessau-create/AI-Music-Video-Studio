package com.example.aivideostudio.huggingface

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class HuggingFaceClient(
    private val apiToken: String,
    private val modelId: String
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun generateVideoClip(
        parameters: HuggingFaceRequestParameters,
        targetFile: File
    ): HuggingFaceVideoResult = withContext(Dispatchers.IO) {
        try {
            val escapedPrompt = escapeJson(parameters.prompt)
            val payload = """
                {
                  "inputs": "$escapedPrompt",
                  "parameters": {
                    "num_frames": ${parameters.numFrames},
                    "fps": ${parameters.fps},
                    "width": ${parameters.width},
                    "height": ${parameters.height}
                  },
                  "options": {
                    "wait_for_model": false
                  }
                }
            """.trimIndent()

            val requestBody = payload.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://api-inference.huggingface.co/models/$modelId")
                .addHeader("Authorization", "Bearer $apiToken")
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type") ?: ""

                if (response.code == 503) {
                    return@withContext HuggingFaceVideoResult.ModelLoading
                }

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    return@withContext HuggingFaceVideoResult.Failure(
                        "Hugging-Face-Anfrage fehlgeschlagen (${response.code}): ${errorBody.take(200)}"
                    )
                }

                if (contentType.contains("video") || contentType.contains("octet-stream")) {
                    val body = response.body ?: return@withContext HuggingFaceVideoResult.Failure(
                        "Hugging Face hat keine Videodaten geliefert."
                    )
                    if (targetFile.parentFile?.exists() != true) {
                        targetFile.parentFile?.mkdirs()
                    }
                    FileOutputStream(targetFile).use { output ->
                        body.byteStream().copyTo(output)
                    }
                    return@withContext HuggingFaceVideoResult.Success(targetFile.absolutePath)
                }

                val textBody = response.body?.string().orEmpty()
                if (textBody.contains("\"estimated_time\"")) {
                    HuggingFaceVideoResult.ModelLoading
                } else {
                    HuggingFaceVideoResult.Failure("Unerwartete Antwort von Hugging Face: ${textBody.take(200)}")
                }
            }
        } catch (exception: Exception) {
            HuggingFaceVideoResult.Failure(exception.message ?: "Hugging-Face-Server nicht erreichbar.")
        }
    }

    suspend fun generateVideoClipWithRetry(
        parameters: HuggingFaceRequestParameters,
        targetFile: File,
        maxAttempts: Int = 6,
        waitMillisOnLoading: Long = 20000
    ): HuggingFaceVideoResult {
        repeat(maxAttempts) {
            val result = generateVideoClip(parameters, targetFile)
            if (result !is HuggingFaceVideoResult.ModelLoading) {
                return result
            }
            kotlinx.coroutines.delay(waitMillisOnLoading)
        }
        return HuggingFaceVideoResult.Failure("Hugging-Face-Modell war nach mehreren Versuchen weiterhin am Laden. Bitte später erneut versuchen.")
    }

    private fun escapeJson(text: String): String {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
    }
}

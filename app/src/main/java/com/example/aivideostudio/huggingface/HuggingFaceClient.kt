package com.example.aivideostudio.huggingface

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
            val payload = buildJsonObject {
                put("inputs", parameters.prompt)
                put("parameters", buildJsonObject {
                    put("num_frames", parameters.numFrames)
                    put("negative_prompt", parameters.negativePrompt)
                })
            }.toString()

            val requestBody = payload.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://router.huggingface.co/hf-inference/models/$modelId")
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
                        "Hugging-Face-Anfrage fehlgeschlagen (${response.code}) für Modell \"$modelId\" über hf-inference: ${errorBody.take(300)}"
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
                    HuggingFaceVideoResult.Failure(
                        "Unerwartete Antwort von Hugging Face für Modell \"$modelId\" (evtl. nicht über hf-inference verfügbar, sondern nur über fal-ai/replicate): ${textBody.take(300)}"
                    )
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
}

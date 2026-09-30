package com.example.aivideostudio.comfyui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ComfyUiClient(private val config: ComfyUiConnectionConfig) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .build()

    suspend fun checkAvailability(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl("/system_stats")
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (exception: Exception) {
            false
        }
    }

    suspend fun uploadImage(fileName: String, imageBytes: ByteArray): ComfyUiUploadResult = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl("/upload/image")
            val mimeType = if (fileName.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", fileName, imageBytes.toRequestBody(mimeType.toMediaType()))
                .addFormDataPart("type", "input")
                .addFormDataPart("overwrite", "true")
                .build()
            val request = Request.Builder().url(url).post(body).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ComfyUiUploadResult.Failure("Bild-Upload zu ComfyUI fehlgeschlagen (${response.code}).")
                }
                val text = response.body?.string().orEmpty()
                val name = extractStringField(text, "name")
                val subfolder = extractStringField(text, "subfolder")
                if (name.isBlank()) {
                    ComfyUiUploadResult.Failure("Bild-Upload zu ComfyUI fehlgeschlagen: Keine Dateibezeichnung erhalten.")
                } else {
                    ComfyUiUploadResult.Success(if (subfolder.isBlank()) name else "$subfolder/$name")
                }
            }
        } catch (exception: Exception) {
            ComfyUiUploadResult.Failure("Bild-Upload zu ComfyUI fehlgeschlagen: Server nicht erreichbar.")
        }
    }

    suspend fun submitWorkflow(workflowJson: String): ComfyUiSubmitResult = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl("/prompt")
            val payload = "{\"prompt\":$workflowJson}"
            val body = payload.toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ComfyUiSubmitResult.Failure("KI-Generierung fehlgeschlagen.")
                }
                val text = response.body?.string().orEmpty()
                val promptId = extractStringField(text, "prompt_id")
                if (promptId.isBlank()) {
                    ComfyUiSubmitResult.Failure("KI-Generierung fehlgeschlagen.")
                } else {
                    ComfyUiSubmitResult.Success(promptId)
                }
            }
        } catch (exception: Exception) {
            ComfyUiSubmitResult.Failure("ComfyUI-Server nicht erreichbar.")
        }
    }

    suspend fun pollHistory(promptId: String): ComfyUiPollResult = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl("/history/$promptId")
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ComfyUiPollResult.Pending
                }
                val text = response.body?.string().orEmpty()
                if (text.isBlank() || text == "{}") {
                    return@withContext ComfyUiPollResult.Pending
                }
                if (!text.contains("\"outputs\"")) {
                    return@withContext ComfyUiPollResult.Pending
                }
                val fileNames = extractAllStringFields(text, "filename")
                val subfolder = extractStringField(text, "subfolder")
                val type = extractStringField(text, "type").ifBlank { "output" }
                if (fileNames.isEmpty()) {
                    ComfyUiPollResult.Failure("ComfyUI hat keine Ausgabedatei geliefert.")
                } else {
                    ComfyUiPollResult.Success(fileNames, subfolder, type)
                }
            }
        } catch (exception: Exception) {
            ComfyUiPollResult.Pending
        }
    }

    suspend fun downloadOutput(
        fileName: String,
        subfolder: String,
        type: String,
        targetDirectory: File
    ): ComfyUiDownloadResult = withContext(Dispatchers.IO) {
        try {
            val encodedFileName = java.net.URLEncoder.encode(fileName, "UTF-8")
            val encodedSubfolder = java.net.URLEncoder.encode(subfolder, "UTF-8")
            val encodedType = java.net.URLEncoder.encode(type, "UTF-8")
            val url = buildUrl("/view?filename=$encodedFileName&subfolder=$encodedSubfolder&type=$encodedType")
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ComfyUiDownloadResult.Failure("ComfyUI hat keine Ausgabedatei geliefert.")
                }
                val body = response.body ?: return@withContext ComfyUiDownloadResult.Failure("ComfyUI hat keine Ausgabedatei geliefert.")
                if (!targetDirectory.exists()) {
                    targetDirectory.mkdirs()
                }
                val targetFile = File(targetDirectory, fileName)
                FileOutputStream(targetFile).use { output ->
                    body.byteStream().copyTo(output)
                }
                ComfyUiDownloadResult.Success(targetFile.absolutePath)
            }
        } catch (exception: Exception) {
            ComfyUiDownloadResult.Failure("ComfyUI hat keine Ausgabedatei geliefert.")
        }
    }

    suspend fun waitForCompletion(
        promptId: String,
        maxAttempts: Int = 180,
        delayMillisBetweenAttempts: Long = 2000
    ): ComfyUiPollResult {
        repeat(maxAttempts) {
            val result = pollHistory(promptId)
            if (result !is ComfyUiPollResult.Pending) {
                return result
            }
            kotlinx.coroutines.delay(delayMillisBetweenAttempts)
        }
        return ComfyUiPollResult.Failure("KI-Generierung fehlgeschlagen.")
    }

    private fun buildUrl(path: String): String {
        val trimmedBase = config.baseUrl.trimEnd('/')
        return "$trimmedBase$path"
    }

    private fun extractStringField(rawJson: String, fieldName: String): String {
        val marker = "\"$fieldName\""
        val markerIndex = rawJson.indexOf(marker)
        if (markerIndex < 0) return ""
        val colonIndex = rawJson.indexOf(':', markerIndex)
        if (colonIndex < 0) return ""
        val quoteStart = rawJson.indexOf('"', colonIndex + 1)
        val quoteEnd = rawJson.indexOf('"', quoteStart + 1)
        if (quoteStart < 0 || quoteEnd < 0) return ""
        return rawJson.substring(quoteStart + 1, quoteEnd)
    }

    private fun extractAllStringFields(rawJson: String, fieldName: String): List<String> {
        val marker = "\"$fieldName\""
        val results = ArrayList<String>()
        var searchIndex = 0
        while (true) {
            val markerIndex = rawJson.indexOf(marker, searchIndex)
            if (markerIndex < 0) break
            val colonIndex = rawJson.indexOf(':', markerIndex)
            if (colonIndex < 0) break
            val quoteStart = rawJson.indexOf('"', colonIndex + 1)
            val quoteEnd = rawJson.indexOf('"', quoteStart + 1)
            if (quoteStart < 0 || quoteEnd < 0) break
            val value = rawJson.substring(quoteStart + 1, quoteEnd)
            if (value.isNotBlank()) {
                results.add(value)
            }
            searchIndex = quoteEnd + 1
        }
        return results
    }
}

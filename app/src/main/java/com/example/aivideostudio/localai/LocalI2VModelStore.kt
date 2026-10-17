package com.example.aivideostudio.localai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class LocalI2VModelFile(
    val fileName: String,
    val downloadUrl: String,
    val expectedSha256: String?,
    val approxSizeBytes: Long
)

sealed class ModelDownloadProgress {
    data class Downloading(val fileName: String, val fileIndex: Int, val totalFiles: Int, val percentOfFile: Int) : ModelDownloadProgress()
    data class Verifying(val fileName: String) : ModelDownloadProgress()
    data class Failed(val message: String) : ModelDownloadProgress()
    object Completed : ModelDownloadProgress()
}

class LocalI2VModelStore(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    private val requiredModels = listOf(
        LocalI2VModelFile(
            fileName = "vae_encoder.onnx",
            downloadUrl = "",
            expectedSha256 = null,
            approxSizeBytes = 50L * 1024 * 1024
        ),
        LocalI2VModelFile(
            fileName = "qwen2_encoder.onnx",
            downloadUrl = "",
            expectedSha256 = null,
            approxSizeBytes = 900L * 1024 * 1024
        ),
        LocalI2VModelFile(
            fileName = "mobilei2v_unet.onnx",
            downloadUrl = "",
            expectedSha256 = null,
            approxSizeBytes = 540L * 1024 * 1024
        ),
        LocalI2VModelFile(
            fileName = "turbo_vaed.onnx",
            downloadUrl = "",
            expectedSha256 = null,
            approxSizeBytes = 100L * 1024 * 1024
        )
    )

    fun getModelDirectory(): File {
        val directory = File(context.getExternalFilesDir(null), "local_i2v_models")
        if (!directory.exists()) directory.mkdirs()
        return directory
    }

    /**
     * Alias für getModelDirectory(), damit bestehender Code (z. B. LocalI2VEngine.kt),
     * der den ursprünglichen Methodennamen verwendet, weiterhin kompiliert.
     */
    fun getDirectory(): File = getModelDirectory()

    fun getRequiredModels(): List<LocalI2VModelFile> = requiredModels

    fun getMissingModels(): List<LocalI2VModelFile> {
        val directory = getModelDirectory()
        return requiredModels.filter { model ->
            val file = File(directory, model.fileName)
            !file.exists() || file.length() < 1024L
        }
    }

    fun areAllModelsInstalled(): Boolean = getMissingModels().isEmpty()

    fun getModelFile(fileName: String): File = File(getModelDirectory(), fileName)

    suspend fun downloadMissingModels(
        urlsByFileName: Map<String, String>,
        onProgress: (ModelDownloadProgress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val missing = getMissingModels()
        if (missing.isEmpty()) {
            onProgress(ModelDownloadProgress.Completed)
            return@withContext true
        }

        for ((index, model) in missing.withIndex()) {
            val url = urlsByFileName[model.fileName]
            if (url.isNullOrBlank()) {
                onProgress(ModelDownloadProgress.Failed("Keine Download-URL für ${model.fileName} hinterlegt."))
                return@withContext false
            }

            val targetFile = getModelFile(model.fileName)
            val partialFile = File(targetFile.parentFile, "${model.fileName}.part")

            try {
                val request = Request.Builder().url(url).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        onProgress(ModelDownloadProgress.Failed("Download von ${model.fileName} fehlgeschlagen (${response.code})."))
                        return@withContext false
                    }
                    val body = response.body ?: run {
                        onProgress(ModelDownloadProgress.Failed("Leere Antwort beim Download von ${model.fileName}."))
                        return@withContext false
                    }
                    val totalBytes = body.contentLength().takeIf { it > 0 } ?: model.approxSizeBytes
                    var downloadedBytes = 0L
                    var lastReportedPercent = -1

                    body.byteStream().use { input ->
                        FileOutputStream(partialFile).use { output ->
                            val buffer = ByteArray(1024 * 256)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                downloadedBytes += read
                                val percent = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                                if (percent != lastReportedPercent) {
                                    lastReportedPercent = percent
                                    onProgress(ModelDownloadProgress.Downloading(model.fileName, index + 1, missing.size, percent))
                                }
                            }
                        }
                    }
                }

                if (model.expectedSha256 != null) {
                    onProgress(ModelDownloadProgress.Verifying(model.fileName))
                    val actualHash = computeSha256(partialFile)
                    if (!actualHash.equals(model.expectedSha256, ignoreCase = true)) {
                        partialFile.delete()
                        onProgress(ModelDownloadProgress.Failed("Prüfsumme von ${model.fileName} stimmt nicht überein. Download verworfen."))
                        return@withContext false
                    }
                }

                if (!partialFile.renameTo(targetFile)) {
                    onProgress(ModelDownloadProgress.Failed("Konnte ${model.fileName} nach dem Download nicht umbenennen."))
                    return@withContext false
                }
            } catch (exception: Exception) {
                partialFile.delete()
                onProgress(ModelDownloadProgress.Failed("Fehler beim Download von ${model.fileName}: ${exception.message}"))
                return@withContext false
            }
        }

        onProgress(ModelDownloadProgress.Completed)
        true
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 256)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

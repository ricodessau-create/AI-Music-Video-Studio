package com.example.aivideostudio.localai

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

class LocalI2VModelStore(
    private val context: Context
) {

    companion object {
        const val VAE_ENCODER =
            "vae_encoder.onnx"

        const val QWEN2_ENCODER =
            "qwen2_encoder.onnx"

        const val MOBILE_I2V_UNET =
            "mobilei2v_unet.onnx"

        const val TURBO_VAED =
            "turbo_vaed.onnx"

        private const val MINIMUM_MODEL_SIZE =
            1024L

        private val REQUIRED_MODELS =
            listOf(
                VAE_ENCODER,
                QWEN2_ENCODER,
                MOBILE_I2V_UNET,
                TURBO_VAED
            )
    }

    fun getDirectory(): File {
        val externalDirectory =
            context
                .getExternalFilesDir(null)
                ?.resolve(
                    "local_i2v_models"
                )

        val directory =
            externalDirectory
                ?: File(
                    context.filesDir,
                    "local_i2v_models"
                )

        if (!directory.exists()) {
            directory.mkdirs()
        }

        return directory
    }

    fun getModelFile(
        name: String
    ): File {
        require(
            name in REQUIRED_MODELS
        ) {
            "Unbekanntes MobileI2V-Modell: $name"
        }

        return File(
            getDirectory(),
            name
        )
    }

    fun isModelInstalled(
        name: String
    ): Boolean {
        val file =
            getModelFile(name)

        return file.exists() &&
            file.isFile &&
            file.length() >
                MINIMUM_MODEL_SIZE
    }

    fun areAllModelsInstalled():
        Boolean {
        return REQUIRED_MODELS.all {
            isModelInstalled(it)
        }
    }

    fun getMissingModels():
        List<String> {
        return REQUIRED_MODELS.filterNot {
            isModelInstalled(it)
        }
    }

    fun getInstalledModels():
        List<File> {
        return REQUIRED_MODELS
            .map {
                getModelFile(it)
            }
            .filter {
                it.exists() &&
                    it.isFile &&
                    it.length() >
                    MINIMUM_MODEL_SIZE
            }
    }

    fun installModel(
        sourceUri: Uri,
        modelName: String,
        onProgress:
            ((Long, Long) -> Unit)? = null
    ): File {
        require(
            modelName in REQUIRED_MODELS
        ) {
            "Unbekanntes MobileI2V-Modell: $modelName"
        }

        val destination =
            getModelFile(modelName)

        val temporary =
            File(
                destination.parentFile,
                "$modelName.partial"
            )

        if (temporary.exists()) {
            temporary.delete()
        }

        val contentResolver =
            context.contentResolver

        val totalBytes =
            contentResolver
                .openAssetFileDescriptor(
                    sourceUri,
                    "r"
                )
                ?.use {
                    it.length
                }
                ?: -1L

        var copiedBytes = 0L

        contentResolver
            .openInputStream(sourceUri)
            ?.use { input ->
                FileOutputStream(
                    temporary
                ).use { output ->

                    val buffer =
                        ByteArray(
                            1024 * 1024
                        )

                    while (true) {
                        val read =
                            input.read(buffer)

                        if (read <= 0) {
                            break
                        }

                        output.write(
                            buffer,
                            0,
                            read
                        )

                        copiedBytes +=
                            read

                        onProgress?.invoke(
                            copiedBytes,
                            totalBytes
                        )
                    }

                    output.flush()
                    output.fd.sync()
                }
            }
            ?: throw IllegalStateException(
                "Modell konnte nicht gelesen werden."
            )

        if (
            !temporary.exists() ||
            temporary.length() <=
                MINIMUM_MODEL_SIZE
        ) {
            temporary.delete()

            throw IllegalStateException(
                "Die Modelldatei ist leer oder ungültig."
            )
        }

        if (destination.exists()) {
            destination.delete()
        }

        if (
            !temporary.renameTo(
                destination
            )
        ) {
            temporary.copyTo(
                destination,
                overwrite = true
            )

            temporary.delete()
        }

        return destination
    }

    fun calculateSha256(
        file: File
    ): String {
        val digest =
            MessageDigest.getInstance(
                "SHA-256"
            )

        FileInputStream(file).use {
            input ->

            val buffer =
                ByteArray(
                    1024 * 1024
                )

            while (true) {
                val read =
                    input.read(buffer)

                if (read <= 0) {
                    break
                }

                digest.update(
                    buffer,
                    0,
                    read
                )
            }
        }

        return digest
            .digest()
            .joinToString("") {
                "%02x".format(it)
            }
    }

    fun describeInstallation():
        String {
        val directory =
            getDirectory()

        val missing =
            getMissingModels()

        if (missing.isEmpty()) {
            return buildString {
                append(
                    "Alle lokalen MobileI2V-Modelle " +
                        "sind vorhanden.\n"
                )

                append(
                    "Ordner: "
                )

                append(
                    directory.absolutePath
                )
            }
        }

        return buildString {
            append(
                "Lokale MobileI2V-Modelle fehlen.\n"
            )

            append(
                "Ordner: "
            )

            append(
                directory.absolutePath
            )

            append(
                "\n\nFehlend:\n"
            )

            missing.forEach {
                append("• ")
                append(it)
                append('\n')
            }
        }
    }
}
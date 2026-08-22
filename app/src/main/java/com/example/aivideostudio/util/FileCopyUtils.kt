package com.example.aivideostudio.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object FileCopyUtils {

    fun copyUriToInternalStorage(context: Context, uri: Uri, prefix: String): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val extension = guessExtension(context, uri)
            val targetDirectory = File(context.filesDir, "imported")
            if (!targetDirectory.exists()) {
                targetDirectory.mkdirs()
            }
            val targetFile = File(targetDirectory, "${prefix}_${System.currentTimeMillis()}.$extension")
            FileOutputStream(targetFile).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
            targetFile.absolutePath
        } catch (exception: Exception) {
            null
        }
    }

    private fun guessExtension(context: Context, uri: Uri): String {
        val type = context.contentResolver.getType(uri) ?: return "dat"
        return when {
            type.contains("mpeg") -> "mp3"
            type.contains("wav") -> "wav"
            type.contains("mp4") || type.contains("m4a") -> "m4a"
            type.contains("aac") -> "aac"
            type.contains("png") -> "png"
            type.contains("jpeg") || type.contains("jpg") -> "jpg"
            else -> "dat"
        }
    }
}

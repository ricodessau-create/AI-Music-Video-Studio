package com.example.aivideostudio.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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

    fun copyImageNormalized(context: Context, uri: Uri, prefix: String, maxSide: Int = 2048): String? {
        return try {
            val resolver = context.contentResolver

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > maxSide) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return null

            val orientation = runCatching {
                resolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }
            }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
            }

            val upright = if (matrix.isIdentity) {
                decoded
            } else {
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            }
            if (upright !== decoded) {
                decoded.recycle()
            }

            val isPng = (resolver.getType(uri) ?: "").contains("png")
            val extension = if (isPng) "png" else "jpg"
            val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG

            val targetDirectory = File(context.filesDir, "imported")
            if (!targetDirectory.exists()) {
                targetDirectory.mkdirs()
            }
            val targetFile = File(targetDirectory, "${prefix}_${System.currentTimeMillis()}.$extension")
            FileOutputStream(targetFile).use { output ->
                upright.compress(format, 92, output)
            }
            upright.recycle()
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

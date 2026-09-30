package com.example.aivideostudio.ai

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class BandReferenceAnalysis(
    val bandDescription: String,
    val characterConsistencyPrompt: String,
    val sceneGuidance: String
)

class AiVisionAnalyzer(
    private val apiToken: String,
    private val modelId: String
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeImage(
        bitmap: Bitmap,
        requestedStyle: String
    ): Result<BandReferenceAnalysis> = withContext(Dispatchers.IO) {
        try {
            if (apiToken.isBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Hugging-Face-API-Token fehlt.")
                )
            }

            if (modelId.isBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Kein Vision-KI-Modell angegeben.")
                )
            }

            val imageDataUrl = bitmapToDataUrl(bitmap)

            val instruction = """
                Analysiere dieses Referenzbild für die Produktion eines Musikvideos.

                Das Bild kann eine einzelne Person, mehrere Personen, eine Band oder einen
                anderen Künstler zeigen.

                Erkenne ausschließlich visuell erkennbare Eigenschaften.

                Analysiere:
                - Anzahl der sichtbar erkennbaren Personen
                - Position und Anordnung der Personen
                - sichtbare Gesichtsmerkmale
                - Frisuren
                - Haarfarben
                - sichtbare Bärte
                - Kleidung
                - sichtbare Accessoires
                - sichtbare Tattoos oder Bemalungen
                - Instrumente
                - Körperhaltung
                - erkennbare Rollen innerhalb der Bildkomposition
                - Umgebung
                - Architektur oder Landschaft
                - Licht
                - Farbwelt
                - Kameraperspektive
                - Bildkomposition
                - visuelle Stimmung

                Erfinde keine Namen.
                Erfinde keine Identitäten.
                Erfinde keine Biografien.
                Erfinde keine zusätzlichen Personen.

                Die Personen müssen später in mehreren KI-generierten Szenen visuell
                wiedererkennbar bleiben.

                Benutzerdefinierter Stil:
                ${requestedStyle.ifBlank { "Kein zusätzlicher Stil vorgegeben." }}

                Erstelle ein visuelles Referenzprofil für eine nachfolgende Szenen-KI.
            """.trimIndent()

            val content = JSONArray()

            content.put(
                JSONObject().apply {
                    put("type", "text")
                    put(
                        "text",
                        "$instruction\n\n" +
                            "Antworte ausschließlich mit einem JSON-Objekt im folgenden Format:\n" +
                            "{\"bandDescription\":\"...\",\"characterConsistencyPrompt\":\"...\",\"sceneGuidance\":\"...\"}"
                    )
                }
            )

            content.put(
                JSONObject().apply {
                    put("type", "image_url")
                    put(
                        "image_url",
                        JSONObject().apply {
                            put("url", imageDataUrl)
                        }
                    )
                }
            )

            val messages = JSONArray()

            messages.put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", content)
                }
            )

            val payload = JSONObject().apply {
                put("model", modelId)
                put("messages", messages)
                put("temperature", 0.2)
                put("max_tokens", 2500)
            }

            val request = Request.Builder()
                .url("https://router.huggingface.co/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiToken")
                .addHeader("Content-Type", "application/json")
                .post(
                    payload.toString().toRequestBody(
                        "application/json".toMediaType()
                    )
                )
                .build()

            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    val message = try {
                        JSONObject(responseText)
                            .optJSONObject("error")
                            ?.optString("message")
                            .orEmpty()
                    } catch (_: Exception) {
                        ""
                    }

                    return@withContext Result.failure(
                        IllegalStateException(
                            if (message.isNotBlank()) {
                                "Vision-KI: $message"
                            } else {
                                "Vision-KI HTTP-Fehler ${response.code}."
                            }
                        )
                    )
                }

                if (responseText.isBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Vision-KI lieferte keine Antwort."
                        )
                    )
                }

                val root = JSONObject(responseText)

                val message = root
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")

                val contentValue = message?.opt("content")

                val generatedText = when (contentValue) {
                    is String -> contentValue
                    is JSONArray -> {
                        buildString {
                            for (i in 0 until contentValue.length()) {
                                val part = contentValue.optJSONObject(i)
                                val text = part?.optString("text").orEmpty()
                                if (text.isNotBlank()) {
                                    append(text)
                                }
                            }
                        }
                    }
                    else -> ""
                }

                if (generatedText.isBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Vision-KI lieferte keinen auswertbaren Inhalt."
                        )
                    )
                }

                val resultJson = extractJson(generatedText)

                val analysis = BandReferenceAnalysis(
                    bandDescription = resultJson.optString(
                        "bandDescription"
                    ),
                    characterConsistencyPrompt = resultJson.optString(
                        "characterConsistencyPrompt"
                    ),
                    sceneGuidance = resultJson.optString(
                        "sceneGuidance"
                    )
                )

                if (
                    analysis.bandDescription.isBlank() &&
                    analysis.characterConsistencyPrompt.isBlank() &&
                    analysis.sceneGuidance.isBlank()
                ) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Vision-KI erzeugte kein gültiges Referenzprofil."
                        )
                    )
                }

                Result.success(analysis)
            }
        } catch (exception: Exception) {
            Result.failure(
                IllegalStateException(
                    exception.message
                        ?: "Fehler bei der Vision-KI.",
                    exception
                )
            )
        }
    }

    private fun bitmapToDataUrl(bitmap: Bitmap): String {
        val prepared = resizeBitmap(bitmap)
        val output = ByteArrayOutputStream()

        prepared.compress(
            Bitmap.CompressFormat.JPEG,
            88,
            output
        )

        if (prepared !== bitmap && !prepared.isRecycled) {
            prepared.recycle()
        }

        return "data:image/jpeg;base64," +
            Base64.encodeToString(
                output.toByteArray(),
                Base64.NO_WRAP
            )
    }

    private fun resizeBitmap(bitmap: Bitmap): Bitmap {
        val maxDimension = 1536

        if (
            bitmap.width <= maxDimension &&
            bitmap.height <= maxDimension
        ) {
            return bitmap
        }

        val scale = minOf(
            maxDimension.toFloat() / bitmap.width,
            maxDimension.toFloat() / bitmap.height
        )

        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun extractJson(text: String): JSONObject {
        val cleaned = text
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')

        if (start < 0 || end <= start) {
            throw IllegalStateException(
                "Vision-KI lieferte kein JSON."
            )
        }

        return JSONObject(
            cleaned.substring(start, end + 1)
        )
    }
}

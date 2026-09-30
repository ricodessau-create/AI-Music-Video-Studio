package com.example.aivideostudio.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AiScenePlan(
    val sceneIndex: Int,
    val prompt: String,
    val cameraMovement: String,
    val transitionType: String,
    val visualEffects: List<String>,
    val intensity: Float
)

class AiScenePlanner(
    private val apiToken: String,
    private val modelId: String
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun createScenePlans(
        referenceAnalysis: BandReferenceAnalysis,
        songTitle: String,
        songLyrics: String,
        visualStyle: String,
        sectionLabels: List<String>,
        sceneCount: Int,
        aspectRatio: String,
        negativePrompt: String
    ): Result<List<AiScenePlan>> = withContext(Dispatchers.IO) {
        try {
            if (apiToken.isBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException(
                        "Hugging-Face-API-Token fehlt."
                    )
                )
            }

            if (modelId.isBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException(
                        "Kein KI-Modell angegeben."
                    )
                )
            }

            val requestedSections = sectionLabels
                .take(sceneCount)
                .mapIndexed { index, value ->
                    "${index + 1}: $value"
                }
                .joinToString("\n")

            val prompt = """
                Du bist ein professioneller Musikvideo-Regisseur
                und KI-Szenenplaner.

                Erstelle einen individuellen Szenenplan für ein Musikvideo.

                Es gibt ein vom Benutzer bereitgestelltes Referenzprofil.
                Dieses Profil stammt aus einer Vision-KI-Analyse eines echten
                Benutzerbildes.

                REFERENZPROFIL:
                ${referenceAnalysis.bandDescription}

                KONSISTENZPROFIL:
                ${referenceAnalysis.characterConsistencyPrompt}

                WEITERE VISUELLE HINWEISE:
                ${referenceAnalysis.sceneGuidance}

                SONGTITEL:
                $songTitle

                SONGTEXT:
                $songLyrics

                BENUTZERDEFINIERTER STIL:
                $visualStyle

                SEITENVERHÄLTNIS:
                $aspectRatio

                NEGATIVE PROMPT:
                $negativePrompt

                SZENENABSCHNITTE:
                $requestedSections

                Erzeuge exakt $sceneCount Szenen.

                Jede Szene muss individuell geplant werden.

                Verwende keine festen Szenen-Templates.

                Verwende keine zufälligen vorgefertigten Textbausteine.

                Die sichtbaren Personen müssen anhand des Referenzprofils
                über die Szenen hinweg konsistent bleiben.

                Beziehe die Personen aktiv in die jeweilige Szene ein,
                sofern Personen im Referenzbild vorhanden sind.

                Wenn das Referenzbild keine Personen enthält,
                darfst du keine Personen erfinden.

                Beschreibe konkret:
                - Handlung
                - Personenpositionen
                - Umgebung
                - Beleuchtung
                - Atmosphäre
                - Kameraperspektive
                - Kamera-Bewegung
                - Bildkomposition
                - visuelle Effekte

                Die Szenen sollen sich dramaturgisch entwickeln und nicht
                lediglich dasselbe Bild wiederholen.

                Antworte ausschließlich als gültiges JSON:
                {
                  "scenes": [
                    {
                      "sceneIndex": 0,
                      "prompt": "...",
                      "cameraMovement": "...",
                      "transitionType": "...",
                      "visualEffects": ["..."],
                      "intensity": 0.0
                    }
                  ]
                }
            """.trimIndent()

            val messages = JSONArray()

            messages.put(
                JSONObject().apply {
                    put(
                        "role",
                        "system"
                    )
                    put(
                        "content",
                        "Du erzeugst detaillierte, konsistente und individuell " +
                            "geplante Musikvideo-Szenen."
                    )
                }
            )

            messages.put(
                JSONObject().apply {
                    put(
                        "role",
                        "user"
                    )
                    put(
                        "content",
                        prompt
                    )
                }
            )

            val payload = JSONObject().apply {
                put("model", modelId)
                put("messages", messages)
                put("temperature", 0.7)
                put("max_tokens", 6000)
            }

            val request = Request.Builder()
                .url("https://router.huggingface.co/v1/chat/completions")
                .addHeader(
                    "Authorization",
                    "Bearer $apiToken"
                )
                .addHeader(
                    "Content-Type",
                    "application/json"
                )
                .post(
                    payload.toString().toRequestBody(
                        "application/json".toMediaType()
                    )
                )
                .build()

            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Szenen-KI HTTP-Fehler ${response.code}."
                        )
                    )
                }

                val root = JSONObject(responseText)

                val content = root
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()

                if (content.isBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Szenen-KI lieferte keine Antwort."
                        )
                    )
                }

                val json = extractJson(content)
                val scenesJson = json.optJSONArray("scenes")

                if (scenesJson == null) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Szenen-KI lieferte keine Szenen."
                        )
                    )
                }

                val scenes = ArrayList<AiScenePlan>()

                for (index in 0 until scenesJson.length()) {
                    val item = scenesJson.optJSONObject(index)
                        ?: continue

                    val effects = ArrayList<String>()
                    val effectArray = item.optJSONArray(
                        "visualEffects"
                    )

                    if (effectArray != null) {
                        for (effectIndex in 0 until effectArray.length()) {
                            val effect = effectArray
                                .optString(effectIndex)
                                .trim()

                            if (effect.isNotBlank()) {
                                effects.add(effect)
                            }
                        }
                    }

                    val scene = AiScenePlan(
                        sceneIndex = item.optInt(
                            "sceneIndex",
                            index
                        ),
                        prompt = item.optString(
                            "prompt"
                        ).trim(),
                        cameraMovement = item.optString(
                            "cameraMovement"
                        ).trim(),
                        transitionType = item.optString(
                            "transitionType"
                        ).trim(),
                        visualEffects = effects,
                        intensity = item.optDouble(
                            "intensity",
                            0.5
                        ).toFloat().coerceIn(0f, 1f)
                    )

                    if (scene.prompt.isNotBlank()) {
                        scenes.add(scene)
                    }
                }

                if (scenes.isEmpty()) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Szenen-KI erzeugte keine gültigen Szenen."
                        )
                    )
                }

                Result.success(scenes)
            }
        } catch (exception: Exception) {
            Result.failure(
                IllegalStateException(
                    exception.message
                        ?: "Fehler bei der Szenen-KI.",
                    exception
                )
            )
        }
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
                "Szenen-KI lieferte kein gültiges JSON."
            )
        }

        return JSONObject(
            cleaned.substring(start, end + 1)
        )
    }
}

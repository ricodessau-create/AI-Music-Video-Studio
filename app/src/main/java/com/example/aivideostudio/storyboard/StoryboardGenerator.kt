package com.example.aivideostudio.storyboard

import com.example.aivideostudio.audio.AudioFeatures
import com.example.aivideostudio.audio.SongGenre
import java.util.UUID
import kotlin.math.max

class StoryboardGenerator {

    fun generate(
        projectId: String,
        audioFeatures: AudioFeatures,
        visualParameters: VisualParameters,
        basePrompt: String,
        globalVisualStyle: String = "",
        genre: SongGenre? = null
    ): List<GeneratedScene> {
        if (audioFeatures.durationSeconds <= 0.0) {
            return emptyList()
        }

        val boundaries = buildSceneBoundaries(
            audioFeatures = audioFeatures,
            targetSceneDurationSeconds = 6.0
        )

        if (boundaries.size < 2) {
            return emptyList()
        }

        val scenes = ArrayList<GeneratedScene>()

        for (index in 0 until boundaries.size - 1) {
            val startTime = boundaries[index]
            val endTime = boundaries[index + 1]

            if (endTime <= startTime) {
                continue
            }

            val label = determineSectionLabel(
                index = index,
                totalScenes = boundaries.size - 1,
                audioFeatures = audioFeatures
            )

            val intensity = calculateIntensity(
                startTime = startTime,
                endTime = endTime,
                audioFeatures = audioFeatures
            )

            val prompt = buildScenePrompt(
                sectionLabel = label,
                sceneIndex = index,
                basePrompt = basePrompt,
                globalVisualStyle = globalVisualStyle,
                visualParameters = visualParameters,
                genre = genre,
                intensity = intensity
            )

            scenes.add(
                GeneratedScene(
                    id = UUID.randomUUID().toString(),
                    orderIndex = index,
                    label = label,
                    startTimeSeconds = startTime,
                    endTimeSeconds = endTime,
                    prompt = prompt,
                    cameraMovement = determineCameraMovement(
                        sceneIndex = index,
                        intensity = intensity
                    ),
                    transitionType = determineTransitionType(
                        sceneIndex = index,
                        totalScenes = boundaries.size - 1
                    ),
                    effects = determineEffects(
                        visualParameters = visualParameters,
                        intensity = intensity
                    ),
                    intensity = intensity,
                    seed = createSeed(
                        projectId = projectId,
                        sceneIndex = index
                    )
                )
            )
        }

        if (scenes.isEmpty()) {
            return scenes
        }

        val lastIndex = scenes.lastIndex
        val lastScene = scenes[lastIndex]

        if (lastScene.label != "Outro") {
            scenes[lastIndex] = lastScene.copy(
                label = "Outro"
            )
        }

        return scenes
    }

    private fun buildSceneBoundaries(
        audioFeatures: AudioFeatures,
        targetSceneDurationSeconds: Double
    ): List<Double> {
        val duration = audioFeatures.durationSeconds
        val boundaries = ArrayList<Double>()

        boundaries.add(0.0)

        val bars = audioFeatures.barTimestamps

        if (bars.size >= 2) {
            var lastBoundary = 0.0

            for (barTime in bars) {
                if (barTime <= 0.0 || barTime >= duration) {
                    continue
                }

                if (barTime - lastBoundary >= targetSceneDurationSeconds) {
                    boundaries.add(barTime)
                    lastBoundary = barTime
                }
            }
        }

        if (boundaries.last() < duration) {
            boundaries.add(duration)
        }

        if (boundaries.size < 2) {
            var current = 0.0

            while (current < duration) {
                boundaries.add(
                    minOf(
                        current + targetSceneDurationSeconds,
                        duration
                    )
                )

                current += targetSceneDurationSeconds
            }
        }

        return boundaries
            .distinct()
            .sorted()
            .filter { it in 0.0..duration }
    }

    private fun determineSectionLabel(
        index: Int,
        totalScenes: Int,
        audioFeatures: AudioFeatures
    ): String {
        if (totalScenes <= 1) {
            return "Outro"
        }

        if (index == 0) {
            return "Intro"
        }

        if (index == totalScenes - 1) {
            return "Outro"
        }

        val scenePosition = index.toFloat() / totalScenes.toFloat()

        if (audioFeatures.intenseSections.isNotEmpty()) {
            val midpoint = indexPosition(
                index = index,
                totalScenes = totalScenes,
                duration = audioFeatures.durationSeconds
            )

            val intense = audioFeatures.intenseSections.any {
                midpoint in it
            }

            if (intense) {
                return "Chorus"
            }
        }

        return when {
            scenePosition < 0.25f -> "Verse"
            scenePosition < 0.35f -> "Pre-Chorus"
            scenePosition < 0.65f -> "Chorus"
            scenePosition < 0.8f -> "Verse"
            scenePosition < 0.92f -> "Breakdown"
            else -> "Outro"
        }
    }

    private fun indexPosition(
        index: Int,
        totalScenes: Int,
        duration: Double
    ): Double {
        if (totalScenes <= 0) {
            return 0.0
        }

        return (
            index.toDouble() /
                totalScenes.toDouble()
            ) * duration
    }

    private fun calculateIntensity(
        startTime: Double,
        endTime: Double,
        audioFeatures: AudioFeatures
    ): Float {
        if (audioFeatures.rmsEnergyCurve.isEmpty()) {
            return 0.5f
        }

        val duration = audioFeatures.durationSeconds

        if (duration <= 0.0) {
            return 0.5f
        }

        val startIndex = (
            startTime /
                duration *
                audioFeatures.rmsEnergyCurve.size
            ).toInt().coerceIn(
                0,
                audioFeatures.rmsEnergyCurve.lastIndex
            )

        val endIndex = (
            endTime /
                duration *
                audioFeatures.rmsEnergyCurve.size
            ).toInt().coerceIn(
                startIndex,
                audioFeatures.rmsEnergyCurve.lastIndex
            )

        var total = 0.0
        var count = 0

        for (index in startIndex..endIndex) {
            total += audioFeatures.rmsEnergyCurve[index].toDouble()
            count++
        }

        if (count == 0) {
            return 0.5f
        }

        val average = (
            total /
                count.toDouble()
        ).toFloat()

        val maxEnergy = audioFeatures.rmsEnergyCurve
            .maxOrNull()
            ?.coerceAtLeast(0.0001f)
            ?: 1f

        return (
            average /
                maxEnergy
            ).coerceIn(0f, 1f)
        )
    }

    private fun buildScenePrompt(
        sectionLabel: String,
        sceneIndex: Int,
        basePrompt: String,
        globalVisualStyle: String,
        visualParameters: VisualParameters,
        genre: SongGenre?,
        intensity: Float
    ): String {
        return buildString {
            if (basePrompt.isNotBlank()) {
                append(basePrompt.trim())
                append(". ")
            }

            if (globalVisualStyle.isNotBlank()) {
                append(globalVisualStyle.trim())
                append(". ")
            }

            if (genre != null) {
                append("Musikgenre: ")
                append(genre.name)
                append(". ")
            }

            append("Musikvideo-Szene ")
            append(sceneIndex + 1)
            append(". ")

            append("Dramaturgischer Abschnitt: ")
            append(sectionLabel)
            append(". ")

            append("Visuelle Intensität: ")
            append(
                String.format(
                    java.util.Locale.US,
                    "%.2f",
                    intensity
                )
            )
            append(". ")

            append(
                "Die Szene soll eigenständig komponiert werden und " +
                    "sich visuell sinnvoll aus dem Referenzbild, dem " +
                    "Benutzerstil und der Musik ableiten. "
            )

            if (visualParameters.hasSnowParticles) {
                append("Schnee darf als atmosphärisches Element verwendet werden. ")
            }

            if (visualParameters.hasFireParticles) {
                append("Feuer und Funken dürfen als atmosphärische Elemente verwendet werden. ")
            }

            if (visualParameters.hasRain) {
                append("Regen darf als atmosphärisches Element verwendet werden. ")
            }

            if (visualParameters.hasSmoke) {
                append("Rauch darf als atmosphärisches Element verwendet werden. ")
            }

            append(
                "Keine zufälligen neuen Personen und keine Veränderung " +
                    "der im Referenzbild erkennbaren Personen."
            )
        }
    }

    private fun determineCameraMovement(
        sceneIndex: Int,
        intensity: Float
    ): String {
        return when {
            intensity >= 0.8f && sceneIndex % 3 == 0 ->
                "dynamic_push_in"

            intensity >= 0.65f && sceneIndex % 3 == 1 ->
                "slow_orbit"

            intensity >= 0.5f ->
                "slow_dolly"

            sceneIndex % 2 == 0 ->
                "slow_zoom"

            else ->
                "static_with_parallax"
        }
    }

    private fun determineTransitionType(
        sceneIndex: Int,
        totalScenes: Int
    ): String {
        if (sceneIndex == 0) {
            return "fade_in"
        }

        if (sceneIndex == totalScenes - 1) {
            return "fade_out"
        }

        return when (sceneIndex % 3) {
            0 -> "cross_fade"
            1 -> "cut"
            else -> "cross_fade"
        }
    }

    private fun determineEffects(
        visualParameters: VisualParameters,
        intensity: Float
    ): List<String> {
        val effects = ArrayList<String>()

        if (visualParameters.hasSnowParticles) {
            effects.add("snow")
        }

        if (visualParameters.hasFireParticles) {
            effects.add("fire")
        }

        if (visualParameters.hasRain) {
            effects.add("rain")
        }

        if (visualParameters.hasSmoke) {
            effects.add("smoke")
        }

        if (intensity >= 0.75f) {
            effects.add("light_flicker")
        }

        return effects
    }

    private fun createSeed(
        projectId: String,
        sceneIndex: Int
    ): Long {
        val hash = projectId.hashCode().toLong()

        return max(
            1L,
            hash * 31L + sceneIndex.toLong()
        )
    }
}

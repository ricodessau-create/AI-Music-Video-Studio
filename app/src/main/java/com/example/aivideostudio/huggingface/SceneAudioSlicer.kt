package com.example.aivideostudio.huggingface

import com.example.aivideostudio.audio.AudioFeatures
import com.example.aivideostudio.storyboard.GeneratedScene
import java.util.UUID

class SceneAudioSlicer {

    fun slice(
        audioFeatures: AudioFeatures,
        targetSceneDurationSeconds: Double,
        basePrompt: String,
        globalVisualStyle: String
    ): List<GeneratedScene> {
        val duration = audioFeatures.durationSeconds
        if (duration <= 0.0) return emptyList()

        val boundaries = buildBoundaries(audioFeatures, targetSceneDurationSeconds, duration)
        val scenes = ArrayList<GeneratedScene>()

        for (i in 0 until boundaries.size - 1) {
            val startTime = boundaries[i]
            val endTime = boundaries[i + 1]
            val prompt = buildString {
                append("Szene ${i + 1}: ")
                append(basePrompt)
                if (globalVisualStyle.isNotBlank()) {
                    append(", ")
                    append(globalVisualStyle)
                }
            }

            scenes.add(
                GeneratedScene(
                    id = UUID.randomUUID().toString(),
                    orderIndex = i,
                    label = "Szene ${i + 1}",
                    startTimeSeconds = startTime,
                    endTimeSeconds = endTime,
                    prompt = prompt,
                    cameraMovement = "static_with_parallax",
                    transitionType = "cross_fade",
                    effects = emptyList(),
                    intensity = 0.5f,
                    seed = 0L
                )
            )
        }

        return scenes
    }

    private fun buildBoundaries(
        audioFeatures: AudioFeatures,
        targetSceneDurationSeconds: Double,
        duration: Double
    ): List<Double> {
        val bars = audioFeatures.barTimestamps
        if (bars.size >= 2) {
            val boundaries = ArrayList<Double>()
            boundaries.add(0.0)
            var accumulatedSinceLastBoundary = 0.0
            var lastBoundary = 0.0

            for (barTime in bars) {
                accumulatedSinceLastBoundary = barTime - lastBoundary
                if (accumulatedSinceLastBoundary >= targetSceneDurationSeconds) {
                    boundaries.add(barTime)
                    lastBoundary = barTime
                }
            }
            if (boundaries.last() < duration) {
                boundaries.add(duration)
            }
            if (boundaries.size >= 2) {
                return boundaries
            }
        }

        val fixedBoundaries = ArrayList<Double>()
        var current = 0.0
        while (current < duration) {
            fixedBoundaries.add(current)
            current += targetSceneDurationSeconds
        }
        fixedBoundaries.add(duration)
        return fixedBoundaries
    }
}

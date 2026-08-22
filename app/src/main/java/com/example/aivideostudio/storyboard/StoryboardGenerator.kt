package com.example.aivideostudio.storyboard

import com.example.aivideostudio.audio.AudioFeatures
import java.util.UUID
import kotlin.random.Random

data class GeneratedScene(
    val id: String,
    val orderIndex: Int,
    val label: String,
    val startTimeSeconds: Double,
    val endTimeSeconds: Double,
    val prompt: String,
    val cameraMovement: String,
    val transitionType: String,
    val effects: List<String>,
    val intensity: Float,
    val seed: Long
)

class StoryboardGenerator {

    fun generate(
        projectId: String,
        audioFeatures: AudioFeatures,
        visualParameters: VisualParameters,
        basePrompt: String,
        globalVisualStyle: String = ""
    ): List<GeneratedScene> {
        val sectionLabels = buildSectionLabels(audioFeatures)
        val scenes = ArrayList<GeneratedScene>()
        val promptGenerator = ScenePromptGenerator()
        val random = Random(System.nanoTime())

        for ((index, section) in sectionLabels.withIndex()) {
            val effects = buildEffectsForSection(section.label, visualParameters)
            val camera = pickCameraMovement(section.label, visualParameters, index)
            val transition = pickTransition(section.label, visualParameters)
            val intensity = computeIntensity(audioFeatures, section.startTime, section.endTime)

            val prompt = promptGenerator.generateScenePrompt(
                sectionLabel = section.label,
                sceneIndexInSection = index,
                globalVisualStyle = globalVisualStyle,
                userVisualPrompt = basePrompt
            )

            scenes.add(
                GeneratedScene(
                    id = UUID.randomUUID().toString(),
                    orderIndex = index,
                    label = section.label,
                    startTimeSeconds = section.startTime,
                    endTimeSeconds = section.endTime,
                    prompt = prompt,
                    cameraMovement = camera,
                    transitionType = transition,
                    effects = effects,
                    intensity = intensity,
                    seed = random.nextLong().let { if (it < 0) -it else it }
                )
            )
        }

        return scenes
    }

    private data class SectionMarker(val label: String, val startTime: Double, val endTime: Double)

    private fun buildSectionLabels(audioFeatures: AudioFeatures): List<SectionMarker> {
        val duration = audioFeatures.durationSeconds
        val bars = audioFeatures.barTimestamps.ifEmpty { listOf(0.0, duration) }

        val boundaries = ArrayList<Double>()
        boundaries.add(0.0)

        val targetSectionCount = when {
            duration < 120 -> 6
            duration < 240 -> 8
            else -> 10
        }

        val step = bars.size.toDouble() / targetSectionCount.toDouble()
        var accumulator = 0.0
        while (accumulator < bars.size) {
            val index = accumulator.toInt().coerceIn(0, bars.size - 1)
            val time = bars[index]
            if (boundaries.last() < time) {
                boundaries.add(time)
            }
            accumulator += step
        }
        if (boundaries.last() < duration) {
            boundaries.add(duration)
        }

        val labels = listOf("Intro", "Szene 1", "Szene 2", "Pre-Chorus", "Chorus", "Breakdown", "Solo", "Chorus", "Szene 3", "Outro")
        val sections = ArrayList<SectionMarker>()
        for (i in 0 until boundaries.size - 1) {
            val label = labels.getOrElse(i) { "Szene ${i + 1}" }
            sections.add(SectionMarker(label, boundaries[i], boundaries[i + 1]))
        }
        if (sections.isNotEmpty()) {
            sections[sections.size - 1] = sections.last().copy(label = "Outro")
        }
        return sections
    }

    private fun buildEffectsForSection(label: String, visual: VisualParameters): List<String> {
        val effects = ArrayList<String>()
        if (visual.hasSnowParticles) effects.add("snow_particles")
        if (visual.hasFireParticles) effects.add("fire_particles")
        if (visual.hasRuneOverlay) effects.add("rune_overlay")
        if (visual.hasSmoke) effects.add("smoke")
        if (visual.hasRain) effects.add("rain")
        if (visual.hasLightning && (label == "Chorus" || label == "Breakdown")) effects.add("lightning_flash")
        effects.add("film_grain")
        effects.add("vignette")
        if (label == "Chorus" || label == "Solo") {
            effects.add("bass_pulse")
            effects.add("beat_sync_zoom")
        }
        effects.add("waveform_overlay")
        return effects
    }

    private fun pickCameraMovement(label: String, visual: VisualParameters, index: Int): String {
        if (label == "Breakdown") return "slow_pan"
        if (visual.cameraPace == "slow" && label != "Chorus" && label != "Solo") return "slow_pan"
        if (label == "Chorus" || label == "Solo") return "fast_zoom"
        val cycle = listOf("pan_left", "pan_right", "zoom_in", "zoom_out", "static_with_parallax")
        return cycle[index % cycle.size]
    }

    private fun pickTransition(label: String, visual: VisualParameters): String {
        return if (visual.cutAggressiveness == "hard") "hard_cut" else "cross_fade"
    }

    private fun computeIntensity(audioFeatures: AudioFeatures, startTime: Double, endTime: Double): Float {
        if (audioFeatures.rmsEnergyCurve.isEmpty()) return 0.5f
        val frameCount = audioFeatures.rmsEnergyCurve.size
        val duration = audioFeatures.durationSeconds
        if (duration <= 0.0) return 0.5f
        val startIndex = ((startTime / duration) * frameCount).toInt().coerceIn(0, frameCount - 1)
        val endIndex = ((endTime / duration) * frameCount).toInt().coerceIn(startIndex, frameCount - 1)
        if (endIndex <= startIndex) return audioFeatures.rmsEnergyCurve[startIndex]
        var sum = 0f
        for (i in startIndex..endIndex) {
            sum += audioFeatures.rmsEnergyCurve[i]
        }
        val average = sum / (endIndex - startIndex + 1)
        val maxEnergy = audioFeatures.rmsEnergyCurve.max().coerceAtLeast(0.0001f)
        return (average / maxEnergy).coerceIn(0f, 1f)
    }
}

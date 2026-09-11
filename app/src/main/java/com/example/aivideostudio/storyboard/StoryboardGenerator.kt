package com.example.aivideostudio.storyboard

import com.example.aivideostudio.audio.AudioFeatures
import com.example.aivideostudio.audio.SongGenre
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

    private val minimumSectionSeconds = 3.0

    fun generate(
        projectId: String,
        audioFeatures: AudioFeatures,
        visualParameters: VisualParameters,
        basePrompt: String,
        globalVisualStyle: String = "",
        genre: SongGenre = SongGenre.OTHER
    ): List<GeneratedScene> {
        val sectionLabels = buildSectionLabels(audioFeatures)
        val scenes = ArrayList<GeneratedScene>()
        val promptGenerator = ScenePromptGenerator()
        val comfyUiSeedRandom = Random(System.nanoTime())
        val songSeed = computeSongSeed(audioFeatures)

        for ((index, section) in sectionLabels.withIndex()) {
            val effects = buildEffectsForSection(section.label, visualParameters, genre)
            val camera = pickCameraMovement(section.label, visualParameters, genre, index)
            val transition = pickTransition(visualParameters, genre)
            val intensity = computeIntensity(audioFeatures, section.startTime, section.endTime)

            val prompt = promptGenerator.generateScenePrompt(
                sectionLabel = section.label,
                sceneIndexInSection = index,
                globalVisualStyle = globalVisualStyle,
                userVisualPrompt = basePrompt,
                songSeed = songSeed,
                sceneIntensity = intensity
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
                    seed = comfyUiSeedRandom.nextLong().let { if (it < 0) -it else it }
                )
            )
        }

        return scenes
    }

    private fun computeSongSeed(audioFeatures: AudioFeatures): Int {
        val bpmComponent = (audioFeatures.bpm * 100.0).toInt()
        val durationComponent = (audioFeatures.durationSeconds * 10.0).toInt()
        val beatCountComponent = audioFeatures.beatTimestamps.size
        return bpmComponent * 31 + durationComponent * 17 + beatCountComponent
    }

    private data class SectionMarker(val label: String, val startTime: Double, val endTime: Double)

    private fun buildSectionLabels(audioFeatures: AudioFeatures): List<SectionMarker> {
        val duration = audioFeatures.durationSeconds
        val bars = audioFeatures.barTimestamps.ifEmpty { listOf(0.0, duration) }

        val rawBoundaries = ArrayList<Double>()
        rawBoundaries.add(0.0)

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
            if (rawBoundaries.last() < time) {
                rawBoundaries.add(time)
            }
            accumulator += step
        }
        if (rawBoundaries.last() < duration) {
            rawBoundaries.add(duration)
        }

        val boundaries = mergeShortSections(rawBoundaries, duration)

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

    private fun mergeShortSections(rawBoundaries: List<Double>, duration: Double): List<Double> {
        if (rawBoundaries.size < 2) return listOf(0.0, duration)

        val filtered = ArrayList<Double>()
        filtered.add(rawBoundaries.first())
        for (i in 1 until rawBoundaries.size) {
            val candidate = rawBoundaries[i]
            val isLastBoundary = i == rawBoundaries.size - 1
            if (isLastBoundary || candidate - filtered.last() >= minimumSectionSeconds) {
                filtered.add(candidate)
            }
        }

        if (filtered.size < 2) {
            return listOf(0.0, duration)
        }
        return filtered
    }

    private fun buildEffectsForSection(label: String, visual: VisualParameters, genre: SongGenre): List<String> {
        val effects = ArrayList<String>()
        if (visual.hasSnowParticles) effects.add("snow_particles")
        if (visual.hasFireParticles) effects.add("fire_particles")
        if (visual.hasRuneOverlay) effects.add("rune_overlay")
        if (visual.hasSmoke) effects.add("smoke")
        if (visual.hasRain) effects.add("rain")
        if (visual.hasLightning && (label == "Chorus" || label == "Breakdown")) effects.add("lightning_flash")
        effects.add("film_grain")
        effects.add("vignette")
        val bassEmphasisGenre = genre == SongGenre.HIP_HOP || genre == SongGenre.METAL
        if (label == "Chorus" || label == "Solo" || bassEmphasisGenre) {
            effects.add("bass_pulse")
            effects.add("beat_sync_zoom")
        }
        effects.add("waveform_overlay")
        return effects
    }

    private fun pickCameraMovement(label: String, visual: VisualParameters, genre: SongGenre, index: Int): String {
        if (genre == SongGenre.BALLADE) {
            return if (label == "Chorus") "slow_pan" else "static_with_parallax"
        }
        if (label == "Breakdown") return "slow_pan"
        if (visual.cameraPace == "slow" && label != "Chorus" && label != "Solo") return "slow_pan"
        if (genre == SongGenre.METAL && (label == "Chorus" || label == "Solo")) return "fast_zoom"
        if (label == "Chorus" || label == "Solo") return "fast_zoom"
        val cycle = listOf("pan_left", "pan_right", "zoom_in", "zoom_out", "static_with_parallax")
        return cycle[index % cycle.size]
    }

    private fun pickTransition(visual: VisualParameters, genre: SongGenre): String {
        if (genre == SongGenre.BALLADE || genre == SongGenre.POP) return "cross_fade"
        if (genre == SongGenre.METAL) return "hard_cut"
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

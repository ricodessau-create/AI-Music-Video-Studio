package com.example.aivideostudio.storyboard

import com.example.aivideostudio.audio.AudioFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryboardGeneratorTest {

    @Test
    fun generateCreatesNonOverlappingScenes() {
        val bars = (0..40).map { it * 2.0 }
        val audioFeatures = AudioFeatures(
            durationSeconds = 80.0,
            bpm = 120.0,
            beatTimestamps = bars,
            barTimestamps = bars,
            rmsEnergyCurve = List(200) { (it % 50) / 50f },
            peakTimestamps = listOf(10.0, 30.0, 50.0),
            quietSections = emptyList(),
            intenseSections = emptyList(),
            spectralCentroidCurve = List(200) { 1000f }
        )
        val visualParameters = VisualPromptAnalyzer().analyze("düsteres viking metal video mit eis und feuer")

        val scenes = StoryboardGenerator().generate(
            projectId = "test-project",
            audioFeatures = audioFeatures,
            visualParameters = visualParameters,
            basePrompt = "test prompt"
        )

        assertTrue(scenes.isNotEmpty())
        for (i in 0 until scenes.size - 1) {
            assertTrue(scenes[i].endTimeSeconds <= scenes[i + 1].startTimeSeconds + 0.001)
        }
        assertEquals("Outro", scenes.last().label)
    }
}

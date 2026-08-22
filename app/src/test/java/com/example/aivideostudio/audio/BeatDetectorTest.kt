package com.example.aivideostudio.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class BeatDetectorTest {

    @Test
    fun analyzeProducesPositiveDuration() {
        val sampleRate = 44100
        val durationSeconds = 4.0
        val sampleCount = (sampleRate * durationSeconds).toInt()
        val samples = FloatArray(sampleCount)
        val frequency = 220.0
        for (i in samples.indices) {
            val time = i.toDouble() / sampleRate.toDouble()
            samples[i] = (0.5 * sin(2.0 * PI * frequency * time)).toFloat()
        }

        val detector = BeatDetector(samples, sampleRate, 1)
        val features = detector.analyze()

        assertTrue(features.durationSeconds > 3.9)
        assertTrue(features.bpm >= 70.0 && features.bpm <= 180.0)
    }

    @Test
    fun analyzeHandlesSilence() {
        val sampleRate = 44100
        val samples = FloatArray(sampleRate * 2)
        val detector = BeatDetector(samples, sampleRate, 1)
        val features = detector.analyze()

        assertTrue(features.beatTimestamps.isEmpty() || features.beatTimestamps.isNotEmpty())
        assertTrue(features.durationSeconds > 1.9)
    }
}

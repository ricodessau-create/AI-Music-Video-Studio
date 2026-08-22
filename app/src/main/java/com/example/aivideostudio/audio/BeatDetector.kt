package com.example.aivideostudio.audio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class BeatDetector(
    private val samples: FloatArray,
    private val sampleRate: Int,
    private val channelCount: Int
) {

    private val monoSamples: FloatArray by lazy { toMono() }

    fun analyze(): AudioFeatures {
        val mono = monoSamples
        val durationSeconds = mono.size.toDouble() / sampleRate.toDouble()

        val frameSize = 1024
        val hopSize = 512
        val energyFrames = computeEnergyFrames(mono, frameSize, hopSize)
        val onsetEnvelope = computeOnsetEnvelope(energyFrames)
        val beatTimestamps = pickBeats(onsetEnvelope, hopSize)
        val bpm = estimateBpm(beatTimestamps)
        val barTimestamps = computeBarTimestamps(beatTimestamps)
        val rmsCurve = energyFrames.map { sqrt(it) }
        val peaks = pickPeaks(rmsCurve, hopSize)
        val quiet = findQuietSections(rmsCurve, hopSize, durationSeconds)
        val intense = findIntenseSections(rmsCurve, hopSize, durationSeconds)
        val spectralCentroid = computeSpectralCentroidCurve(mono, frameSize, hopSize)

        return AudioFeatures(
            durationSeconds = durationSeconds,
            bpm = bpm,
            beatTimestamps = beatTimestamps,
            barTimestamps = barTimestamps,
            rmsEnergyCurve = rmsCurve,
            peakTimestamps = peaks,
            quietSections = quiet,
            intenseSections = intense,
            spectralCentroidCurve = spectralCentroid
        )
    }

    private fun toMono(): FloatArray {
        if (channelCount <= 1) return samples
        val frameCount = samples.size / channelCount
        val out = FloatArray(frameCount)
        for (i in 0 until frameCount) {
            var sum = 0f
            for (c in 0 until channelCount) {
                sum += samples[i * channelCount + c]
            }
            out[i] = sum / channelCount
        }
        return out
    }

    private fun computeEnergyFrames(mono: FloatArray, frameSize: Int, hopSize: Int): List<Float> {
        val frames = ArrayList<Float>()
        var index = 0
        while (index + frameSize <= mono.size) {
            var sumSquares = 0f
            for (i in 0 until frameSize) {
                val s = mono[index + i]
                sumSquares += s * s
            }
            frames.add(sumSquares / frameSize)
            index += hopSize
        }
        return frames
    }

    private fun computeOnsetEnvelope(energyFrames: List<Float>): List<Float> {
        val envelope = ArrayList<Float>(energyFrames.size)
        for (i in energyFrames.indices) {
            val diff = if (i == 0) 0f else energyFrames[i] - energyFrames[i - 1]
            envelope.add(max(0f, diff))
        }
        return envelope
    }

    private fun pickBeats(onsetEnvelope: List<Float>, hopSize: Int): List<Double> {
        if (onsetEnvelope.isEmpty()) return emptyList()
        val mean = onsetEnvelope.average().toFloat()
        val threshold = mean * 1.5f
        val beats = ArrayList<Double>()
        val minGapFrames = ((sampleRate * 0.25) / hopSize).toInt().coerceAtLeast(1)
        var lastBeatFrame = -minGapFrames
        for (i in onsetEnvelope.indices) {
            val value = onsetEnvelope[i]
            val isLocalPeak = i > 0 && i < onsetEnvelope.size - 1 &&
                value > onsetEnvelope[i - 1] && value >= onsetEnvelope[i + 1]
            if (value > threshold && isLocalPeak && i - lastBeatFrame >= minGapFrames) {
                val timeSeconds = (i.toDouble() * hopSize.toDouble()) / sampleRate.toDouble()
                beats.add(timeSeconds)
                lastBeatFrame = i
            }
        }
        return beats
    }

    private fun estimateBpm(beatTimestamps: List<Double>): Double {
        if (beatTimestamps.size < 2) return 120.0
        val intervals = ArrayList<Double>()
        for (i in 1 until beatTimestamps.size) {
            intervals.add(beatTimestamps[i] - beatTimestamps[i - 1])
        }
        val sorted = intervals.sorted()
        val median = sorted[sorted.size / 2]
        if (median <= 0.0) return 120.0
        var bpm = 60.0 / median
        while (bpm < 70.0) bpm *= 2.0
        while (bpm > 180.0) bpm /= 2.0
        return bpm
    }

    private fun computeBarTimestamps(beatTimestamps: List<Double>): List<Double> {
        val bars = ArrayList<Double>()
        var count = 0
        for (timestamp in beatTimestamps) {
            if (count % 4 == 0) {
                bars.add(timestamp)
            }
            count++
        }
        return bars
    }

    private fun pickPeaks(rmsCurve: List<Float>, hopSize: Int): List<Double> {
        if (rmsCurve.isEmpty()) return emptyList()
        val mean = rmsCurve.average().toFloat()
        val threshold = mean * 2.0f
        val peaks = ArrayList<Double>()
        val minGapFrames = ((sampleRate * 0.5) / hopSize).toInt().coerceAtLeast(1)
        var lastPeakFrame = -minGapFrames
        for (i in 1 until rmsCurve.size - 1) {
            val value = rmsCurve[i]
            if (value > threshold && value > rmsCurve[i - 1] && value >= rmsCurve[i + 1] && i - lastPeakFrame >= minGapFrames) {
                peaks.add((i.toDouble() * hopSize.toDouble()) / sampleRate.toDouble())
                lastPeakFrame = i
            }
        }
        return peaks
    }

    private fun findQuietSections(
        rmsCurve: List<Float>,
        hopSize: Int,
        durationSeconds: Double
    ): List<ClosedFloatingPointRange<Double>> {
        if (rmsCurve.isEmpty()) return emptyList()
        val mean = rmsCurve.average().toFloat()
        val quietThreshold = mean * 0.4f
        return extractRanges(rmsCurve, hopSize, durationSeconds) { it < quietThreshold }
    }

    private fun findIntenseSections(
        rmsCurve: List<Float>,
        hopSize: Int,
        durationSeconds: Double
    ): List<ClosedFloatingPointRange<Double>> {
        if (rmsCurve.isEmpty()) return emptyList()
        val mean = rmsCurve.average().toFloat()
        val intenseThreshold = mean * 1.6f
        return extractRanges(rmsCurve, hopSize, durationSeconds) { it > intenseThreshold }
    }

    private fun extractRanges(
        rmsCurve: List<Float>,
        hopSize: Int,
        durationSeconds: Double,
        predicate: (Float) -> Boolean
    ): List<ClosedFloatingPointRange<Double>> {
        val ranges = ArrayList<ClosedFloatingPointRange<Double>>()
        var rangeStart = -1
        for (i in rmsCurve.indices) {
            val matches = predicate(rmsCurve[i])
            if (matches && rangeStart < 0) {
                rangeStart = i
            } else if (!matches && rangeStart >= 0) {
                val startTime = (rangeStart.toDouble() * hopSize.toDouble()) / sampleRate.toDouble()
                val endTime = (i.toDouble() * hopSize.toDouble()) / sampleRate.toDouble()
                if (endTime - startTime > 1.0) {
                    ranges.add(startTime..endTime)
                }
                rangeStart = -1
            }
        }
        if (rangeStart >= 0) {
            val startTime = (rangeStart.toDouble() * hopSize.toDouble()) / sampleRate.toDouble()
            if (durationSeconds - startTime > 1.0) {
                ranges.add(startTime..durationSeconds)
            }
        }
        return ranges
    }

    private fun computeSpectralCentroidCurve(mono: FloatArray, frameSize: Int, hopSize: Int): List<Float> {
        val curve = ArrayList<Float>()
        var index = 0
        val real = DoubleArray(frameSize)
        val imag = DoubleArray(frameSize)
        while (index + frameSize <= mono.size) {
            for (i in 0 until frameSize) {
                val window = 0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (frameSize - 1))
                real[i] = mono[index + i] * window
                imag[i] = 0.0
            }
            fft(real, imag)
            var weightedSum = 0.0
            var magnitudeSum = 0.0
            val half = frameSize / 2
            for (k in 0 until half) {
                val magnitude = sqrt(real[k] * real[k] + imag[k] * imag[k])
                val freq = k.toDouble() * sampleRate.toDouble() / frameSize.toDouble()
                weightedSum += freq * magnitude
                magnitudeSum += magnitude
            }
            val centroid = if (magnitudeSum > 0.0) (weightedSum / magnitudeSum).toFloat() else 0f
            curve.add(centroid)
            index += hopSize
        }
        return curve
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        if (n <= 1) return
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tempReal = real[i]
                real[i] = real[j]
                real[j] = tempReal
                val tempImag = imag[i]
                imag[i] = imag[j]
                imag[j] = tempImag
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * Math.PI / length
            val wReal = kotlin.math.cos(angle)
            val wImag = kotlin.math.sin(angle)
            var i = 0
            while (i < n) {
                var curReal = 1.0
                var curImag = 0.0
                for (k in 0 until length / 2) {
                    val evenReal = real[i + k]
                    val evenImag = imag[i + k]
                    val oddReal = real[i + k + length / 2]
                    val oddImag = imag[i + k + length / 2]
                    val tReal = curReal * oddReal - curImag * oddImag
                    val tImag = curReal * oddImag + curImag * oddReal
                    real[i + k] = evenReal + tReal
                    imag[i + k] = evenImag + tImag
                    real[i + k + length / 2] = evenReal - tReal
                    imag[i + k + length / 2] = evenImag - tImag
                    val nextCurReal = curReal * wReal - curImag * wImag
                    val nextCurImag = curReal * wImag + curImag * wReal
                    curReal = nextCurReal
                    curImag = nextCurImag
                }
                i += length
            }
            length = length shl 1
        }
    }
}

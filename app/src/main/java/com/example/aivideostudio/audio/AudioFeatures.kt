package com.example.aivideostudio.audio

data class AudioFeatures(
    val durationSeconds: Double,
    val bpm: Double,
    val beatTimestamps: List<Double>,
    val barTimestamps: List<Double>,
    val rmsEnergyCurve: List<Float>,
    val peakTimestamps: List<Double>,
    val quietSections: List<ClosedFloatingPointRange<Double>>,
    val intenseSections: List<ClosedFloatingPointRange<Double>>,
    val spectralCentroidCurve: List<Float>
)

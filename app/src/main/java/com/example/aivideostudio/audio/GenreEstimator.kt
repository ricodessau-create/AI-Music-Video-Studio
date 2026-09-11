package com.example.aivideostudio.audio

enum class SongGenre {
    BALLADE,
    POP,
    ROCK,
    METAL,
    HIP_HOP,
    OTHER
}

object GenreEstimator {

    fun estimate(features: AudioFeatures): SongGenre {
        val duration = features.durationSeconds
        if (duration <= 0.0) return SongGenre.OTHER

        val quietRatio = totalDuration(features.quietSections) / duration
        val intenseRatio = totalDuration(features.intenseSections) / duration
        val averageCentroid = if (features.spectralCentroidCurve.isNotEmpty()) {
            features.spectralCentroidCurve.average()
        } else {
            0.0
        }
        val bpm = features.bpm

        return when {
            bpm < 85.0 && quietRatio > 0.30 && intenseRatio < 0.20 -> SongGenre.BALLADE
            bpm >= 140.0 && intenseRatio > 0.35 -> SongGenre.METAL
            averageCentroid in 400.0..1800.0 && bpm in 80.0..115.0 -> SongGenre.HIP_HOP
            bpm in 100.0..150.0 && intenseRatio in 0.20..0.60 -> SongGenre.ROCK
            else -> SongGenre.POP
        }
    }

    private fun totalDuration(ranges: List<ClosedFloatingPointRange<Double>>): Double {
        var sum = 0.0
        for (range in ranges) {
            sum += (range.endInclusive - range.start)
        }
        return sum
    }
}

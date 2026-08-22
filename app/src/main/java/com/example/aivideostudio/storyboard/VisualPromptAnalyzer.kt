package com.example.aivideostudio.storyboard

data class VisualParameters(
    val colorPaletteKey: String,
    val hasSnowParticles: Boolean,
    val hasFireParticles: Boolean,
    val hasRuneOverlay: Boolean,
    val hasSmoke: Boolean,
    val hasRain: Boolean,
    val hasLightning: Boolean,
    val cameraPace: String,
    val cutAggressiveness: String,
    val darknessLevel: Float
)

class VisualPromptAnalyzer {

    private val darkKeywords = listOf("düster", "dark", "gothic", "black", "schwarz", "grim")
    private val iceKeywords = listOf("eis", "ice", "frozen", "gefroren", "schnee", "snow", "viking", "nordisch", "nordic")
    private val fireKeywords = listOf("feuer", "fire", "flamme", "flame", "funken", "spark")
    private val runeKeywords = listOf("rune", "runen", "runic")
    private val smokeKeywords = listOf("rauch", "smoke", "nebel", "fog", "mist")
    private val rainKeywords = listOf("regen", "rain", "sturm", "storm")
    private val lightningKeywords = listOf("blitz", "lightning", "gewitter", "thunder")
    private val epicKeywords = listOf("episch", "epic", "majestic", "grandios")
    private val brutalKeywords = listOf("brutal", "aggressive", "hart", "harsh", "violent")
    private val metalKeywords = listOf("metal", "industrial", "symphonic")

    fun analyze(prompt: String): VisualParameters {
        val normalized = prompt.lowercase()

        val darkScore = countMatches(normalized, darkKeywords)
        val hasIce = containsAny(normalized, iceKeywords)
        val hasFire = containsAny(normalized, fireKeywords)
        val hasRunes = containsAny(normalized, runeKeywords)
        val hasSmoke = containsAny(normalized, smokeKeywords)
        val hasRain = containsAny(normalized, rainKeywords)
        val hasLightning = containsAny(normalized, lightningKeywords)
        val isEpic = containsAny(normalized, epicKeywords)
        val isBrutal = containsAny(normalized, brutalKeywords)
        val isMetal = containsAny(normalized, metalKeywords)

        val cameraPace = if (isEpic && !isBrutal) "slow" else if (isBrutal) "fast" else "medium"
        val cutAggressiveness = if (isBrutal || isMetal) "hard" else "smooth"
        val darkness = (darkScore.toFloat() * 0.3f).coerceIn(0f, 1f)

        val paletteKey = when {
            hasIce && darkness > 0.2f -> "frozen_dark"
            hasFire && darkness > 0.2f -> "infernal_dark"
            hasIce -> "frozen"
            hasFire -> "infernal"
            darkness > 0.3f -> "gothic_dark"
            else -> "neutral"
        }

        return VisualParameters(
            colorPaletteKey = paletteKey,
            hasSnowParticles = hasIce,
            hasFireParticles = hasFire,
            hasRuneOverlay = hasRunes,
            hasSmoke = hasSmoke,
            hasRain = hasRain,
            hasLightning = hasLightning,
            cameraPace = cameraPace,
            cutAggressiveness = cutAggressiveness,
            darknessLevel = darkness
        )
    }

    private fun containsAny(text: String, keywords: List<String>): Boolean {
        return keywords.any { text.contains(it) }
    }

    private fun countMatches(text: String, keywords: List<String>): Int {
        return keywords.count { text.contains(it) }
    }
}

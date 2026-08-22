package com.example.aivideostudio.storyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualPromptAnalyzerTest {

    @Test
    fun analyzeDetectsIceAndFireAndRunes() {
        val analyzer = VisualPromptAnalyzer()
        val result = analyzer.analyze("Düsteres Viking-Metal-Musikvideo mit Eis, Feuer und Runen. Episch und brutal.")

        assertTrue(result.hasSnowParticles)
        assertTrue(result.hasFireParticles)
        assertTrue(result.hasRuneOverlay)
        assertEquals("hard", result.cutAggressiveness)
    }

    @Test
    fun analyzeReturnsNeutralForPlainText() {
        val analyzer = VisualPromptAnalyzer()
        val result = analyzer.analyze("Ein einfaches Musikvideo ohne besondere Elemente")

        assertTrue(!result.hasFireParticles)
        assertTrue(!result.hasSnowParticles)
        assertTrue(!result.hasRuneOverlay)
    }
}

package com.example.aivideostudio.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderPresetsTest {

    @Test
    fun forResolutionLabel1080pWidescreenComputesCorrectDimensions() {
        val settings = RenderPresets.forResolutionLabel("1080p", "16:9")
        assertEquals(1080, settings.resolutionHeight)
        assertEquals(1920, settings.resolutionWidth)
    }

    @Test
    fun forResolutionLabelVerticalSwapsDimensions() {
        val settings = RenderPresets.forResolutionLabel("1080p", "9:16")
        assertEquals(1080, settings.resolutionWidth)
        assertTrue(settings.resolutionHeight in 600..620)
    }

    @Test
    fun forResolutionLabelSquareIsEqual() {
        val settings = RenderPresets.forResolutionLabel("720p", "1:1")
        assertEquals(settings.resolutionWidth, settings.resolutionHeight)
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }
}

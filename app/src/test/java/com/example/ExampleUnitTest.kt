package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testColorDetectionThresholds() {
        // Red threshold: R > 180, G < 100, B < 100
        val isRed = { r: Int, g: Int, b: Int -> r > 180 && g < 100 && b < 100 }
        assertTrue("Pure red should be detected", isRed(255, 0, 0))
        assertTrue("Deep red (200, 50, 40) should be detected", isRed(200, 50, 40))
        assertTrue("Orange-yellow should NOT be detected as red", !isRed(220, 180, 20))

        // Green threshold: G > 150, R < 100
        val isGreen = { r: Int, g: Int, b: Int -> g > 150 && r < 100 }
        assertTrue("Pure green should be detected", isGreen(0, 255, 0))
        assertTrue("Cyan-green should be detected", isGreen(50, 180, 200))
        assertTrue("Yellow should NOT be detected as green", !isGreen(200, 200, 0))
    }

    @Test
    fun testTargetCoordinatesAndRoiBounds() {
        val targetX = DetectionState.TARGET_X
        val targetY = DetectionState.TARGET_Y
        val radius = DetectionState.ROI_RADIUS

        assertEquals(801, targetX)
        assertEquals(359, targetY)

        val startX = targetX - radius
        val endX = targetX + radius - 1
        val startY = targetY - radius
        val endY = targetY + radius - 1

        assertEquals(791, startX)
        assertEquals(810, endX)
        assertEquals(349, startY)
        assertEquals(368, endY)

        val width = endX - startX + 1
        val height = endY - startY + 1
        assertEquals(20, width)
        assertEquals(20, height)
    }

    @Test
    fun testSubtitleTextOutput() {
        assertEquals("STATUS: RED", DetectionResult.RED.subtitleText)
        assertEquals("STATUS: GREEN", DetectionResult.GREEN.subtitleText)
        assertEquals("STATUS: SCANNING...", DetectionResult.SCANNING.subtitleText)
    }
}

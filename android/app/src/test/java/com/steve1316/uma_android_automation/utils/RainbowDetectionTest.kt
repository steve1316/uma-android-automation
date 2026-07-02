package com.steve1316.uma_android_automation.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure rainbow-ring decision. The OpenCV pixel work in `detectRainbowRing` needs native OpenCV and is validated on-device via the debug test, but the "all three
 * hues must be present" rule that separates a rainbow from a look-alike background is pure and covered here.
 */
@DisplayName("Rainbow ring hue presence")
class RainbowDetectionTest {
    @Test
    @DisplayName("A ring needs all three pastel hues; two or fewer is not a rainbow")
    fun testRainbowHuesPresent() {
        assertEquals(3, rainbowHuesPresent(greenFraction = 0.06, cyanFraction = 0.15, pinkFraction = 0.09), "Green, cyan, and pink all present is a rainbow ring")
        assertEquals(1, rainbowHuesPresent(greenFraction = 0.0, cyanFraction = 0.80, pinkFraction = 0.0), "Cyan alone (e.g. a sky background) is not a ring")
        assertEquals(2, rainbowHuesPresent(greenFraction = 0.05, cyanFraction = 0.10, pinkFraction = 0.0), "Two hues without pink is not a ring")
        assertEquals(0, rainbowHuesPresent(greenFraction = 0.01, cyanFraction = 0.02, pinkFraction = 0.01), "All three below the presence floor is not a ring")
    }
}

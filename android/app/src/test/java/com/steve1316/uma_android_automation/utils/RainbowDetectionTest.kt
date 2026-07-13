package com.steve1316.uma_android_automation.utils

import com.steve1316.uma_android_automation.utils.CustomImageUtils.RainbowRingResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    @DisplayName("A ring also needs one vividly dominant hue, rejecting uniformly-faint look-alikes")
    fun testRainbowRingDominantHue() {
        assertTrue(
            RainbowRingResult(huesPresent = 3, greenFraction = 0.07, cyanFraction = 0.06, pinkFraction = 0.20, brightChromaticFraction = 0.5).isRainbow,
            "All three hues with a vivid dominant is a rainbow",
        )
        assertFalse(
            RainbowRingResult(huesPresent = 3, greenFraction = 0.058, cyanFraction = 0.037, pinkFraction = 0.043, brightChromaticFraction = 0.5).isRainbow,
            "All three barely above the floor but none dominant (an enlarged button's faint sparkle) is not a rainbow",
        )
        assertFalse(RainbowRingResult(huesPresent = 2, greenFraction = 0.0, cyanFraction = 0.20, pinkFraction = 0.10, brightChromaticFraction = 0.5).isRainbow, "Only two hues is never a rainbow")
    }
}

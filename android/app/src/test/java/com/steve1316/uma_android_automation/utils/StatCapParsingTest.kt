package com.steve1316.uma_android_automation.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure stat-cap denominator parser. The OCR crop and region tuning are validated on-device, but the "/NNNN" text -> plausible cap rule is pure and covered here.
 */
@DisplayName("Stat cap OCR parsing")
class StatCapParsingTest {
    @Test
    @DisplayName("parseStatCap extracts the plausible cap, taking the largest number when the value is also caught")
    fun testParseStatCap() {
        assertEquals(1416, parseStatCap("/1416"), "clean denominator")
        assertEquals(1800, parseStatCap("1800"), "cap without the slash")
        assertEquals(1200, parseStatCap("/ 1200"), "spaced slash")
        // If the crop also caught the current value, the cap is the larger number (a cap is always >= the current value).
        assertEquals(1464, parseStatCap("301/1464"), "value plus cap resolves to the cap")
        assertEquals(1416, parseStatCap("1300 1416"), "soft-zone value plus cap resolves to the cap")
    }

    @Test
    @DisplayName("parseStatCap rejects implausible or empty reads so the caller falls back to the per-scenario table")
    fun testParseStatCapRejectsNoise() {
        assertNull(parseStatCap("156"), "a lone current value is not a plausible cap")
        assertNull(parseStatCap(""), "empty text")
        assertNull(parseStatCap("abc"), "no digits")
        assertNull(parseStatCap("999"), "below the plausible cap band")
        assertNull(parseStatCap("2500"), "above the game hard cap")
    }

    /**
     * Unit tests for the rule that decides whether a Main screen number crop is unchanged, so its last read can be reused instead of OCR'd again. Measured on device, an unchanged
     * number varies by at most 1 pixel between turns while the two most alike digits differ by 200 pixels.
     */
    @DisplayName("Number crop reuse")
    @Nested
    inner class NumberCropReuseTest {
        /** A 88x50 crop, the size of a stat value at 1080p. */
        private val blank = IntArray(88 * 50) { 230 }

        /**
         * Darkens a run of pixels, like a stroke of a digit.
         *
         * @param base The crop to copy.
         * @param count How many pixels to darken.
         * @returns The changed copy.
         */
        private fun withDarkPixels(base: IntArray, count: Int): IntArray = base.copyOf().also { for (i in 0 until count) it[i] = 60 }

        @Test
        @DisplayName("An identical crop and one with a pixel of noise count as unchanged")
        fun unchangedCrops() {
            assertTrue(isSameNumberCrop(blank, blank.copyOf()))
            assertTrue(isSameNumberCrop(blank, withDarkPixels(blank, 1)))
        }

        @Test
        @DisplayName("A changed digit, even between the two most alike digits, counts as changed")
        fun changedDigit() {
            assertFalse(isSameNumberCrop(blank, withDarkPixels(blank, 200)))
            assertFalse(isSameNumberCrop(blank, withDarkPixels(blank, 40)))
        }

        @Test
        @DisplayName("Small brightness shifts below the difference level are ignored")
        fun brightnessShift() {
            assertTrue(isSameNumberCrop(blank, IntArray(blank.size) { 210 }))
        }

        @Test
        @DisplayName("A crop taken while the digits fade in still matches the same number")
        fun fadingDigits() {
            // On device, a number read mid fade-in had light gray digits on the same white background, a 1.89x contrast change from the next turn's crop.
            val digit = withDarkPixels(blank, 400)
            val faded = blank.copyOf().also { for (i in 0 until 400) it[i] = 150 }
            assertTrue(isSameNumberCrop(digit, faded))
            assertTrue(isSameNumberCrop(faded, digit))
            assertTrue(isSameNumberCrop(digit, IntArray(digit.size) { (digit[it] * 0.72).toInt() }))
        }

        @Test
        @DisplayName("A changed digit still counts as changed while fading in")
        fun fadingChangedDigit() {
            val digit = withDarkPixels(blank, 400)
            val otherFaded = blank.copyOf().also { for (i in 200 until 600) it[i] = 150 }
            assertFalse(isSameNumberCrop(digit, otherFaded))
            assertFalse(isSameNumberCrop(otherFaded, digit))
        }

        @Test
        @DisplayName("Crops of different sizes never match")
        fun sizeMismatch() {
            assertFalse(isSameNumberCrop(blank, IntArray(10) { 230 }))
        }
    }
}

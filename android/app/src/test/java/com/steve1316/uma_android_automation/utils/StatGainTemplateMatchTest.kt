package com.steve1316.uma_android_automation.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Unit tests for the stat gain template matcher's pixel match share and its one-hit-per-glyph rule. -1 is 0xFF: a fully opaque alpha, or "equal" from `Core.compare`. */
@DisplayName("Stat gain template matching")
class StatGainTemplateMatchTest {
    @Test
    @DisplayName("URA templates ignore their soft edge")
    fun testSoftEdgeIgnored() {
        // Two opaque pixels that match, two edge pixels that never equal the screen.
        val alpha = byteArrayOf(-1, -1, 120, 0)
        val equal = byteArrayOf(-1, -1, 0, 0)
        assertEquals(1.0, statGainPixelMatchRatio("1", equal, alpha))
        assertEquals(0.5, statGainPixelMatchRatio("1_mini", equal, alpha), "two-row templates keep the whole-box share")
    }

    @Test
    @DisplayName("Black paint over a rejected match does not count for URA templates")
    fun testPaintedPixelsIgnored() {
        // The matcher paints rejected boxes black, which equals the premultiplied template's transparent pixels.
        val alpha = byteArrayOf(-1, -1, 0, 0, 0, 0, 0, 0)
        val equal = byteArrayOf(0, 0, -1, -1, -1, -1, -1, -1)
        assertEquals(0.0, statGainPixelMatchRatio("7", equal, alpha))
        assertEquals(0.75, statGainPixelMatchRatio("7_mini_bold", equal, alpha))
    }

    @Test
    @DisplayName("A template with no fully opaque pixel never passes")
    fun testNoOpaquePixel() {
        assertEquals(0.0, statGainPixelMatchRatio("4", byteArrayOf(-1, -1), byteArrayOf(100, 0)))
    }

    /** The digits `constructIntegerFromMatches` would join from the kept hits, left to right. */
    private fun read(vararg matches: StatGainMatch): String = keepBestMatchPerGlyph(matches.toList(), 12.0).joinToString("") { it.template }

    @Test
    @DisplayName("A 1 on the stem of a 4 is dropped, so +4 does not read as 41")
    fun testOneOnFour() {
        assertEquals("+4", read(StatGainMatch("+", 36.0, 0.95), StatGainMatch("4", 120.0, 0.972), StatGainMatch("1", 122.0, 0.890)))
        assertEquals("+14", read(StatGainMatch("+", 36.0, 0.95), StatGainMatch("1", 76.0, 0.958), StatGainMatch("4", 120.0, 0.972), StatGainMatch("1", 122.0, 0.890)))
    }

    @Test
    @DisplayName("A 0 on a 9 is dropped, so +19 does not read as 109")
    fun testZeroOnNine() {
        assertEquals("+19", read(StatGainMatch("+", 24.0, 0.95), StatGainMatch("1", 65.0, 0.95), StatGainMatch("0", 108.0, 0.857), StatGainMatch("9", 108.0, 0.983)))
    }

    @Test
    @DisplayName("Separate digits stay, and two hits on one glyph keep the higher score")
    fun testSeparateDigitsStay() {
        assertEquals("14", read(StatGainMatch("1", 87.0, 0.86), StatGainMatch("4", 120.0, 0.97)), "33 px apart are two glyphs")
        assertEquals("11", read(StatGainMatch("1", 85.0, 0.95), StatGainMatch("1", 127.0, 0.95)))
        assertEquals("0", read(StatGainMatch("9", 108.0, 0.86), StatGainMatch("0", 108.0, 0.95)), "same x keeps the better match")
    }
}

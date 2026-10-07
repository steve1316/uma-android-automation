package com.steve1316.uma_android_automation.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Unit tests for the pure fan count and turns-remaining OCR parsers. */
@DisplayName("Fan count and turns remaining OCR parsing")
class CountParsingTest {
    @Test
    @DisplayName("parseFanCount reads a clean comma-grouped fan count")
    fun testParseFanCountClean() {
        assertEquals(201486, parseFanCount("201,486"), "real end-of-career read")
        assertEquals(1234567, parseFanCount("1,234,567"), "two group separators")
        assertEquals(201486, parseFanCount("201.486"), "period read in place of the comma")
        assertEquals(850, parseFanCount("850"), "small count with no separator")
    }

    @Test
    @DisplayName("parseFanCount ignores stray digits fused onto the grouped number")
    fun testParseFanCountStrayDigits() {
        assertEquals(201486, parseFanCount("201,486 1"), "separate stray digit after")
        assertEquals(201486, parseFanCount("201,4861"), "stray digit glued after")
        assertEquals(201486, parseFanCount("7 201,486"), "stray digit before")
    }

    @Test
    @DisplayName("parseFanCount returns null for empty or overflowing reads")
    fun testParseFanCountRejects() {
        assertNull(parseFanCount(""), "empty text")
        assertNull(parseFanCount("fans"), "no digits")
        assertNull(parseFanCount("123456789012"), "too large for an Int")
    }

    @Test
    @DisplayName("parseTurnsRemaining reads the countdown and treats a label as 0")
    fun testParseTurnsRemaining() {
        assertEquals(11, parseTurnsRemaining("11"), "two-digit countdown")
        assertEquals(1, parseTurnsRemaining("1"), "one turn left")
        assertEquals(0, parseTurnsRemaining("GO"), "GOAL label on a mandatory race turn")
        assertEquals(0, parseTurnsRemaining("Race Day"), "race day label")
    }

    @Test
    @DisplayName("parseTurnsRemaining returns null when the read overflows")
    fun testParseTurnsRemainingOverflow() {
        assertNull(parseTurnsRemaining("123456789012"), "too large for an Int")
    }
}

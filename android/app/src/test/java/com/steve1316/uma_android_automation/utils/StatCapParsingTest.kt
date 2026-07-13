package com.steve1316.uma_android_automation.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
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
}

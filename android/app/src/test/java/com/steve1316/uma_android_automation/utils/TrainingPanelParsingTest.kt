package com.steve1316.uma_android_automation.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Unit tests for the pure failure chance and training level OCR parsers. */
@DisplayName("Training panel OCR parsing")
class TrainingPanelParsingTest {
    @Test
    @DisplayName("parseFailureChance takes the digits in front of the percent sign")
    fun testParseFailureChance() {
        assertEquals(10, parseFailureChance("Failure 10%"), "label and value")
        assertEquals(0, parseFailureChance("0%"), "zero")
        assertEquals(5, parseFailureChance("1 5%"), "stray digit before the value")
        assertEquals(20, parseFailureChance("2o%"), "o read in place of 0")
        assertEquals(37, parseFailureChance("37"), "no percent sign")
        assertNull(parseFailureChance("120%"), "a read above 100 is an impossible misread")
    }

    @Test
    @DisplayName("parseFailureChance keeps the first two digits when the percent sign was read as a digit")
    fun testParseFailureChancePercentReadAsDigit() {
        // Seen on device: 11% read as "115", 14% read as "143" and "1451 14".
        assertEquals(11, parseFailureChance("115"))
        assertEquals(14, parseFailureChance("143"))
        assertEquals(14, parseFailureChance("1451 14"))
    }

    @Test
    @DisplayName("parseFailureChance rejects a read with no percent sign when the sign is required")
    fun testParseFailureChanceRequirePercent() {
        // Seen on device: a 0% bubble read as "133", which parsed as 13% and dropped the picked training.
        assertNull(parseFailureChance("133", requirePercent = true), "no percent sign")
        assertNull(parseFailureChance("U70", requirePercent = true), "a 0% bubble read with the percent sign as 70")
        assertEquals(0, parseFailureChance("0%", requirePercent = true), "percent sign present")
        assertEquals(13, parseFailureChance("133"), "the sign is optional by default")
    }

    @Test
    @DisplayName("parseFailureChance returns null when there are no digits")
    fun testParseFailureChanceRejects() {
        assertNull(parseFailureChance(""), "empty text")
        assertNull(parseFailureChance("Failure %"), "no digits")
    }

    @Test
    @DisplayName("parseTrainingLevel reads the digit after Lvl")
    fun testParseTrainingLevel() {
        assertEquals(4, parseTrainingLevel("Speed Lvl 4"), "facility name and level")
        assertEquals(1, parseTrainingLevel("lvl1"), "lower case with no space")
        assertNull(parseTrainingLevel("Lvl 7"), "levels only go to 5")
        assertNull(parseTrainingLevel("Speed"), "no level")
        assertNull(parseTrainingLevel(""), "empty text")
    }
}
